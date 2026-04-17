// Form1.UI.cs — UI 构建：主布局、各页面、事件绑定、辅助方法
using System;
using System.Drawing;
using System.IO;
using System.Windows.Forms;
using VisionGuard.Services;
using VisionGuard.UI;
using VisionGuard.Utils;

namespace VisionGuard
{
    public partial class Form1
    {
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

            // ── 内容区：左边预览 + 右边页面 ───────────────────────
            var contentLayout = new TableLayoutPanel
            {
                Dock        = DockStyle.Fill,
                ColumnCount = 2,
                RowCount    = 1
            };
            contentLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 58F));
            contentLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 42F));
            contentLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            contentLayout.Controls.Add(_overlayPanel,  0, 0);
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
        }

        // ════════════════════════════════════════════════════════════
        // UI 辅助方法
        // ════════════════════════════════════════════════════════════

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
    }
}
