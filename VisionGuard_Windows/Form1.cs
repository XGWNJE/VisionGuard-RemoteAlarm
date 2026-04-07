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
        private CheckBox        _chkPlaySound;
        private TextBox         _txtSoundPath;
        private FlatRoundButton _btnPickSound;

        // ── 目标页 控件 ─────────────────────────────────────────────
        private CocoClassPickerControl _classPicker;

        // ── 服务器页 控件（Phase 2）──────────────────────────────────
        private TextBox  _txtServerUrl;
        private TextBox  _txtApiKey;
        private TextBox  _txtDeviceName;
        private Label    _lblConnState;

        // ── ServerPushService + 心跳定时器 ───────────────────────────
        private ServerPushService _serverPushService;
        private System.Windows.Forms.Timer _heartbeatTimer;

        // ── 状态栏 ──────────────────────────────────────────────────
        private ToolStripStatusLabel _tsStatus, _tsLastAlert, _tsInferMs;

        // ── 系统托盘 / 键钩 ──────────────────────────────────────────
        private NotifyIcon    _notifyIcon;
        private GlobalKeyHook _keyHook;

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
            _alertService.AlarmStarted     += OnAlarmStarted;
            _alertService.AlarmStopped     += OnAlarmStopped;
            _monitorService.FrameProcessed += OnFrameProcessed;

            SetupTrayIcon();
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);

            var ver = System.Reflection.Assembly.GetExecutingAssembly().GetName().Version;
            this.Text = $"VisionGuard v{ver.Major}.{ver.Minor}";

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

        private void BtnStart_Click(object sender, EventArgs e)
        {
            if (!File.Exists(ModelPath))
            {
                MessageBox.Show(
                    $"找不到模型文件：\n{ModelPath}\n\n请参阅 Assets/ASSETS_README.md。",
                    "模型缺失", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }

            MonitorConfig cfg = BuildConfig();

            // 验证有效捕获源
            if (cfg.CaptureMode == CaptureMode.ScreenRegion)
            {
                if (cfg.CaptureRegion.Width < 32 || cfg.CaptureRegion.Height < 32)
                {
                    MessageBox.Show("捕获区域太小（最小 32×32），请重新选择。",
                        "区域无效", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }
            }
            else // WindowHandle
            {
                if (cfg.TargetWindowHandle == IntPtr.Zero)
                {
                    MessageBox.Show("请先点击「选择窗口…」选择目标窗口。",
                        "未选择目标窗口", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }
            }

            try
            {
                _monitorService.Start(ModelPath, cfg);
                _keyHook = new GlobalKeyHook();
                _keyHook.KeyDown += OnGlobalKeyDown;
                UpdateControlState(started: true);
                string src = cfg.CaptureMode == CaptureMode.WindowHandle
                    ? $"窗口「{cfg.TargetWindowTitle}」"
                    : $"区域 {cfg.CaptureRegion}";
                _log.Info($"监控已启动 | {src} | {cfg.TargetFps} FPS | 阈值 {cfg.ConfidenceThreshold:P0}");

                // 启动心跳定时器（30秒）
                if (_heartbeatTimer == null)
                {
                    _heartbeatTimer = new System.Windows.Forms.Timer { Interval = 30000 };
                    _heartbeatTimer.Tick += (s, ev) =>
                        _serverPushService.SendHeartbeat(
                            isMonitoring: _monitorService.IsStarted,
                            isAlarming:   _alertService.IsAlarming);
                }
                _heartbeatTimer.Start();
            }
            catch (Exception ex)
            {
                string fullMsg = BuildExceptionMessage(ex);
                _log.Error("启动失败：" + fullMsg);
                MessageBox.Show(fullMsg, "启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        // ── 停止监控 ─────────────────────────────────────────────────

        private void BtnStop_Click(object sender, EventArgs e)
        {
            _alertService.StopAlarm();
            _keyHook?.Dispose();
            _keyHook = null;
            _monitorService.Stop();
            _heartbeatTimer?.Stop();
            _serverPushService.SendHeartbeat(isMonitoring: false, isAlarming: false);
            UpdateControlState(started: false);
            _log.Info("监控已停止。");
        }

        private void OnGlobalKeyDown(Keys key)
        {
            if (key == Keys.Space && _alertService.IsAlarming)
            {
                _alertService.StopAlarm();
                _log.Info("用户按 Space 键，铃声已停止，推理恢复。");
            }
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

        private void OnAlarmStarted(object sender, EventArgs e)
        {
            _monitorService.Pause();
            BeginInvoke(new Action(() =>
            {
                _tsStatus.Text      = "⚠ 报警中 — 按 Space 停止";
                _tsStatus.ForeColor = Color.OrangeRed;
            }));
        }

        private void OnAlarmStopped(object sender, EventArgs e)
        {
            _monitorService.Resume();
            BeginInvoke(new Action(() =>
            {
                _tsStatus.Text      = "● 监控中";
                _tsStatus.ForeColor = Color.LimeGreen;
            }));
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
                PlayAlertSound       = _chkPlaySound.Checked,
                AlertSoundPath       = _txtSoundPath.Text == "默认系统音" ? string.Empty : _txtSoundPath.Text
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
            _chkPlaySound.Checked = SettingsStore.GetBool("PlayAlertSound", true);

            string soundPath = SettingsStore.GetString("AlertSoundPath", string.Empty);
            if (!string.IsNullOrEmpty(soundPath) && File.Exists(soundPath))
            {
                _txtSoundPath.Text      = soundPath;
                _txtSoundPath.ForeColor = Color.White;
            }

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

            // 服务器页：从设置恢复
            _txtServerUrl.Text  = SettingsStore.GetString("ServerUrl",  string.Empty);
            _txtApiKey.Text     = SettingsStore.GetString("ApiKey",     string.Empty);
            _txtDeviceName.Text = SettingsStore.GetString("DeviceName", Environment.MachineName);

            // 如果上次保存了服务器地址，则自动重连
            string savedUrl = _txtServerUrl.Text.Trim();
            if (!string.IsNullOrEmpty(savedUrl))
            {
                string deviceId = EnsureDeviceId();
                WireServerPushEvents();
                _serverPushService.Configure(
                    savedUrl,
                    _txtApiKey.Text.Trim(),
                    deviceId,
                    _txtDeviceName.Text.Trim());
                _log.Info($"[Server] 自动连接 {savedUrl}…");
            }
        }

        private void SaveSettings()
        {
            SettingsStore.Set("ConfidenceThresholdPct",  _trkThreshold.Value);
            SettingsStore.Set("TargetFps",               ParseInt(_txtFps.Text,      1,  5, 2));
            SettingsStore.Set("IntraOpNumThreads",        ParseInt(_txtThreads.Text,  1,  8, 2));
            SettingsStore.Set("AlertCooldownSeconds",     ParseInt(_txtCooldown.Text, 1, 300, 5));
            SettingsStore.Set("PlayAlertSound",           _chkPlaySound.Checked);
            SettingsStore.Set("AlertSoundPath",
                _txtSoundPath.Text == "默认系统音" ? string.Empty : _txtSoundPath.Text);

            SettingsStore.Set("WatchedClasses",
                string.Join(",", _classPicker.SelectedClasses));

            // 服务器设置
            SettingsStore.Set("ServerUrl",  _txtServerUrl.Text.Trim());
            SettingsStore.Set("ApiKey",     _txtApiKey.Text.Trim());
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

        private void SaveServerSettings()
        {
            SettingsStore.Set("ServerUrl",  _txtServerUrl.Text.Trim());
            SettingsStore.Set("ApiKey",     _txtApiKey.Text.Trim());
            SettingsStore.Set("DeviceName", _txtDeviceName.Text.Trim());
            SettingsStore.Save();
        }

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
                            break;
                        case "connecting":
                            _lblConnState.Text      = "◌ 连接中…";
                            _lblConnState.ForeColor = Color.Goldenrod;
                            break;
                        default:
                            _lblConnState.Text      = "● 未连接";
                            _lblConnState.ForeColor = Color.Gray;
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
                            _monitorService.Pause();
                            _log.Info("[Server] 收到命令：暂停监控。");
                            break;
                        case "resume":
                            _monitorService.Resume();
                            _log.Info("[Server] 收到命令：恢复监控。");
                            break;
                        case "stop-alarm":
                            _alertService.StopAlarm();
                            _log.Info("[Server] 收到命令：停止报警。");
                            break;
                    }
                }));
            };
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
            _chkPlaySound.Enabled = !started;
            _txtSoundPath.Enabled = !started && _chkPlaySound.Checked;
            _btnPickSound.Enabled = !started && _chkPlaySound.Checked;
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
                new MenuItem("显示主窗口", (s, ev) => { Show(); WindowState = FormWindowState.Normal; }),
                new MenuItem("退出",        (s, ev) => Application.Exit())
            });
            _notifyIcon.ContextMenu = menu;
            _notifyIcon.DoubleClick += (s, ev) => { Show(); WindowState = FormWindowState.Normal; };
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            SaveSettings();
            _heartbeatTimer?.Stop();
            _heartbeatTimer?.Dispose();
            _serverPushService?.Dispose();
            _alertService?.StopAlarm();
            _keyHook?.Dispose();
            _keyHook = null;
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

            _menuCapture = new MenuButton { Text = "捕获", IconText = "\U0001F4F7", Dock = DockStyle.Top };
            _menuParams  = new MenuButton { Text = "参数", IconText = "\u2699",     Dock = DockStyle.Top };
            _menuTargets = new MenuButton { Text = "目标", IconText = "\U0001F3AF", Dock = DockStyle.Top };
            _menuServer  = new MenuButton { Text = "服务器", IconText = "\U0001F310", Dock = DockStyle.Top };
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
            contentLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 52F));
            contentLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 48F));
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

            // 铃声设置
            y += fh;
            _pageParams.Controls.Add(MakeTitle("铃声设置", PadX, ref y, fh));

            int chkH = fh + 6;
            _chkPlaySound = new CheckBox
            {
                Text = "警报铃声", Left = PadX, Top = y, Height = chkH,
                Width = _pageParams.ClientSize.Width - PadX * 2,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
                ForeColor = Color.LightGray, Checked = true
            };
            _pageParams.Controls.Add(_chkPlaySound);
            y += chkH + RowGap;

            int pickW = fh + 8;
            _txtSoundPath = new TextBox
            {
                Left = PadX, Top = y, Height = RowH,
                Width = _pageParams.ClientSize.Width - PadX * 2 - pickW - 4,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
                BackColor = Color.FromArgb(45, 45, 45), ForeColor = Color.DimGray,
                Text = "默认系统音", ReadOnly = true
            };
            _btnPickSound = new FlatRoundButton
            {
                Text = "…", Top = y, Width = pickW, Height = RowH,
                Left = _pageParams.ClientSize.Width - PadX - pickW,
                Anchor = AnchorStyles.Right | AnchorStyles.Top,
                NormalColor = Color.FromArgb(60, 60, 60), HoverColor = Color.FromArgb(75, 75, 75),
                ForeColor = Color.White
            };
            _pageParams.Controls.Add(_txtSoundPath);
            _pageParams.Controls.Add(_btnPickSound);
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
            const int LblW = 90;
            const int TbW  = 220;
            const int RowH = 22;
            int y = 12;

            _pageServer.Controls.Add(MakeTitle("服务器推送", PadX, ref y, Font.Height));

            // ── 辅助：添加 Label + TextBox 行（不含整数验证）────────
            TextBox MakeRow(string lbl, string defaultText)
            {
                _pageServer.Controls.Add(new Label
                {
                    Text = lbl, Left = PadX, Top = y + 3,
                    Width = LblW - 2, Height = Font.Height + 4,
                    ForeColor = Color.LightGray, AutoSize = false
                });
                var tb = new TextBox
                {
                    Left = PadX + LblW, Top = y,
                    Width = TbW, Height = RowH,
                    Text = defaultText,
                    BackColor = Color.FromArgb(50, 50, 50),
                    ForeColor = Color.White,
                    BorderStyle = BorderStyle.FixedSingle,
                };
                _pageServer.Controls.Add(tb);
                y += RowH + 6;
                return tb;
            }

            _txtServerUrl  = MakeRow("服务器地址：", "");
            _txtApiKey     = MakeRow("API 密钥：",   "");
            _txtDeviceName = MakeRow("设备名称：",   Environment.MachineName);

            y += 4;

            // 连接/断开 按钮
            var btnConnect = new FlatRoundButton
            {
                Text = "连接服务器", Left = PadX, Top = y, Width = 120, Height = 28,
            };
            var btnDisconnect = new FlatRoundButton
            {
                Text = "断开", Left = PadX + 128, Top = y, Width = 80, Height = 28,
            };
            _pageServer.Controls.Add(btnConnect);
            _pageServer.Controls.Add(btnDisconnect);
            y += 36;

            // 连接状态指示
            _lblConnState = new Label
            {
                Text = "● 未连接", Left = PadX, Top = y, AutoSize = true,
                ForeColor = Color.Gray,
            };
            _pageServer.Controls.Add(_lblConnState);
            y += 28;

            // 提示文字
            _pageServer.Controls.Add(new Label
            {
                Text = "提示：配置后点击「连接服务器」，Windows → Debian → Android 三端联通。\n" +
                       "API 密钥须与服务器 .env 中 API_KEY 一致。\n" +
                       "留空服务器地址 = 纯本地模式（不影响现有功能）。",
                Left = PadX, Top = y,
                Width = _pageServer.ClientSize.Width - PadX * 2,
                Height = 72,
                ForeColor = Color.DimGray,
                AutoSize = false,
            });

            // 按钮事件
            btnConnect.Click += (s, e) =>
            {
                SaveServerSettings();
                string url  = _txtServerUrl.Text.Trim();
                string key  = _txtApiKey.Text.Trim();
                string name = _txtDeviceName.Text.Trim();
                if (string.IsNullOrEmpty(url))
                {
                    _log.Warn("未填写服务器地址，已保存设置（纯本地模式）。");
                    return;
                }
                string deviceId = EnsureDeviceId();
                _serverPushService.Dispose();
                _serverPushService = new ServerPushService();
                WireServerPushEvents();
                _serverPushService.Configure(url, key, deviceId, name);
                _log.Info($"[Server] 正在连接 {url}…");
            };

            btnDisconnect.Click += (s, e) =>
            {
                _serverPushService.Disconnect();
                _log.Info("[Server] 已断开连接。");
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

            _chkPlaySound.CheckedChanged += (s, e) =>
            {
                _txtSoundPath.Enabled = _chkPlaySound.Checked;
                _btnPickSound.Enabled = _chkPlaySound.Checked;
            };

            _btnPickSound.Click += (s, e) =>
            {
                using (var dlg = new OpenFileDialog
                {
                    Title  = "选择警报铃声（WAV）",
                    Filter = "WAV 音频|*.wav",
                    CheckFileExists = true
                })
                {
                    if (dlg.ShowDialog() == DialogResult.OK)
                    {
                        _txtSoundPath.Text      = dlg.FileName;
                        _txtSoundPath.ForeColor = Color.White;
                    }
                }
            };

            // 目标页事件
            _classPicker.SelectionChanged += (s, e) => { /* 可在此实时更新状态显示 */ };

            // 分割比例
            Shown  += (s, e) => ApplySplitterRatio();
        }

        // ════════════════════════════════════════════════════════════
        // 辅助方法
        // ════════════════════════════════════════════════════════════

        private void ApplySplitterRatio()
        {
            if (_leftSplit == null) return;
            try
            {
                int h = _leftSplit.Height;
                int target = (int)(h * 0.70);
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
