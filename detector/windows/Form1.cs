// ┌─────────────────────────────────────────────────────────┐
// │ Form1.cs                                                │
// │ 角色：主窗体 — 布局+事件+设置持久化                     │
// │ 布局：左侧菜单导航(72px) + 中间预览+日志 + 右侧分页    │
// │ 线程：所有 UI 操作在主线程，回调通过 BeginInvoke 转发   │
// │ 依赖：MonitorService, AlertService, LogManager          │
// │ 页面：捕获(默认) / 参数 / 目标 / 服务器                 │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Windows.Forms;
using VisionGuard.Capture;
using VisionGuard.Data;
using VisionGuard.Models;
using VisionGuard.Services;
using VisionGuard.UI;
using VisionGuard.Utils;

namespace VisionGuard
{
    public partial class Form1 : Form
    {
        // ── 服务 ────────────────────────────────────────────────────
        private AlertService   _alertService;
        private MonitorService _monitorService;
        private LogManager     _log;

        // ── 高 DPI ──────────────────────────────────────────────────
        private float _scaleFactor = 1.0f;

        // ── 菜单 ───────────────────────────────────────────────────
        private Panel _menuPanel;
        private MenuButton _menuCapture, _menuParams, _menuTargets, _menuServer;
        private MenuButton[] _allMenuButtons;

        // ── 预览 / 日志 ──────────────────────────────────────────────
        private SplitContainer        _leftSplit;
        private DetectionOverlayPanel _overlayPanel;
        private OwnerDrawListBox      _lstLog;

        // ── 页面容器 ────────────────────────────────────────────────
        private Panel _pageContainer;
        private Panel _pageCapture, _pageParams, _pageTargets, _pageServer;

        // ── 捕获页 控件 ─────────────────────────────────────────────
        private Label           _lblRegionInfo;
        private FlatRoundButton _btnSelectRegion;
        private FlatRoundButton _btnPickWindow;
        private FlatRoundButton _btnStart, _btnStop;

        // ── 参数页 控件 ─────────────────────────────────────────────
        private TextBox         _txtFps, _txtThreads, _txtCooldown;
        private DarkSlider      _trkThreshold;
        private Label           _lblThreshold;

        // ── 目标页 控件 ─────────────────────────────────────────────
        private CocoClassPickerControl _classPicker;

        // ── 服务器页 控件 ────────────────────────────────────────────
        private TextBox  _txtDeviceName;
        private Label    _lblConnState;
        private Label    _lblConnDetail;   // 第二行：详细状态说明
        private FlatRoundButton _btnRetry; // 手动重试按钮

        // ── 服务器硬编码常量 ──────────────────────────────────────────
        private const string ServerUrl = "http://66.154.112.91:3000";
        private const string ServerApiKey = "XG-VisionGuard-2024";

        // ── ServerPushService + 心跳定时器 ───────────────────────────
        private ServerPushService _serverPushService;
        private System.Windows.Forms.Timer _heartbeatTimer;

        // ── 状态栏 ──────────────────────────────────────────────────
        private ToolStripStatusLabel _tsStatus, _tsLastAlert, _tsInferMs;

        // ── 系统托盘 ──────────────────────────────────────────────
        private NotifyIcon _notifyIcon;

        // ── 运行时目标窗口（不持久化 HWND）──────────────────────────
        private WindowInfo    _targetWindow;   // null = 屏幕区域模式
        private Rectangle     _screenRegion;   // ScreenRegion 模式下的坐标
        private Rectangle     _windowSubRegion; // WindowHandle 子区域

        private string ModelPath => Path.Combine(
            AppDomain.CurrentDomain.BaseDirectory, "Assets", "yolov5nu.onnx");

        // ════════════════════════════════════════════════════════════
        // 构造
        // ════════════════════════════════════════════════════════════

        public Form1()
        {
            InitializeComponent();

            // 高 DPI：在任何控件创建前确定缩放系数
            AutoScaleMode       = AutoScaleMode.Dpi;
            AutoScaleDimensions = new SizeF(96F, 96F);

            BuildUI();

            _alertService   = new AlertService();
            _monitorService = new MonitorService(_alertService);
            _log            = new LogManager(_lstLog);
            _serverPushService = new ServerPushService();

            _alertService.AlertTriggered   += OnAlertTriggered;
            _monitorService.FrameProcessed += OnFrameProcessed;

            SetupTrayIcon();
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);

            this.Text = "VisionGuard";

            // 构建4个页面内容
            BuildCapturePage();
            BuildParamsPage();
            BuildTargetsPage();
            BuildServerPage();

            WireEvents();
            LoadSettings();
            UpdateControlState(started: false);
            ShowPage(_pageCapture, _menuCapture);
            ApplySplitterRatio();

            _log.Info("VisionGuard 已就绪，请选择捕获区域或目标窗口后点击「开始」。");
        }

        // ════════════════════════════════════════════════════════════
        // 高 DPI
        // ════════════════════════════════════════════════════════════

        protected override void OnLoad(EventArgs e)
        {
            base.OnLoad(e);
            _scaleFactor = DeviceDpi / 96.0f;
        }

        protected override void OnDpiChanged(DpiChangedEventArgs e)
        {
            base.OnDpiChanged(e);
            _scaleFactor = e.DeviceDpiNew / 96.0f;

            // DPI 变化时重新调整窗口大小（固定逻辑尺寸 960×640）
            int w = (int)(960 * _scaleFactor);
            int h = (int)(640 * _scaleFactor);
            Size = new Size(w, h);

            ApplySplitterRatio();
            _overlayPanel?.Invalidate();
        }

        // ════════════════════════════════════════════════════════════
        // 按钮事件
        // ════════════════════════════════════════════════════════════

        // ── 选择屏幕区域 ─────────────────────────────────────────────

        private void BtnSelectRegion_Click(object sender, EventArgs e)
        {
            if (_targetWindow != null)
            {
                // WindowHandle 模式：在目标窗口截图上框选子区域
                Bitmap snapshot;
                try
                {
                    snapshot = WindowCapturer.CaptureWindow(_targetWindow.Handle, Rectangle.Empty);
                }
                catch (Exception ex)
                {
                    _log.Error("无法捕获目标窗口截图：" + ex.Message);
                    return;
                }

                using (snapshot)
                using (var selector = new RegionSelectorForm(snapshot))
                {
                    selector.ShowDialog(this);
                    if (selector.SelectedRegion != Rectangle.Empty)
                    {
                        _windowSubRegion = selector.SelectedRegion;
                        _log.Info($"已选择子区域：{_windowSubRegion.Width}×{_windowSubRegion.Height} @ ({_windowSubRegion.X},{_windowSubRegion.Y})");
                    }
                    else
                    {
                        _windowSubRegion = Rectangle.Empty;
                        _log.Info("子区域已清除，将捕获整个窗口。");
                    }
                    UpdateRegionLabel();
                }
            }
            else
            {
                // ScreenRegion 模式：全屏半透明遮罩拖拽
                using (var selector = new RegionSelectorForm())
                {
                    Hide();
                    selector.ShowDialog(this);
                    Show();
                    BringToFront();

                    if (selector.SelectedRegion != Rectangle.Empty)
                    {
                        _screenRegion = selector.SelectedRegion;
                        _log.Info($"已选择区域：({_screenRegion.X}, {_screenRegion.Y})  {_screenRegion.Width}×{_screenRegion.Height}");
                        UpdateRegionLabel();
                    }
                }
            }
        }

        // ── 选择目标窗口 ─────────────────────────────────────────────

        private void BtnPickWindow_Click(object sender, EventArgs e)
        {
            using (var picker = new WindowPickerForm(Handle))
            {
                if (picker.ShowDialog(this) == DialogResult.OK && picker.SelectedWindow != null)
                {
                    _targetWindow    = picker.SelectedWindow;
                    _windowSubRegion = Rectangle.Empty;  // 重置子区域
                    _btnSelectRegion.Text = "选择子区域…";
                    UpdateRegionLabel();
                    _log.Info($"已选择目标窗口：{_targetWindow.Title}  [{_targetWindow.ClassName}]");
                }
            }
        }

        // ── 开始监控 ─────────────────────────────────────────────────

        private void BtnStart_Click(object sender, EventArgs e) => StartMonitor(remote: false);

        /// <summary>
        /// 启动监控推理。remote=true 时失败通过 command-ack 返回，不弹 MessageBox。
        /// </summary>
        private void StartMonitor(bool remote)
        {
            if (_monitorService.IsStarted)
            {
                if (remote) _serverPushService.SendCommandAck("resume", false, "监控已在运行");
                return;
            }

            if (!File.Exists(ModelPath))
            {
                if (remote)
                {
                    _serverPushService.SendCommandAck("resume", false, "模型文件不存在");
                    _log.Warn("[Server] 收到 resume，但模型文件不存在。");
                }
                else
                {
                    MessageBox.Show(
                        $"找不到模型文件：\n{ModelPath}\n\n请参阅 Assets/ASSETS_README.md。",
                        "模型缺失", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
                return;
            }

            MonitorConfig cfg = BuildConfig();

            // 验证有效捕获源
            if (cfg.CaptureMode == CaptureMode.ScreenRegion)
            {
                if (cfg.CaptureRegion.Width < 32 || cfg.CaptureRegion.Height < 32)
                {
                    if (remote)
                    {
                        _serverPushService.SendCommandAck("resume", false, "未选择捕获区域，请先在捕获页框选区域");
                        _log.Warn("[Server] 收到 resume，但捕获区域未设置。");
                    }
                    else
                    {
                        MessageBox.Show("捕获区域太小（最小 32×32），请重新选择。",
                            "区域无效", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    }
                    return;
                }
            }
            else // WindowHandle
            {
                if (cfg.TargetWindowHandle == IntPtr.Zero)
                {
                    if (remote)
                    {
                        _serverPushService.SendCommandAck("resume", false, "目标窗口句柄无效，请重新选择");
                        _log.Warn("[Server] 收到 resume，但目标窗口句柄无效。");
                    }
                    else
                    {
                        MessageBox.Show("请先点击「选择窗口…」选择目标窗口。",
                            "未选择目标窗口", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    }
                    return;
                }
            }

            try
            {
                _monitorService.Start(ModelPath, cfg);
                UpdateControlState(started: true);
                string src = cfg.CaptureMode == CaptureMode.WindowHandle
                    ? $"窗口「{cfg.TargetWindowTitle}」"
                    : $"区域 {cfg.CaptureRegion}";
                _log.Info($"监控已启动 | {src} | {cfg.TargetFps} FPS | 阈值 {cfg.ConfidenceThreshold:P0}");
                if (remote)
                {
                    _serverPushService.SendCommandAck("resume", true);
                    _log.Info("[Server] 收到 resume，监控推理已启动。");
                }

                _heartbeatTimer?.Start(); // 定时器已在 LoadSettings 创建，此处确保运行中
            }
            catch (Exception ex)
            {
                string fullMsg = BuildExceptionMessage(ex);
                _log.Error("启动失败：" + fullMsg);
                if (remote)
                    _serverPushService.SendCommandAck("resume", false, "启动异常: " + ex.Message);
                else
                    MessageBox.Show(fullMsg, "启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        // ── 停止监控 ─────────────────────────────────────────────────

        private void BtnStop_Click(object sender, EventArgs e) => StopMonitor(remote: false);

        /// <summary>
        /// 停止监控推理。remote=true 时通过 command-ack 返回结果。
        /// </summary>
        private void StopMonitor(bool remote)
        {
            if (!_monitorService.IsStarted)
            {
                if (remote) _serverPushService.SendCommandAck("pause", false, "监控未运行");
                return;
            }

            _monitorService.Stop();
            // 不停止心跳定时器：服务器依赖心跳判断在线状态，停止心跳会导致 Android 误报掉线
            // isMonitoring=false 通过下次 tick 自动传递，同时立即发一次最终状态
            _serverPushService.SendHeartbeat(isMonitoring: false, isAlarming: false, isReady: IsRegionReady,
                cooldown: ParseInt(_txtCooldown.Text, 1, 300, 5),
                confidence: _trkThreshold.Value / 100f,
                targets: string.Join(",", _classPicker.SelectedClasses));
            UpdateControlState(started: false);
            _log.Info(remote ? "[Server] 收到 pause，监控推理已停止。" : "监控已停止。");
            if (remote) _serverPushService.SendCommandAck("pause", true);
        }

        // ════════════════════════════════════════════════════════════
        // MonitorService 回调（ThreadPool 线程）
        // ════════════════════════════════════════════════════════════

        private void OnFrameProcessed(object sender, FrameResultEventArgs e)
        {
            if (e.HasError)
            {
                _log.Error(e.Error.Message);
                return;
            }

            _overlayPanel.UpdateFrame(e.Frame, e.Detections);

            string inferText = $"推理 {e.InferenceMs} ms";
            BeginInvoke(new Action(() => _tsInferMs.Text = inferText));
        }

        private void OnAlertTriggered(object sender, AlertEvent e)
        {
            string msg = string.Empty;
            foreach (var d in e.Detections)
                msg += $"[{d.Label} {d.Confidence:P0}] ";

            _log.Warn("报警：" + msg.Trim());

            BeginInvoke(new Action(() =>
                _tsLastAlert.Text = "最后报警：" + e.Timestamp.ToString("HH:mm:ss")));

            // 推送到服务器（fire-and-forget，内部克隆 PNG bytes）
            _serverPushService.PushAlert(e);

            e.Snapshot?.Dispose();
        }

        // ════════════════════════════════════════════════════════════
        // 配置构建
        // ════════════════════════════════════════════════════════════

        private MonitorConfig BuildConfig()
        {
            var cfg = new MonitorConfig
            {
                ConfidenceThreshold  = _trkThreshold.Value / 100f,
                TargetFps            = ParseInt(_txtFps.Text,      1,   5,  2),
                IntraOpNumThreads    = ParseInt(_txtThreads.Text,  1,   8,  2),
                AlertCooldownSeconds = ParseInt(_txtCooldown.Text, 1, 300,  5),
                WatchedClasses       = _classPicker.SelectedClasses,
                SaveAlertSnapshot    = true,
            };

            if (_targetWindow != null)
            {
                cfg.CaptureMode          = CaptureMode.WindowHandle;
                cfg.TargetWindowTitle    = _targetWindow.Title;
                cfg.TargetWindowHandle   = _targetWindow.Handle;
                cfg.WindowSubRegion      = _windowSubRegion;
                cfg.CaptureRegion = _windowSubRegion != Rectangle.Empty
                    ? _windowSubRegion
                    : _targetWindow.Bounds;
            }
            else
            {
                cfg.CaptureMode   = CaptureMode.ScreenRegion;
                cfg.CaptureRegion = _screenRegion;
            }

            return cfg;
        }

        // ════════════════════════════════════════════════════════════
        // 设置持久化
        // ════════════════════════════════════════════════════════════

        private void LoadSettings()
        {
            SettingsStore.Load();

            // 阈值 / 参数
            _trkThreshold.Value = Math.Max(_trkThreshold.Minimum,
                Math.Min(_trkThreshold.Maximum, SettingsStore.GetInt("ConfidenceThresholdPct", 45)));
            _txtFps.Text      = SettingsStore.GetInt("TargetFps",            2).ToString();
            _txtThreads.Text  = SettingsStore.GetInt("IntraOpNumThreads",    2).ToString();
            _txtCooldown.Text = SettingsStore.GetInt("AlertCooldownSeconds", 5).ToString();

            // 监控对象（中英文选择器）
            HashSet<string> watched = SettingsStore.GetStringList("WatchedClasses");
            _classPicker.SetSelection(watched);

            // 捕获模式
            string modeStr = SettingsStore.GetString("CaptureMode", CaptureMode.ScreenRegion.ToString());
            if (Enum.TryParse(modeStr, out CaptureMode mode) && mode == CaptureMode.WindowHandle)
            {
                string title = SettingsStore.GetString("TargetWindowTitle", string.Empty);
                if (!string.IsNullOrEmpty(title))
                {
                    var windows = WindowEnumerator.GetWindows(Handle);
                    WindowInfo found = null;
                    foreach (var w in windows)
                        if (w.Title.Equals(title, StringComparison.OrdinalIgnoreCase))
                        { found = w; break; }

                    if (found != null)
                    {
                        _targetWindow = found;
                        _btnSelectRegion.Text = "选择子区域…";
                        _log.Info($"已恢复目标窗口：{found.Title}");
                    }
                    else
                    {
                        _log.Warn($"目标窗口「{title}」未找到，已回退到屏幕区域模式。");
                    }
                }

                string subStr = SettingsStore.GetString("WindowSubRegion", string.Empty);
                if (!string.IsNullOrEmpty(subStr))
                {
                    var parts = subStr.Split(',');
                    if (parts.Length == 4
                        && int.TryParse(parts[0], out int x)
                        && int.TryParse(parts[1], out int y)
                        && int.TryParse(parts[2], out int w)
                        && int.TryParse(parts[3], out int h))
                    {
                        _windowSubRegion = new Rectangle(x, y, w, h);
                    }
                }
            }
            else
            {
                string regStr = SettingsStore.GetString("ScreenRegion", string.Empty);
                if (!string.IsNullOrEmpty(regStr))
                {
                    var parts = regStr.Split(',');
                    if (parts.Length == 4
                        && int.TryParse(parts[0], out int x)
                        && int.TryParse(parts[1], out int y)
                        && int.TryParse(parts[2], out int w)
                        && int.TryParse(parts[3], out int h))
                    {
                        _screenRegion = new Rectangle(x, y, w, h);
                    }
                }
            }

            UpdateRegionLabel();

            // 服务器页：恢复设备名
            _txtDeviceName.Text = SettingsStore.GetString("DeviceName", Environment.MachineName);

            // 启动时自动连接（服务器地址/Key 已硬编码）
            {
                string deviceId = EnsureDeviceId();
                WireServerPushEvents();
                _serverPushService.Configure(
                    ServerUrl,
                    ServerApiKey,
                    deviceId,
                    _txtDeviceName.Text.Trim());
                _log.Info("[Server] 自动连接中…");
            }

            // 启动心跳定时器（5秒）—— 连接建立后立即开始，无论监控是否运行
            // SendHeartbeat 内部有 _wsConnected 守卫，未连接时自动跳过
            _heartbeatTimer = new System.Windows.Forms.Timer { Interval = 5000 };
            _heartbeatTimer.Tick += (s, ev) =>
                _serverPushService.SendHeartbeat(
                    isMonitoring: _monitorService.IsStarted,
                    isAlarming:   _alertService.IsAlarming,
                    isReady:       IsRegionReady,
                    cooldown:      ParseInt(_txtCooldown.Text, 1, 300, 5),
                    confidence:    _trkThreshold.Value / 100f,
                    targets:       string.Join(",", _classPicker.SelectedClasses));
            _heartbeatTimer.Start();
        }

        private void SaveSettings()
        {
            SettingsStore.Set("ConfidenceThresholdPct",  _trkThreshold.Value);
            SettingsStore.Set("TargetFps",               ParseInt(_txtFps.Text,      1,  5, 2));
            SettingsStore.Set("IntraOpNumThreads",        ParseInt(_txtThreads.Text,  1,  8, 2));
            SettingsStore.Set("AlertCooldownSeconds",     ParseInt(_txtCooldown.Text, 1, 300, 5));

            SettingsStore.Set("WatchedClasses",
                string.Join(",", _classPicker.SelectedClasses));

            // 服务器设置：只保存设备名
            SettingsStore.Set("DeviceName", _txtDeviceName.Text.Trim());

            if (_targetWindow != null)
            {
                SettingsStore.Set("CaptureMode",       CaptureMode.WindowHandle.ToString());
                SettingsStore.Set("TargetWindowTitle",  _targetWindow.Title);
                SettingsStore.Set("WindowSubRegion",
                    _windowSubRegion == Rectangle.Empty
                        ? string.Empty
                        : $"{_windowSubRegion.X},{_windowSubRegion.Y},{_windowSubRegion.Width},{_windowSubRegion.Height}");
            }
            else
            {
                SettingsStore.Set("CaptureMode", CaptureMode.ScreenRegion.ToString());
                SettingsStore.Set("ScreenRegion",
                    $"{_screenRegion.X},{_screenRegion.Y},{_screenRegion.Width},{_screenRegion.Height}");
            }

            SettingsStore.Save();
        }

        // ── 服务器设置辅助 ────────────────────────────────────────────

        /// <summary>首次调用时自动生成 DeviceId 并持久化，用户不可见</summary>
        private string EnsureDeviceId()
        {
            string id = SettingsStore.GetString("DeviceId", string.Empty);
            if (string.IsNullOrEmpty(id))
            {
                id = Guid.NewGuid().ToString();
                SettingsStore.Set("DeviceId", id);
                SettingsStore.Save();
            }
            return id;
        }

        private void WireServerPushEvents()
        {
            _serverPushService.ConnectionStateChanged += (s, state) =>
            {
                BeginInvoke(new Action(() =>
                {
                    switch (state)
                    {
                        case "connected":
                            _lblConnState.Text      = "● 已连接";
                            _lblConnState.ForeColor = Color.LimeGreen;
                            _lblConnDetail.Text     = "WebSocket 已就绪，报警推送正常";
                            _lblConnDetail.ForeColor = Color.FromArgb(150, 255, 150);
                            _btnRetry.Enabled = true;
                            // 连接服务器成功 → 强制关闭本地铃声（由远程统一管控）
                            if (_alertService.IsAlarming)
                            {
                                _alertService.StopAlarm();
                                _log.Info("[Server] 已连接服务器，本地铃声已自动关闭。");
                            }
                            break;
                        case "connecting":
                            _lblConnState.Text      = "◌ 连接中…";
                            _lblConnState.ForeColor = Color.Goldenrod;
                            _lblConnDetail.Text     = "正在连接服务器，请稍候…";
                            _lblConnDetail.ForeColor = Color.Goldenrod;
                            _btnRetry.Enabled = false;
                            break;
                        default:  // disconnected
                            _lblConnState.Text      = "● 未连接";
                            _lblConnState.ForeColor = Color.Gray;
                            _lblConnDetail.Text     = "连接断开，将自动重连  ·  点击「手动重试」立即重连";
                            _lblConnDetail.ForeColor = Color.DimGray;
                            _btnRetry.Enabled = true;
                            break;
                    }
                }));
            };

            _serverPushService.CommandReceived += (s, cmd) =>
            {
                BeginInvoke(new Action(() =>
                {
                    switch (cmd)
                    {
                        case "pause":
                            StopMonitor(remote: true);
                            break;

                        case "resume":
                            StartMonitor(remote: true);
                            break;

                        case "stop-alarm":
                            if (!_alertService.IsAlarming)
                            {
                                _serverPushService.SendCommandAck(cmd, false, "当前无报警");
                            }
                            else
                            {
                                _alertService.StopAlarm();
                                _serverPushService.SendCommandAck(cmd, true);
                                _log.Info("[Server] 收到命令：停止报警。");
                            }
                            break;
                    }
                }));
            };

            _serverPushService.SetConfigReceived += (s, kv) =>
            {
                BeginInvoke(new Action(() => ApplyRemoteConfig(kv.Key, kv.Value)));
            };
        }


        /// <summary>
        /// 应用 Android 端下发的参数调整命令（set-config）。
        /// 支持的 key：cooldown / confidence / targets
        /// </summary>
        private void ApplyRemoteConfig(string key, string value)
        {
            switch (key)
            {
                case "cooldown":
                    if (int.TryParse(value, out int cd) && cd >= 1 && cd <= 300)
                    {
                        _txtCooldown.Text = cd.ToString();
                        // 如果正在监控，实时更新 MonitorService 的配置
                        if (_monitorService.IsStarted)
                            _monitorService.UpdateConfig(BuildConfig());
                        _serverPushService.SendCommandAck("set-config:cooldown", true);
                        _log.Info($"[Server] 远程调整冷却时间 → {cd}s");
                        SaveSettings();
                    }
                    else
                    {
                        _serverPushService.SendCommandAck("set-config:cooldown", false, "值无效（1-300）");
                    }
                    break;

                case "confidence":
                    if (float.TryParse(value,
                        System.Globalization.NumberStyles.Float,
                        System.Globalization.CultureInfo.InvariantCulture,
                        out float conf) && conf >= 0.1f && conf <= 0.99f)
                    {
                        int pct = (int)(conf * 100);
                        _trkThreshold.Value = Math.Max(_trkThreshold.Minimum,
                                              Math.Min(_trkThreshold.Maximum, pct));
                        if (_monitorService.IsStarted)
                            _monitorService.UpdateConfig(BuildConfig());
                        _serverPushService.SendCommandAck("set-config:confidence", true);
                        _log.Info($"[Server] 远程调整置信度 → {pct}%");
                        SaveSettings();
                    }
                    else
                    {
                        _serverPushService.SendCommandAck("set-config:confidence", false, "值无效（0.1-0.99）");
                    }
                    break;

                case "targets":
                    // value 为逗号分隔的类名，空字符串 = 全部
                    var classes = new System.Collections.Generic.HashSet<string>(
                        StringComparer.OrdinalIgnoreCase);
                    if (!string.IsNullOrWhiteSpace(value))
                        foreach (var cls in value.Split(','))
                        {
                            string trimmed = cls.Trim();
                            if (!string.IsNullOrEmpty(trimmed))
                                classes.Add(trimmed);
                        }
                    _classPicker.SetSelection(classes);
                    if (_monitorService.IsStarted)
                        _monitorService.UpdateConfig(BuildConfig());
                    _serverPushService.SendCommandAck("set-config:targets", true);
                    _log.Info($"[Server] 远程调整监控目标 → {(classes.Count == 0 ? "全部" : string.Join(",", classes))}");
                    SaveSettings();
                    break;

                default:
                    _serverPushService.SendCommandAck($"set-config:{key}", false, $"未知配置项: {key}");
                    break;
            }
        }

        // ════════════════════════════════════════════════════════════
        // 控件状态
        // ════════════════════════════════════════════════════════════

        private void UpdateControlState(bool started)
        {
            _btnStart.Enabled        = !started;
            _btnStop.Enabled         =  started;
            _btnSelectRegion.Enabled = !started;
            _btnPickWindow.Enabled   = !started;
            _txtFps.Enabled     = _txtThreads.Enabled = _txtCooldown.Enabled = !started;
            _trkThreshold.Enabled = !started;
            _classPicker.Enabled  = !started;

            _tsStatus.Text      = started ? "● 监控中" : "○ 已停止";
            _tsStatus.ForeColor = started ? Color.LimeGreen : Color.Gray;
        }

        private void UpdateRegionLabel()
        {
            if (_targetWindow != null)
            {
                string sub = _windowSubRegion != Rectangle.Empty
                    ? $"  子区域 {_windowSubRegion.Width}×{_windowSubRegion.Height}"
                    : "  全窗口";
                _lblRegionInfo.Text = $"[{_targetWindow.Title}]{sub}";
            }
            else
            {
                _lblRegionInfo.Text = _screenRegion == Rectangle.Empty
                    ? "未选择区域"
                    : $"X:{_screenRegion.X}  Y:{_screenRegion.Y}  {_screenRegion.Width}×{_screenRegion.Height}";
            }
        }

        // ════════════════════════════════════════════════════════════
        // 菜单切换
        // ════════════════════════════════════════════════════════════

        private void ShowPage(Panel page, MenuButton activeMenu)
        {
            _pageCapture.Visible = (page == _pageCapture);
            _pageParams.Visible  = (page == _pageParams);
            _pageTargets.Visible = (page == _pageTargets);
            _pageServer.Visible  = (page == _pageServer);

            foreach (var btn in _allMenuButtons)
                btn.IsSelected = (btn == activeMenu);
        }

        // ════════════════════════════════════════════════════════════
        // 托盘 / 关闭
        // ════════════════════════════════════════════════════════════

        private void SetupTrayIcon()
        {
            var trayIcon = Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? SystemIcons.Shield;
            _notifyIcon = new NotifyIcon
            {
                Text    = "VisionGuard",
                Icon    = trayIcon,
                Visible = true
            };
            var menu = new ContextMenu(new[]
            {
                new MenuItem("显示主窗口", (s, ev) => { Show(); WindowState = FormWindowState.Normal; Activate(); }),
                new MenuItem("退出",        (s, ev) => Application.Exit())
            });
            _notifyIcon.ContextMenu = menu;
            _notifyIcon.DoubleClick += (s, ev) => { Show(); WindowState = FormWindowState.Normal; Activate(); };

            // 最小化时隐藏到托盘，不在任务栏占位
            Resize += (s, ev) =>
            {
                if (WindowState == FormWindowState.Minimized)
                    Hide();
            };
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            // 用户点击 × 时隐藏到托盘，不退出；托盘菜单"退出"才真正关闭
            if (e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
                return;
            }

            SaveSettings();
            _heartbeatTimer?.Stop();
            _heartbeatTimer?.Dispose();
            _serverPushService?.Dispose();
            _alertService?.StopAlarm();
            _monitorService?.Stop();
            _monitorService?.Dispose();
            _notifyIcon?.Dispose();
            base.OnFormClosing(e);
        }

        // ════════════════════════════════════════════════════════════
        // 异常诊断
        // ════════════════════════════════════════════════════════════

        private static string BuildExceptionMessage(Exception ex)
        {
            var sb = new System.Text.StringBuilder();
            Exception cur = ex;
            int depth = 0;
            while (cur != null && depth < 6)
            {
                if (depth > 0) sb.AppendLine("\n─── InnerException ───");
                sb.AppendLine(cur.GetType().Name + ": " + cur.Message);
                cur = cur.InnerException;
                depth++;
            }
            return sb.ToString();
        }

        // ════════════════════════════════════════════════════════════
        // 构建 UI — 固定 960×640，三区布局
        // ════════════════════════════════════════════════════════════

        private void BuildUI()
        {
            Text            = "VisionGuard — 人员检测监控";
            Size            = new Size(960, 640);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox     = false;
            StartPosition   = FormStartPosition.CenterScreen;
            ShowInTaskbar   = false;   // 始终不显示任务栏按钮，靠托盘图标操作
            BackColor       = Color.FromArgb(25, 25, 25);
            ForeColor       = Color.LightGray;
            Font = new Font("Segoe UI", 9f, FontStyle.Regular, GraphicsUnit.Point);

            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            SuspendLayout();

            // ── 状态栏 ───────────────────────────────────────────────
            var strip = new StatusStrip { BackColor = Color.FromArgb(37, 37, 37) };
            strip.Renderer = new DarkStatusRenderer();
            strip.SizingGrip = false; // 固定窗口不需要 Grip
            _tsStatus    = new ToolStripStatusLabel("○ 已停止") { ForeColor = Color.Gray };
            _tsLastAlert = new ToolStripStatusLabel("最后报警：—") { Spring = true };
            _tsInferMs   = new ToolStripStatusLabel("推理 — ms") { Alignment = ToolStripItemAlignment.Right };
            strip.Items.AddRange(new ToolStripItem[] { _tsStatus, _tsLastAlert, _tsInferMs });

            // ── 左侧菜单面板 ─────────────────────────────────────────
            _menuPanel = new Panel
            {
                Dock      = DockStyle.Left,
                Width     = 72,
                BackColor = Color.FromArgb(30, 30, 30)
            };

            // 加载菜单图标
            var assetPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Assets");
            var imgCapture  = Image.FromFile(Path.Combine(assetPath, "capture.png"));
            var imgParams   = Image.FromFile(Path.Combine(assetPath, "settings.png"));
            var imgTargets  = Image.FromFile(Path.Combine(assetPath, "target.png"));
            var imgServer   = Image.FromFile(Path.Combine(assetPath, "server.png"));

            _menuCapture = new MenuButton { Text = "捕获", IconImage = imgCapture, Dock = DockStyle.Top };
            _menuParams  = new MenuButton { Text = "参数", IconImage = imgParams,  Dock = DockStyle.Top };
            _menuTargets = new MenuButton { Text = "目标", IconImage = imgTargets, Dock = DockStyle.Top };
            _menuServer  = new MenuButton { Text = "服务器", IconImage = imgServer, Dock = DockStyle.Top };
            _allMenuButtons = new[] { _menuCapture, _menuParams, _menuTargets, _menuServer };

            // 注意：Dock.Top 按添加顺序从上到下排列，需要反序添加
            _menuPanel.Controls.Add(_menuServer);
            _menuPanel.Controls.Add(_menuTargets);
            _menuPanel.Controls.Add(_menuParams);
            _menuPanel.Controls.Add(_menuCapture);

            // ── 预览面板 ─────────────────────────────────────────────
            _overlayPanel = new DetectionOverlayPanel { Dock = DockStyle.Fill };

            // ── 日志面板 ─────────────────────────────────────────────
            _lstLog = new OwnerDrawListBox { Dock = DockStyle.Fill };
            _lstLog.Font = new Font("Consolas", Font.SizeInPoints, FontStyle.Regular, GraphicsUnit.Point);
            var logContainer = new Panel { Dock = DockStyle.Fill, MinimumSize = new Size(0, 60) };
            logContainer.Controls.Add(_lstLog);

            // ── 左侧分割：上70%预览 / 下30%日志 ─────────────────────
            _leftSplit = new SplitContainer
            {
                Dock          = DockStyle.Fill,
                Orientation   = Orientation.Horizontal,
                Panel1MinSize = 100,
                Panel2MinSize = 60,
                BackColor     = Color.FromArgb(25, 25, 25)
            };
            _leftSplit.Panel1.Controls.Add(_overlayPanel);
            _leftSplit.Panel2.Controls.Add(logContainer);

            // ── 页面容器（4个页面，Dock.Fill 叠加，切换 Visible）───
            _pageCapture = new Panel { Dock = DockStyle.Fill, BackColor = Color.FromArgb(32, 32, 32), Visible = true };
            _pageParams  = new Panel { Dock = DockStyle.Fill, BackColor = Color.FromArgb(32, 32, 32), Visible = false };
            _pageTargets = new Panel { Dock = DockStyle.Fill, BackColor = Color.FromArgb(32, 32, 32), Visible = false };
            _pageServer  = new Panel { Dock = DockStyle.Fill, BackColor = Color.FromArgb(32, 32, 32), Visible = false };

            _pageContainer = new Panel { Dock = DockStyle.Fill, BackColor = Color.FromArgb(32, 32, 32) };
            _pageContainer.Controls.Add(_pageCapture);
            _pageContainer.Controls.Add(_pageParams);
            _pageContainer.Controls.Add(_pageTargets);
            _pageContainer.Controls.Add(_pageServer);

            // ── 内容区：左边预览+日志 + 右边页面 ─────────────────────
            var contentLayout = new TableLayoutPanel
            {
                Dock        = DockStyle.Fill,
                ColumnCount = 2,
                RowCount    = 1
            };
            contentLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 58F));
            contentLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 42F));
            contentLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            contentLayout.Controls.Add(_leftSplit,     0, 0);
            contentLayout.Controls.Add(_pageContainer, 1, 0);

            // ── 组装 ─────────────────────────────────────────────────
            Controls.Add(contentLayout);
            Controls.Add(_menuPanel);
            Controls.Add(strip);

            ResumeLayout(false);
        }

        // ════════════════════════════════════════════════════════════
        // 页面1：捕获区域 + 开始/停止
        // ════════════════════════════════════════════════════════════

        private void BuildCapturePage()
        {
            int fh    = Font.Height;
            int PadX  = 12;
            int RowGap = fh / 3;
            int BtnH  = fh + 12;
            int y     = 12;

            // 标题
            _pageCapture.Controls.Add(MakeTitle("捕获区域", PadX, ref y, fh));

            // 区域信息
            _lblRegionInfo = new Label
            {
                Text = "未选择区域", Left = PadX, Top = y,
                Width = _pageCapture.ClientSize.Width - PadX * 2,
                Height = fh + 4, ForeColor = Color.Gray, AutoSize = false,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top
            };
            _pageCapture.Controls.Add(_lblRegionInfo);
            y += fh + 4 + RowGap;

            // 按钮
            _btnPickWindow   = MakePageBtn(_pageCapture, "选择窗口…", Color.FromArgb(45, 60, 80),  Color.FromArgb(58, 78, 105),  PadX, BtnH, ref y);
            y += RowGap;
            _btnSelectRegion = MakePageBtn(_pageCapture, "拖拽选区…", Color.FromArgb(45, 80, 45),  Color.FromArgb(58, 100, 58), PadX, BtnH, ref y);

            // 分隔线
            y += fh;
            _pageCapture.Controls.Add(MakeTitle("监控控制", PadX, ref y, fh));

            // 开始 / 停止
            _btnStart = MakePageBtn(_pageCapture, "▶  开  始",
                Color.FromArgb(0, 120, 212), Color.FromArgb(16, 110, 190), PadX, BtnH + 4, ref y);
            _btnStart.PressColor = Color.FromArgb(0, 90, 170);
            y += RowGap;
            _btnStop = MakePageBtn(_pageCapture, "■  停  止",
                Color.FromArgb(58, 58, 58), Color.FromArgb(72, 72, 72), PadX, BtnH + 4, ref y);
            _btnStop.Enabled = false;
        }

        // ════════════════════════════════════════════════════════════
        // 页面2：检测参数
        // ════════════════════════════════════════════════════════════

        private void BuildParamsPage()
        {
            int fh    = Font.Height;
            int PadX  = 12;
            int RowGap = fh / 3;
            int RowH  = fh + 12;
            int y     = 12;

            // 置信度阈值
            _pageParams.Controls.Add(MakeTitle("置信度阈值", PadX, ref y, fh));

            int sliderH = fh + 8;
            _trkThreshold = new DarkSlider
            {
                Left = PadX, Top = y, Height = sliderH,
                Width = _pageParams.ClientSize.Width - PadX * 2,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
                Minimum = 10, Maximum = 90, Value = 45
            };
            _pageParams.Controls.Add(_trkThreshold);
            y += sliderH + RowGap;

            _lblThreshold = new Label
            {
                Text = "0.45", Left = PadX, Top = y, Height = fh + 2,
                Width = _pageParams.ClientSize.Width - PadX * 2,
                ForeColor = Color.LightGray, AutoSize = false,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top
            };
            _pageParams.Controls.Add(_lblThreshold);
            y += fh + 2 + RowGap + fh / 2;

            // 冷却时间
            _pageParams.Controls.Add(MakeTitle("性能与报警", PadX, ref y, fh));

            _txtCooldown = AddParamRow(_pageParams, "冷却(s)：", 1, 300, 5, PadX, RowH, ref y);
            y += RowGap;
            _txtFps     = AddParamRow(_pageParams, "FPS：",    1, 5, 2, PadX, RowH, ref y);
            y += RowGap;
            _txtThreads = AddParamRow(_pageParams, "线程数：", 1, 8, 2, PadX, RowH, ref y);
        }

        // ════════════════════════════════════════════════════════════
        // 页面3：监控对象
        // ════════════════════════════════════════════════════════════

        private void BuildTargetsPage()
        {
            int PadX = 12;
            int y    = 12;

            _pageTargets.Controls.Add(MakeTitle("监控对象", PadX, ref y, Font.Height));

            _classPicker = new CocoClassPickerControl
            {
                Left = PadX, Top = y,
                Width  = _pageTargets.ClientSize.Width - PadX * 2,
                Height = _pageTargets.ClientSize.Height - y - 8,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top | AnchorStyles.Bottom
            };
            _pageTargets.Controls.Add(_classPicker);
        }

        // ════════════════════════════════════════════════════════════
        // 页面4：服务器推送
        // ════════════════════════════════════════════════════════════

        private void BuildServerPage()
        {
            const int PadX = 12;
            int fh = Font.Height;
            int y  = 12;

            // ── 服务器连接 ──────────────────────────────────────────────
            _pageServer.Controls.Add(MakeTitle("服务器连接", PadX, ref y, fh));

            // 状态指示 — 左
            _lblConnState = new Label
            {
                Text      = "● 未连接",
                Left      = PadX,
                Top       = y + 2,          // 光学对齐按钮中心
                AutoSize  = true,
                ForeColor = Color.Gray,
                Font      = new Font(Font, FontStyle.Bold),
            };
            _pageServer.Controls.Add(_lblConnState);

            // 重试按钮 — 右，同行
            _btnRetry = new FlatRoundButton
            {
                Text   = "重试连接",
                Left   = _pageServer.ClientSize.Width - PadX - 100,
                Top    = y,
                Width  = 100,
                Height = 28,
            };
            _pageServer.Controls.Add(_btnRetry);
            y += 44;   // 28px 按钮 + 16px 间距

            // 分割线
            _pageServer.Controls.Add(new Label
            {
                Text      = "",
                Left      = PadX,
                Top       = y,
                Width     = _pageServer.ClientSize.Width - PadX * 2,
                Height    = 1,
                BackColor = Color.FromArgb(60, 60, 60),
            });
            y += 17;   // 1px 线 + 16px 间距

            // ── 设备名称 ────────────────────────────────────────────────
            _pageServer.Controls.Add(MakeTitle("设备名称", PadX, ref y, fh));

            

            _txtDeviceName = new TextBox
            {
                Left        = PadX,
                Top         = y,
                Width       = 180,
                Height      = 22,
                Text        = Environment.MachineName,
                BackColor   = Color.FromArgb(50, 50, 50),
                ForeColor   = Color.White,
                BorderStyle = BorderStyle.FixedSingle,
            };
            _pageServer.Controls.Add(_txtDeviceName);

            var btnApplyName = new FlatRoundButton
            {
                Text   = "应用",
                Left   = PadX + 180 + 8,
                Top    = y ,
                Width  = 72,
                Height = 26,
            };
            _pageServer.Controls.Add(btnApplyName);

            // ── 隐藏字段（WireServerPushEvents 仍赋值，不可删）──────────
            _lblConnDetail = new Label { Visible = false };
            _pageServer.Controls.Add(_lblConnDetail);

            // ── 按钮事件 ────────────────────────────────────────────────
            _btnRetry.Click += (s, e) =>
            {
                string name     = _txtDeviceName.Text.Trim();
                string deviceId = EnsureDeviceId();
                _serverPushService.Disconnect();
                _serverPushService.Configure(ServerUrl, ServerApiKey, deviceId, name);
                _log.Info("[Server] 手动重试连接…");
            };

            btnApplyName.Click += (s, e) =>
            {
                string name = _txtDeviceName.Text.Trim();
                if (string.IsNullOrEmpty(name)) { _log.Warn("设备名不能为空。"); return; }
                SettingsStore.Set("DeviceName", name);
                SettingsStore.Save();
                string deviceId = EnsureDeviceId();
                _serverPushService.Disconnect();
                _serverPushService.Configure(ServerUrl, ServerApiKey, deviceId, name);
                _log.Info($"[Server] 设备名已更新为「{name}」，重新连接中…");
            };
        }

        // ════════════════════════════════════════════════════════════
        // 事件绑定
        // ════════════════════════════════════════════════════════════

        private void WireEvents()
        {
            // 菜单切换
            _menuCapture.Click += (s, e) => ShowPage(_pageCapture, _menuCapture);
            _menuParams.Click  += (s, e) => ShowPage(_pageParams,  _menuParams);
            _menuTargets.Click += (s, e) => ShowPage(_pageTargets, _menuTargets);
            _menuServer.Click  += (s, e) => ShowPage(_pageServer,  _menuServer);

            // 捕获页按钮
            _btnSelectRegion.Click += BtnSelectRegion_Click;
            _btnPickWindow.Click   += BtnPickWindow_Click;
            _btnStart.Click        += BtnStart_Click;
            _btnStop.Click         += BtnStop_Click;

            // 参数页事件
            _trkThreshold.ValueChanged += (s, e) =>
                _lblThreshold.Text = (_trkThreshold.Value / 100f).ToString("F2");

            // 目标页事件
            _classPicker.SelectionChanged += (s, e) => { /* 可在此实时更新状态显示 */ };

            // 分割比例
            Shown  += (s, e) => ApplySplitterRatio();
        }

        // ════════════════════════════════════════════════════════════
        // 辅助方法
        // ════════════════════════════════════════════════════════════

        /// <summary>
        /// 捕获选区是否已设定（不依赖 MonitorService.IsReady，
        /// MonitorService 启动前 _config 为 null 会导致 IsReady 始终 false）。
        /// </summary>
        private bool IsRegionReady
        {
            get
            {
                if (_targetWindow != null) return true;                          // 窗口捕获模式
                return _screenRegion.Width >= 32 && _screenRegion.Height >= 32; // 屏幕区域模式
            }
        }

        private void ApplySplitterRatio()
        {
            if (_leftSplit == null) return;
            try
            {
                int h = _leftSplit.Height;
                int target = (int)(h * 0.60);
                target = Math.Max(_leftSplit.Panel1MinSize,
                         Math.Min(h - _leftSplit.Panel2MinSize, target));
                _leftSplit.SplitterDistance = target;
            }
            catch { /* 尺寸不合法时静默忽略 */ }
        }

        /// <summary>在页面中添加一个分类标题</summary>
        private Label MakeTitle(string text, int padX, ref int y, int fh)
        {
            var lbl = new Label
            {
                Text = text, Left = padX, Top = y,
                Height = fh + 4, ForeColor = Color.FromArgb(200, 200, 200),
                AutoSize = false, Font = new Font(Font, FontStyle.Bold),
                Anchor = AnchorStyles.Left | AnchorStyles.Top
            };
            y += fh + 4 + fh / 3;
            return lbl;
        }

        /// <summary>在页面中添加一个全宽按钮</summary>
        private FlatRoundButton MakePageBtn(Panel page, string text, Color normal, Color hover, int padX, int btnH, ref int y)
        {
            var btn = new FlatRoundButton
            {
                Text = text, Left = padX, Top = y, Height = btnH,
                Width = page.ClientSize.Width - padX * 2,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
                NormalColor = normal, HoverColor = hover, ForeColor = Color.White
            };
            page.Controls.Add(btn);
            y += btnH;
            return btn;
        }

        /// <summary>在参数页添加 Label + TextBox 行</summary>
        private TextBox AddParamRow(Panel page, string lbl, int min, int max, int def, int padX, int rowH, ref int y)
        {
            int lblW = padX + 76;
            page.Controls.Add(new Label
            {
                Text = lbl, Left = padX, Top = y + 3, Width = 74, Height = Font.Height + 4,
                Anchor = AnchorStyles.Left | AnchorStyles.Top, ForeColor = Color.LightGray
            });
            var tb = new TextBox
            {
                Left = lblW, Top = y, Height = rowH,
                Width = page.ClientSize.Width - lblW - padX,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
                BackColor = Color.FromArgb(50, 50, 50), ForeColor = Color.White,
                BorderStyle = BorderStyle.FixedSingle, Text = def.ToString()
            };
            WireIntTextBox(tb, min, max, def);
            page.Controls.Add(tb);
            y += rowH;
            return tb;
        }

        /// <summary>注册 TextBox 的失焦整数验证（超范围 Clamp，非法恢复默认）。</summary>
        private static void WireIntTextBox(TextBox tb, int min, int max, int def)
        {
            tb.Leave += (s, e) =>
            {
                if (tb.IsDisposed) return;
                if (int.TryParse(tb.Text, out int v))
                    tb.Text = Math.Max(min, Math.Min(max, v)).ToString();
                else
                    tb.Text = def.ToString();
            };
        }

        /// <summary>安全解析 TextBox 值（用于 BuildConfig）。</summary>
        private static int ParseInt(string text, int min, int max, int def)
        {
            if (int.TryParse(text, out int v))
                return Math.Max(min, Math.Min(max, v));
            return def;
        }
    }
}
