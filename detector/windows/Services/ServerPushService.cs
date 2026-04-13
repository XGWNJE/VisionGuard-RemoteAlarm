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

        // 最后断开原因（用于日志）
        private string _lastDisconnectReason = "initial";
        private bool _lastDisconnectWasClean = false;

        // 心跳计数（用于减少日志频率）
        private int _heartbeatCount = 0;

        private bool _disposed;

        // ── 发送保护 ─────────────────────────────────────────────────
        // 心跳连续失败计数（避免单次抖动导致断连）
        private int _heartbeatFailCount = 0;
        private const int HEARTBEAT_FAIL_THRESHOLD = 3;   // 连续 3 次失败才触发重连
        private const int SEND_TIMEOUT_MS = 5000;         // 发送超时 5s，防止阻塞

        // ── WebSocket 关闭码翻译 ─────────────────────────────────────
        private static readonly Dictionary<ushort, string> CloseCodeReasons = new Dictionary<ushort, string>
        {
            { 1000, "正常关闭" },
            { 1001, "服务器关闭 (Going Away)" },
            { 1002, "协议错误" },
            { 1003, "不支持的数据类型" },
            { 1005, "无状态码 (Never closing)" },
            { 1006, "异常断开 (网络中断/服务器崩溃)" },
            { 1007, "消息格式错误" },
            { 1008, "消息内容违反策略" },
            { 1009, "消息过大" },
            { 1010, "必要扩展未协商成功" },
            { 1011, "服务器内部错误" },
            { 1015, "TLS 握手失败" },
        };

        private string GetCloseCodeReason(ushort code)
        {
            return CloseCodeReasons.TryGetValue(code, out var reason) ? reason : $"未知错误 (code={code})";
        }

        // ── 断开原因上报 ──────────────────────────────────────────
        private enum DisconnectReason {
            UserClose,
            NetworkError,
            ServerUnreachable,
            AuthFailed,
            Unknown
        }

        private void SendDisconnectReason(DisconnectReason reason, string detail = "")
        {
            if (!_configured || _ws == null) return;
            string reasonStr;
            switch (reason) {
                case DisconnectReason.UserClose: reasonStr = "user-close"; break;
                case DisconnectReason.NetworkError: reasonStr = "network-error"; break;
                case DisconnectReason.ServerUnreachable: reasonStr = "server-unreachable"; break;
                case DisconnectReason.AuthFailed: reasonStr = "auth-failed"; break;
                default: reasonStr = "unknown"; break;
            }
            var msg = new Dictionary<string, object>
            {
                ["type"] = "disconnect-reason",
                ["reason"] = reasonStr,
                ["detail"] = detail
            };
            try {
                _ws.Send(SimpleJson.ToJson(msg));
            } catch { /* 忽略发送失败 */ }
        }

        // ── 指数退避参数 ─────────────────────────────────────────────
        private static readonly int[] BackoffSeconds = { 2, 4, 8, 16, 30, 60 };

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
            if (!_configured) {
                LogManager.StaticInfo($"[Server] Configure: ServerUrl 为空，静默不启动连接");
                return;
            }

            LogManager.StaticInfo($"[Server] Configure: serverUrl={_serverUrl} deviceId={_deviceId} deviceName={_deviceName}");
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
            if (!WsSendJson(msg))
                LogManager.StaticWarn($"[Server] 报警发送失败 deviceId={_deviceId} alertId={alert.AlertId}");
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

        /// <summary>发送心跳（由 Form1 的 3 秒定时器调用）</summary>
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

            // 发送失败时：连续 HEARTBEAT_FAIL_THRESHOLD 次才触发重连，避免单次抖动断连
            if (!WsSendJson(msg))
            {
                _heartbeatFailCount++;
                if (_heartbeatFailCount >= HEARTBEAT_FAIL_THRESHOLD)
                {
                    LogManager.StaticWarn($"[Server] 心跳连续{_heartbeatFailCount}次发送失败，触发重连 deviceId={_deviceId}");
                    _wsCts?.Cancel();
                    try { _ws?.Close(); } catch { }
                    _heartbeatFailCount = 0; // 触发重连后重置计数
                }
                else
                {
                    LogManager.StaticInfo($"[Server] 心跳发送失败({_heartbeatFailCount}/{HEARTBEAT_FAIL_THRESHOLD}) deviceId={_deviceId}");
                }
                return;
            }

            // 发送成功，重置失败计数
            _heartbeatFailCount = 0;

            // 每 60 次心跳（约 3 分钟）打一次日志，避免刷屏
            _heartbeatCount++;
            if (_heartbeatCount == 1 || _heartbeatCount % 60 == 0)
            {
                LogManager.StaticInfo($"[Server] 心跳 #{_heartbeatCount} deviceId={_deviceId} monitoring={isMonitoring} alarming={isAlarming}");
            }
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

                LogManager.StaticInfo($"[Server] WS 连接尝试: 第{attempt + 1}次 URL={wsUrl}/ws 上次断开原因={_lastDisconnectReason}");

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
                        LogManager.StaticInfo($"[Server] WS OnOpen，发送认证 deviceId={_deviceId}");
                        _ws.Send(SimpleJson.ToJson(authMsg));
                    };

                    _ws.OnMessage += (sender, e) =>
                    {
                        HandleWsMessage(e.Data);
                    };

                    _ws.OnClose += (sender, e) =>
                    {
                        if (_disposed) return;
                        _lastDisconnectReason = GetCloseCodeReason(e.Code);
                        _lastDisconnectWasClean = e.WasClean;

                        // 根据 wasClean 和 code 判断断开原因
                        string disconnectType;
                        if (e.WasClean && e.Code == 1000) disconnectType = "客户端正常关闭";
                        else if (e.WasClean && e.Code == 1001) disconnectType = "服务器正常关闭";
                        else if (!e.WasClean && e.Code == 1006) disconnectType = "网络中断或服务器崩溃";
                        else if (!e.WasClean) disconnectType = "异常断开";
                        else disconnectType = "未知原因";
                        LogManager.StaticWarn($"[Server] WS OnClose: {disconnectType} code={e.Code}({GetCloseCodeReason(e.Code)}) wasClean={e.WasClean}");
                        SetWsState(false, "disconnected");
                    };

                    _ws.OnError += (sender, e) =>
                    {
                        if (_disposed) return;
                        string msg = e.Message ?? "unknown";
                        string detail = "";
                        string disconnectType = "网络错误";
                        // 根据错误消息模式匹配更详细的解释
                        if (msg.Contains("Unable to connect")) {
                            detail = " - 无法连接到服务器 (检查网络/服务器地址)";
                            disconnectType = "服务器不可达";
                        }
                        else if (msg.Contains("timeout")) {
                            detail = " - 连接超时 (服务器无响应)";
                            disconnectType = "连接超时";
                        }
                        else if (msg.Contains("refused")) {
                            detail = " - 连接被拒绝 (服务器未启动/端口错误)";
                            disconnectType = "连接被拒绝";
                        }
                        else if (msg.Contains("isn't established") || msg.Contains("has been closed")) {
                            detail = " - 连接已断开 (正常重连流程)";
                            disconnectType = "连接已断开";
                        }
                        // 更新断开原因用于下次重连日志
                        _lastDisconnectReason = disconnectType;
                        // 连接已断开时的 OnError 是正常重连流程，降级为 Info
                        if (detail == "" || detail.Contains("已断开"))
                            LogManager.StaticInfo($"[Server] WS OnError: {msg}{detail}");
                        else
                            LogManager.StaticWarn($"[Server] WS OnError: {msg}{detail}");
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
                        LogManager.StaticWarn($"[Server] WS 认证失败，{waitSec}s 后重连...");
                        token.WaitHandle.WaitOne(TimeSpan.FromSeconds(waitSec));
                        attempt++;
                        continue;
                    }

                    SetWsState(true, "connected");
                    attempt = 0;
                    _heartbeatCount = 0;
                    LogManager.StaticInfo($"[Server] WS 已连接 deviceId={_deviceId} 重试计数已重置");

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
            if (!WsSendJson(msg))
                LogManager.StaticWarn($"[Server] 命令确认发送失败 deviceId={_deviceId} command={command}");
        }

        // ── WS 辅助 ─────────────────────────────────────────────────

        /// <returns>true=发送成功，false=发送失败（连接已断开或超时）</returns>
        private bool WsSendJson(Dictionary<string, object> msg)
        {
            lock (_sendLock)
            {
                if (_ws == null || !_ws.IsAlive) return false;
                var json = SimpleJson.ToJson(msg);
                try
                {
                    // WebSocketSharp 的 Send 是同步的，CPU 高负载时可能长时间阻塞
                    // 使用 Task.Run + Wait 模拟 5s 超时，避免心跳线程被卡死
                    var task = Task.Run(() => _ws.Send(json));
                    if (task.Wait(SEND_TIMEOUT_MS))
                        return true; // 发送成功
                    // 超时不立即断连，由连续失败计数决定是否触发重连
                    LogManager.StaticWarn($"[Server] WS 发送超时({SEND_TIMEOUT_MS}ms) deviceId={_deviceId}");
                    return false;
                }
                catch (Exception ex)
                {
                    LogManager.StaticWarn($"[Server] WS 发送异常: {ex.Message} deviceId={_deviceId}");
                    return false;
                }
            }
        }

        private void SetWsState(bool connected, string stateName)
        {
            if (_wsConnected == connected) return; // 状态未变，跳过（避免 OnError+OnClose 重复触发）
            _wsConnected = connected;
            var ts = DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fffZ");
            LogManager.StaticInfo($"[Server][{ts}] WS 状态 → {stateName} deviceId={_deviceId ?? "n/a"}");
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
