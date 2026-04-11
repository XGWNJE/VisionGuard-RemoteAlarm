// ┌─────────────────────────────────────────────────────────┐
// │ RegionSelectorForm.cs                                   │
// │ 角色：全屏透明覆盖窗口，拖拽选择捕获区域                │
// │ 模式1：无参构造→全屏遮罩 (ScreenRegion)                 │
// │ 模式2：Bitmap构造→窗口截图上框选子区域 (WindowHandle)   │
// │ 对外 API：SelectedRegion (关闭后读取)                   │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Drawing;
using System.Windows.Forms;

namespace VisionGuard.UI
{
    /// <summary>
    /// 全屏透明覆盖窗口，用于拖拽选择屏幕捕获区域。
    /// 关闭后通过 SelectedRegion 获取结果（Rectangle.Empty 表示取消）。
    /// 支持两种模式：
    ///   1. 无参构造：全屏半透明遮罩（ScreenRegion 模式）。
    ///   2. Bitmap 构造：在指定背景图上框选子区域（WindowHandle 子区域模式）。
    /// </summary>
    public class RegionSelectorForm : Form
    {
        public Rectangle SelectedRegion { get; private set; } = Rectangle.Empty;

        private Point     _startPoint;
        private Rectangle _current;
        private bool      _dragging;

        // 仅 Bitmap 模式使用
        private readonly Bitmap _background;
        private readonly bool   _bitmapMode;

        // ── 构造（全屏模式）─────────────────────────────────────────

        /// <summary>全屏半透明遮罩，ScreenRegion 模式使用。</summary>
        public RegionSelectorForm()
        {
            InitCommon();

            // 全屏：覆盖虚拟桌面（支持多显示器）
            FormBorderStyle = FormBorderStyle.None;
            WindowState     = FormWindowState.Maximized;
            Bounds          = SystemInformation.VirtualScreen;
            TopMost         = true;
            BackColor       = Color.Black;
            Opacity         = 0.35;
            Cursor          = Cursors.Cross;
            ShowInTaskbar   = false;

            _bitmapMode = false;
        }

        // ── 构造（Bitmap 子区域模式）────────────────────────────────

        /// <summary>
        /// 在目标窗口截图上框选子区域（WindowHandle 模式）。
        /// </summary>
        /// <param name="background">目标窗口的截图，Form 尺寸匹配此 Bitmap。</param>
        public RegionSelectorForm(Bitmap background)
        {
            if (background == null) throw new ArgumentNullException(nameof(background));
            _background = background;
            _bitmapMode = true;

            InitCommon();

            FormBorderStyle = FormBorderStyle.None;
            TopMost         = true;
            Cursor          = Cursors.Cross;
            ShowInTaskbar   = false;
            BackColor       = Color.Black;

            // 窗口大小 = Bitmap 大小
            ClientSize      = new Size(background.Width, background.Height);
            StartPosition   = FormStartPosition.CenterScreen;

            // 双缓冲避免闪烁
            DoubleBuffered = true;
        }

        // ── 公共初始化 ───────────────────────────────────────────────

        private void InitCommon()
        {
            KeyPreview = true;
            KeyDown   += (s, e) => { if (e.KeyCode == Keys.Escape) Close(); };
        }

        // ── 鼠标事件 ─────────────────────────────────────────────────

        protected override void OnMouseDown(MouseEventArgs e)
        {
            if (e.Button == MouseButtons.Left)
            {
                _startPoint = e.Location;
                _current    = new Rectangle(e.Location, Size.Empty);
                _dragging   = true;
            }
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            if (!_dragging) return;
            _current = NormalizeRect(_startPoint, e.Location);
            Invalidate();
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            if (!_dragging || e.Button != MouseButtons.Left) return;
            _dragging = false;

            Rectangle r = NormalizeRect(_startPoint, e.Location);
            if (r.Width > 10 && r.Height > 10)
            {
                SelectedRegion = r;
                DialogResult   = DialogResult.OK;
            }
            Close();
        }

        // ── 绘制 ─────────────────────────────────────────────────────

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);

            // Bitmap 模式：先绘制背景截图
            if (_bitmapMode && _background != null)
            {
                e.Graphics.DrawImage(_background, 0, 0, ClientSize.Width, ClientSize.Height);
                // 半透明遮罩
                using (var overlay = new System.Drawing.SolidBrush(Color.FromArgb(100, Color.Black)))
                    e.Graphics.FillRectangle(overlay, ClientRectangle);
            }

            if (!_dragging || _current.Width <= 0 || _current.Height <= 0) return;

            // 高亮选区（挖空遮罩效果）
            using (var brush = new SolidBrush(Color.FromArgb(100, Color.White)))
                e.Graphics.FillRectangle(brush, _current);

            using (var pen = new Pen(Color.LimeGreen, 2))
                e.Graphics.DrawRectangle(pen, _current);

            // 尺寸提示
            string hint = $"{_current.Width} × {_current.Height}";
            using (var font = new Font("Consolas", 10))
            using (var brush = new SolidBrush(Color.LimeGreen))
                e.Graphics.DrawString(hint, font, brush,
                    _current.Right + 4, _current.Bottom + 4);
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            base.OnFormClosed(e);
            // Bitmap 由调用方管理，不在此 Dispose
        }

        // ── 辅助 ─────────────────────────────────────────────────────

        private static Rectangle NormalizeRect(Point a, Point b)
        {
            return new Rectangle(
                Math.Min(a.X, b.X),
                Math.Min(a.Y, b.Y),
                Math.Abs(b.X - a.X),
                Math.Abs(b.Y - a.Y));
        }
    }
}
