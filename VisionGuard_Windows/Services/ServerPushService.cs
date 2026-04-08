// ┌─────────────────────────────────────────────────────────┐
// │ ServerPushService.cs                                    │
// │ 角色：HTTP 报警上传 + WebSocket 客户端（心跳+命令接收）  │
// │ 依赖：WebSocketSharp, SimpleJson, AlertEvent, LogManager │
// │ 对外 API：Configure(), PushAlert(), SendHeartbeat(),    │
// │           Disconnect(), IsConnected, 事件               │
// │ 特性：ServerUrl 为空时完全静默，离线失败不影响本地流程   │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;   
using System.Drawing.Imaging;
using System.IO;
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
        /// 上传报警（fire-and-forget）。
        /// 在调用线程克隆截图字节，然后 Task.Run 发 HTTP POST。
        /// 失败仅记录日志，不影响本地报警流程。
        /// </summary>
        public void PushAlert(AlertEvent alert)
        {
            if (!_configured || alert == null) return;

            // 立刻在调用线程克隆 PNG bytes（不持有 Bitmap 引用）
            byte[] pngBytes = null;
            try
            {
                if (alert.Snapshot != null)
                {
                    using (var ms = new MemoryStream())
                    {
                        alert.Snapshot.Save(ms, ImageFormat.Png);
                        pngBytes = ms.ToArray();
                    }
                }
            }
            catch { /* 截图克隆失败，继续上传 meta */ }

            var detections = alert.Detections;
            var timestamp  = alert.Timestamp;

            Task.Run(async () =>
            {
                try
                {
                    var meta = new Dictionary<string, object>
                    {
                        ["deviceId"]   = _deviceId,
                        ["deviceName"] = _deviceName,
                        ["timestamp"]  = timestamp.ToString("o"),
                        ["detections"] = BuildDetectionsPayload(detections),
                    };

                    using (var form = new MultipartFormDataContent())
                    {
                        var metaContent = new StringContent(
                            SimpleJson.ToJson(meta), Encoding.UTF8, "application/json");
                        form.Add(metaContent, "meta");

                        if (pngBytes != null && pngBytes.Length > 0)
                        {
                            var imgContent = new ByteArrayContent(pngBytes);
                            imgContent.Headers.ContentType =
                                new System.Net.Http.Headers.MediaTypeHeaderValue("image/png");
                            form.Add(imgContent, "screenshot", "screenshot.png");
                        }

                        var req = new HttpRequestMessage(HttpMethod.Post, _serverUrl + "/api/alert")
                        {
                            Content = form
                        };
                        req.Headers.Add("X-API-Key", _apiKey);

                        var resp = await _http.SendAsync(req).ConfigureAwait(false);
                        if (!resp.IsSuccessStatusCode)
                        {
                            string body = await resp.Content.ReadAsStringAsync().ConfigureAwait(false);
                            LogManager.StaticWarn($"[Server] 上传报警失败 {(int)resp.StatusCode}: {body}");
                        }
                    }
                }
                catch (Exception ex)
                {
                    LogManager.StaticWarn($"[Server] PushAlert 异常: {ex.Message}");
                }
            });
        }

        /// <summary>发送心跳（30秒定时器调用）</summary>
        public void SendHeartbeat(bool isMonitoring, bool isAlarming)
        {
            if (!_configured || !_wsConnected || _ws == null) return;

            var msg = new Dictionary<string, object>
            {
                ["type"]        = "heartbeat",
                ["deviceId"]    = _deviceId,
                ["isMonitoring"] = isMonitoring,
                ["isAlarming"]  = isAlarming,
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
                        LogManager.StaticWarn($"[Server] WS 错误: {msg}");
                        SetWsState(false, "disconnected");
                    };

                    // 启动连接
                    _ws.Connect();

                    // 等待认证完成
                    while (!_authCompleted && !token.IsCancellationRequested)
                    {
                        if (_authEvent.Wait(500)) break;
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
                        if (_authEvent.Wait(500)) { _authEvent.Reset(); _authCompleted = false; }
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
            if (_ws == null || !_ws.IsAlive) return;
            try { _ws.Send(SimpleJson.ToJson(msg)); }
            catch { }
        }

        private void SetWsState(bool connected, string stateName)
        {
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
