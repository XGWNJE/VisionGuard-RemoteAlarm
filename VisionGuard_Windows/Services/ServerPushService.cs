// ┌─────────────────────────────────────────────────────────┐
// │ ServerPushService.cs                                    │
// │ 角色：HTTP 报警上传 + WebSocket 客户端（心跳+命令接收）  │
// │ 线程：WS 接收在独立后台线程；PushAlert 用 Task.Run      │
// │ 依赖：SimpleJson, AlertEvent, LogManager                │
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
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
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
        private ClientWebSocket _ws;
        private CancellationTokenSource _wsCts;
        private Thread _wsThread;
        private bool _wsConnected;

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
            // 主动关闭 WS，立即中断 ReceiveAsync 阻塞
            try
            {
                if (_ws != null && _ws.State == WebSocketState.Open)
                    _ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "user disconnect",
                        CancellationToken.None).Wait(1000);
            }
            catch { /* 忽略关闭时异常 */ }
            SetWsState(false, "disconnected");  // 立即更新 UI，不等后台线程
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            _wsCts?.Cancel();
            _wsCts?.Dispose();
            try { _ws?.Abort(); } catch { }
            _ws?.Dispose();
        }

        // ════════════════════════════════════════════════════════════
        // WebSocket 自动重连循环
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
                SetWsState(false, "connecting");
                LogManager.StaticInfo($"[Server] WS 连接中 → {_serverUrl} (第{attempt + 1}次)");

                try
                {
                    _ws?.Dispose();
                    _ws = new ClientWebSocket();

                    // 将 http:// 换成 ws://，https:// → wss://
                    string wsUrl = _serverUrl
                        .Replace("https://", "wss://")
                        .Replace("http://",  "ws://");

                    _ws.ConnectAsync(new Uri(wsUrl + "/ws"), token)
                       .GetAwaiter().GetResult();

                    // 认证
                    var authMsg = new Dictionary<string, object>
                    {
                        ["type"]       = "auth",
                        ["apiKey"]     = _apiKey,
                        ["role"]       = "windows",
                        ["deviceId"]   = _deviceId,
                        ["deviceName"] = _deviceName,
                    };
                    WsSendJsonSync(_ws, authMsg, token);

                    // 等待 auth-result
                    string authReply = WsReceiveOneSync(_ws, token);
                    var authDict = SimpleJson.ParseDict(authReply);
                    bool success = authDict.TryGetValue("success", out object sv) && sv is bool b && b;
                    if (!success)
                    {
                        string reason = SimpleJson.GetString(authDict, "reason", "unknown");
                        LogManager.StaticWarn($"[Server] WS 认证失败: {reason}");
                        SetWsState(false, "disconnected");
                        // 认证失败退避，避免无限快速重试
                        token.WaitHandle.WaitOne(TimeSpan.FromSeconds(BackoffSeconds[Math.Min(attempt, BackoffSeconds.Length - 1)]));
                        attempt++;
                        continue;
                    }

                    SetWsState(true, "connected");
                    attempt = 0;  // 连接成功，重置退避
                    LogManager.StaticInfo("[Server] WS 已连接");

                    // 接收消息循环
                    while (!token.IsCancellationRequested && _ws.State == WebSocketState.Open)
                    {
                        string json = WsReceiveOneSync(_ws, token);
                        if (json == null) break;  // 连接关闭
                        HandleWsMessage(json);
                    }
                }
                catch (OperationCanceledException) { /* 主动取消，正常退出 */ }
                catch (Exception ex)
                {
                    LogManager.StaticWarn($"[Server] WS 断线: {ex.Message}");
                }

                SetWsState(false, "disconnected");  // 无论何种退出原因，都更新状态

                if (!token.IsCancellationRequested)
                {
                    int waitSec = BackoffSeconds[Math.Min(attempt, BackoffSeconds.Length - 1)];
                    LogManager.StaticInfo($"[Server] {waitSec}s 后重连...");
                    token.WaitHandle.WaitOne(TimeSpan.FromSeconds(waitSec));
                    attempt++;
                }
            }
        }

        private void HandleWsMessage(string json)
        {
            try
            {
                var d = SimpleJson.ParseDict(json);
                string type = SimpleJson.GetString(d, "type");

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
                // auth-result 已在连接阶段消费，此处忽略
            }
            catch { }
        }

        /// <summary>
        /// 发送命令回执（command-ack）给服务器，服务器再转发给 Android 端。
        /// </summary>
        public void SendCommandAck(string command, bool success, string reason = "")
        {
            if (!_configured || !_wsConnected) return;
            var msg = new Dictionary<string, object>
            {
                ["type"]    = "command-ack",
                ["command"] = command,
                ["success"] = success,
                ["reason"]  = reason ?? "",
            };
            WsSendJson(msg);
        }

        // ── WS 辅助 ──────────────────────────────────────────────────

        private void WsSendJson(Dictionary<string, object> msg)
        {
            if (_ws == null || _ws.State != WebSocketState.Open) return;
            try { WsSendJsonSync(_ws, msg, CancellationToken.None); }
            catch { }
        }

        private static void WsSendJsonSync(ClientWebSocket ws, Dictionary<string, object> msg, CancellationToken token)
        {
            byte[] data = Encoding.UTF8.GetBytes(SimpleJson.ToJson(msg));
            ws.SendAsync(new ArraySegment<byte>(data), WebSocketMessageType.Text, true, token)
              .GetAwaiter().GetResult();
        }

        private static string WsReceiveOneSync(ClientWebSocket ws, CancellationToken token)
        {
            var buf  = new byte[64 * 1024];
            var sb   = new StringBuilder();

            while (true)
            {
                WebSocketReceiveResult result;
                try
                {
                    result = ws.ReceiveAsync(new ArraySegment<byte>(buf), token)
                               .GetAwaiter().GetResult();
                }
                catch (OperationCanceledException) { throw; }
                catch { return null; }

                if (result.MessageType == WebSocketMessageType.Close) return null;

                sb.Append(Encoding.UTF8.GetString(buf, 0, result.Count));
                if (result.EndOfMessage) break;
            }

            return sb.ToString();
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
