// ┌─────────────────────────────────────────────────────────┐
// │ Form1.cs — 核心：字段、构造、生命周期、配置、状态控制   │
// │ 拆分文件：                                             │
// │   Form1.Monitor.cs  — 监控控制：区域选择、启停、回调   │
// │   Form1.Server.cs   — 设置持久化、服务器推送、远程配置 │
// │   Form1.UI.cs       — UI 构建：主布局、页面、辅助方法  │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Drawing;
using System.IO;
using System.Threading.Tasks;
using System.Windows.Forms;
using VisionGuard.Capture;
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

        // ── 预览 ──────────────────────────────────────────────────
        private DetectionOverlayPanel _overlayPanel;

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
        private ComboBox        _cmbModel;

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

        // ── 模型选择 ────────────────────────────────────────────────
        private string _selectedModel = "yolo26n"; // "yolo26n" | "yolo26s"

        private string ModelPath => Path.Combine(
            AppDomain.CurrentDomain.BaseDirectory, "Assets", $"{_selectedModel}.onnx");

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
            _log            = new LogManager();
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

            // 启动时同步 NTP 时钟
            Task.Run(async () =>
            {
                await Utils.NtpSync.SyncAsync();
            });

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

            _overlayPanel?.Invalidate();
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
        // 辅助方法
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

        /// <summary>安全解析 TextBox 值（用于 BuildConfig）。</summary>
        private static int ParseInt(string text, int min, int max, int def)
        {
            if (int.TryParse(text, out int v))
                return Math.Max(min, Math.Min(max, v));
            return def;
        }
    }
}
