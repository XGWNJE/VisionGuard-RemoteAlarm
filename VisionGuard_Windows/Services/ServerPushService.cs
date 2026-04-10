// ┌─────────────────────────────────────────────────────────┐
// │ ServerPushService.cs                                    │
// │ 角色：WebSocket 客户端（心跳+命令接收+截图按需转发）     │
// │ 对外 API：Configure(), PushAlert(), SendHeartbeat(),    │
// │           Disconnect(), IsConnected, 事件               │
// │ 特性：ServerUrl 为空时完全静默，离线失败不影响本地流程   │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using WebSocketSharp;
using VisionGuard.Models;
using VisionGuard.Utils;

namespace VisionGuard.Services
{
    public sealed class ServerPushService : IDisposable
    {
        // ── 对外事件 ─────────────────────────────────────────────────
        /// <summary>"connected" / "disconnected" / "connecting"</summary>
        public event EventHandler<string> ConnectionStateChanged;

        /// <summary>"pause" / "resume" / "stop-alarm"</summary>
        public event EventHandler<string> CommandReceived;

        /// <summary>set-config 命令：key=配置项名, value=新值（字符串形式）</summary>
        public event EventHandler<KeyValuePair<string, string>> SetConfigReceived;

        // ── 配置 ─────────────────────────────────────────────────────
        private string _serverUrl;
        private string _apiKey;
        private string _deviceId;
        private string _deviceName;

        private bool _configured;

        // ── HTTP ─────────────────────────────────────────────────────
        private static readonly HttpClient _http = new HttpClient
        {
            Timeout = TimeSpan.FromSeconds(15)
        };

        // ── WebSocket ────────────────────────────────────────────────
        private WebSocket _ws;
        private CancellationTokenSource _wsCts;
        private Thread _wsThread;
        private bool _wsConnected;
        private bool _authCompleted;
        private readonly object _sendLock = new object();

        // 用于等待认证结果返回
        private readonly ManualResetEventSlim _authEvent = new ManualResetEventSlim(false);
        private bool _authSuccess;

        private bool _disposed;

        // ── 指数退避参数 ─────────────────────────────────────────────
        private static readonly int[] BackoffSeconds = { 1, 2, 4, 8, 16, 30 };

        // ════════════════════════════════════════════════════════════
        // 公开 API
        // ════════════════════════════════════════════════════════════

        /// <summary>
        /// 配置服务器参数并启动 WS 连接。
        /// ServerUrl 为空时静默不启动（保持当前版本行为）。
        /// </summary>
        public void Configure(string serverUrl, string apiKey, string deviceId, string deviceName)
        {
            _serverUrl  = (serverUrl ?? "").TrimEnd('/');
            _apiKey     = apiKey     ?? "";
            _deviceId   = deviceId   ?? "";
            _deviceName = deviceName ?? Environment.MachineName;

            _configured = !string.IsNullOrWhiteSpace(_serverUrl);
            if (!_configured) return;

            StartWsLoop();
        }

        /// <summary>
        /// 上传报警元数据（截图改为本地缓存，不上传到 VPS）。
        /// 截图由 Android 按需从 Windows 拉取。
        /// </summary>
        public void PushAlert(AlertEvent alert)
        {
            if (!_configured || alert == null) return;

            var msg = new Dictionary<string, object>
            {
                ["type"]      = "alert",
                ["alertId"]   = alert.AlertId,
                ["deviceId"]  = _deviceId,
                ["deviceName"] = _deviceName,
                ["timestamp"] = alert.Timestamp.ToString("o"),
                ["detections"] = BuildDetectionsPayload(alert.Detections),
            };
            WsSendJson(msg);
        }

        /// <summary>
        /// 处理 Android 按需请求的截图：读本地文件 → 等比缩放 → JPEG 压缩 → base64 → WS 发送。
        /// 在 ThreadPool 线程上执行，不阻塞 WS 接收线程。
        /// </summary>
        public void SendScreenshotData(string alertId)
        {
            if (!_configured || !_wsConnected || _ws == null) return;

            try
            {
                string path = AlertService.GetSnapshotPath(alertId);
                if (!File.Exists(path))
                {
                    LogManager.StaticWarn($"[Server] 截图不存在: {alertId}");
                    return;
                }

                using (var bmp = new Bitmap(path))
                {
                    // 等比缩放到最大宽 960px，减少传输大小
                    const int MaxW = 960;
                    Bitmap toSend;
                    if (bmp.Width > MaxW)
                    {
                        int newH = (int)(bmp.Height * (MaxW / (double)bmp.Width));
                        toSend = new Bitmap(bmp, new Size(MaxW, newH));
                    }
                    else
                    {
                        toSend = bmp;
                    }

                    try
                    {
                        using (var ms = new MemoryStream())
                        {
                            // JPEG 压缩质量 65，960px 宽下约 80-150KB
                            var jpegParams = new System.Drawing.Imaging.EncoderParameters(1);
                            jpegParams.Param[0] = new System.Drawing.Imaging.EncoderParameter(
                                System.Drawing.Imaging.Encoder.Quality, 65L);
                            var jpegCodec = System.Drawing.Imaging.ImageCodecInfo
                                .GetImageEncoders().FirstOrDefault(c => c.MimeType == "image/jpeg");
                            toSend.Save(ms, jpegCodec, jpegParams);
                            byte[] jpegBytes = ms.ToArray();

                            var msg = new Dictionary<string, object>
                            {
                                ["type"]        = "screenshot-data",
                                ["alertId"]     = alertId,
                                ["imageBase64"] = Convert.ToBase64String(jpegBytes),
                                ["width"]       = toSend.Width,
                                ["height"]      = toSend.Height,
                            };
                            WsSendJson(msg);
                            LogManager.StaticInfo($"[Server] 截图发送: {alertId} ({jpegBytes.Length} bytes, {toSend.Width}x{toSend.Height})");
                        }
                    }
                    finally
                    {
                        if (!ReferenceEquals(toSend, bmp)) toSend.Dispose();
                    }
                }
            }
            catch (Exception ex)
            {
                LogManager.StaticWarn($"[Server] SendScreenshotData 异常: {ex.Message}");
            }
        }

        /// <summary>发送心跳（5秒定时器调用）</summary>
        public void SendHeartbeat(bool isMonitoring, bool isAlarming, bool isReady,
            int cooldown, float confidence, string targets)
        {
            if (!_configured || !_wsConnected || _ws == null) return;

            var msg = new Dictionary<string, object>
            {
                ["type"]        = "heartbeat",
                ["deviceId"]    = _deviceId,
                ["isMonitoring"] = isMonitoring,
                ["isAlarming"]  = isAlarming,
                ["isReady"]     = isReady,
                ["cooldown"]    = cooldown,
                ["confidence"]  = confidence,
                ["targets"]     = targets,
            };
            WsSendJson(msg);
        }

        public bool IsConnected => _wsConnected;

        public void Disconnect()
        {
            _configured = false;          // 阻止心跳等后续操作
            _wsCts?.Cancel();             // 通知 WsLoop 退出
            try { _ws?.Close(); } catch { /* 忽略关闭时异常 */ }
            SetWsState(false, "disconnected");  // 立即更新 UI
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            _wsCts?.Cancel();
            _wsCts?.Dispose();
            _authEvent.Dispose();
            try { _ws?.Close(); } catch { }
        }

        // ════════════════════════════════════════════════════════════
        // WebSocket 自动重连循环（基于 WebSocketSharp 事件驱动）
        // ════════════════════════════════════════════════════════════

        private void StartWsLoop()
        {
            _wsCts?.Cancel();
            _wsCts = new CancellationTokenSource();
            var token = _wsCts.Token;

            _wsThread = new Thread(() => WsLoop(token))
            {
                IsBackground = true,
                Name = "VG_WsLoop"
            };
            _wsThread.Start();
        }

        private void WsLoop(CancellationToken token)
        {
            int attempt = 0;

            while (!token.IsCancellationRequested)
            {
                _authCompleted = false;
                _authEvent.Reset();

                string wsUrl = _serverUrl
                    .Replace("https://", "wss://")
                    .Replace("http://",  "ws://");

                LogManager.StaticInfo($"[Server] WS 连接中 → {_serverUrl} (第{attempt + 1}次)");

                try
                {
                    SetWsState(false, "connecting");

                    _ws?.Close();
                    _ws = new WebSocket(wsUrl + "/ws");

                    _ws.OnOpen += (sender, e) =>
                    {
                        // 连接建立，发送认证
                        var authMsg = new Dictionary<string, object>
                        {
                            ["type"]       = "auth",
                            ["apiKey"]     = _apiKey,
                            ["role"]       = "windows",
                            ["deviceId"]   = _deviceId,
                            ["deviceName"] = _deviceName,
                        };
                        _ws.Send(SimpleJson.ToJson(authMsg));
                    };

                    _ws.OnMessage += (sender, e) =>
                    {
                        HandleWsMessage(e.Data);
                    };

                    _ws.OnClose += (sender, e) =>
                    {
                        if (_disposed) return;
                        LogManager.StaticInfo($"[Server] WS 连接关闭 (code={e.Code})");
                        SetWsState(false, "disconnected");
                    };

                    _ws.OnError += (sender, e) =>
                    {
                        if (_disposed) return;
                        string msg = e.Message ?? "unknown";
                        // 连接已断开时的 OnError 是正常重连流程，降级为 Info
                        if (msg.Contains("isn't established") || msg.Contains("has been closed"))
                            LogManager.StaticInfo($"[Server] WS 连接已断开，将自动重连");
                        else
                            LogManager.StaticWarn($"[Server] WS 错误: {msg}");
                        SetWsState(false, "disconnected");
                    };

                    // 启动连接
                    _ws.Connect();

                    // 等待认证完成
                    while (!_authCompleted && !token.IsCancellationRequested)
                    {
                        if (_disposed) return;
                        try { if (_authEvent.Wait(500)) break; }
                        catch (ObjectDisposedException) { return; }
                    }

                    if (token.IsCancellationRequested) break;

                    if (!_authSuccess)
                    {
                        int waitSec = BackoffSeconds[Math.Min(attempt, BackoffSeconds.Length - 1)];
                        LogManager.StaticInfo($"[Server] {waitSec}s 后重连...");
                        token.WaitHandle.WaitOne(TimeSpan.FromSeconds(waitSec));
                        attempt++;
                        continue;
                    }

                    SetWsState(true, "connected");
                    attempt = 0;
                    LogManager.StaticInfo("[Server] WS 已连接");

                    // 保持线程存活直到连接断开或取消
                    while (!token.IsCancellationRequested && _ws != null && _ws.IsAlive)
                    {
                        if (_disposed) break;
                        try { if (_authEvent.Wait(500)) { _authEvent.Reset(); _authCompleted = false; } }
                        catch (ObjectDisposedException) { break; }
                    }
                }
                catch (OperationCanceledException) { /* 主动取消，正常退出 */ }
                catch (Exception ex)
                {
                    LogManager.StaticWarn($"[Server] WS 异常: {ex.Message}");
                    SetWsState(false, "disconnected");

                    if (!token.IsCancellationRequested)
                    {
                        int waitSec = BackoffSeconds[Math.Min(attempt, BackoffSeconds.Length - 1)];
                        LogManager.StaticInfo($"[Server] {waitSec}s 后重连...");
                        token.WaitHandle.WaitOne(TimeSpan.FromSeconds(waitSec));
                        attempt++;
                    }
                }
            }
        }

        private void HandleWsMessage(string json)
        {
            try
            {
                var d = SimpleJson.ParseDict(json);
                string type = SimpleJson.GetString(d, "type");

                if (type == "auth-result")
                {
                    _authSuccess = d.TryGetValue("success", out object sv) && sv is bool b && b;
                    if (!_authSuccess)
                    {
                        string reason = SimpleJson.GetString(d, "reason", "unknown");
                        LogManager.StaticWarn($"[Server] WS 认证失败: {reason}");
                        SetWsState(false, "disconnected");
                    }
                    _authCompleted = true;
                    _authEvent.Set();
                    return;
                }

                if (type == "command")
                {
                    string cmd = SimpleJson.GetString(d, "command");
                    if (!string.IsNullOrEmpty(cmd))
                        CommandReceived?.Invoke(this, cmd);
                }
                else if (type == "set-config")
                {
                    string key = SimpleJson.GetString(d, "key");
                    string val = SimpleJson.GetString(d, "value");
                    if (!string.IsNullOrEmpty(key))
                        SetConfigReceived?.Invoke(this, new KeyValuePair<string, string>(key, val));
                }
                else if (type == "request-screenshot")
                {
                    string alertId = SimpleJson.GetString(d, "alertId");
                    if (!string.IsNullOrEmpty(alertId))
                        Task.Run(() => SendScreenshotData(alertId));   // 异步，不阻塞 WS 接收线程
                }
            }
            catch { }
        }

        /// <summary>
        /// 发送命令回执（command-ack）给服务器，服务器再转发给 Android 端。
        /// </summary>
        public void SendCommandAck(string command, bool success, string reason = "")
        {
            if (!_configured || !_wsConnected || _ws == null) return;
            var msg = new Dictionary<string, object>
            {
                ["type"]    = "command-ack",
                ["command"] = command,
                ["success"] = success,
                ["reason"]  = reason ?? "",
            };
            WsSendJson(msg);
        }

        // ── WS 辅助 ─────────────────────────────────────────────────

        private void WsSendJson(Dictionary<string, object> msg)
        {
            lock (_sendLock)
            {
                if (_ws == null || !_ws.IsAlive) return;
                try { _ws.Send(SimpleJson.ToJson(msg)); }
                catch { }
            }
        }

        private void SetWsState(bool connected, string stateName)
        {
            if (_wsConnected == connected) return; // 状态未变，跳过（避免 OnError+OnClose 重复触发）
            _wsConnected = connected;
            try { ConnectionStateChanged?.Invoke(this, stateName); }
            catch { }
        }

        // ── 序列化辅助 ───────────────────────────────────────────────

        private static List<Dictionary<string, object>> BuildDetectionsPayload(
            System.Collections.Generic.IReadOnlyList<Detection> detections)
        {
            var list = new List<Dictionary<string, object>>();
            if (detections == null) return list;
            foreach (var d in detections)
            {
                list.Add(new Dictionary<string, object>
                {
                    ["label"]      = d.Label ?? "",
                    ["confidence"] = Math.Round(d.Confidence, 4),
                    ["bbox"]       = new Dictionary<string, object>
                    {
                        ["x"] = (int)d.BoundingBox.X,
                        ["y"] = (int)d.BoundingBox.Y,
                        ["w"] = (int)d.BoundingBox.Width,
                        ["h"] = (int)d.BoundingBox.Height,
                    },
                });
            }
            return list;
        }
    }
}
