// ┌─────────────────────────────────────────────────────────┐
// │ CardPanel.cs                                            │
// │ 角色：Win11 Fluent 风格圆角卡片面板（替代 GroupBox）     │
// │ 用途：Form1 各分页内容的容器控件                        │
// │ 对外 API：Title, ContentTop, RoundRect() (FlatRoundButton共用)│
// └─────────────────────────────────────────────────────────┘
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace VisionGuard.UI
{
    /// <summary>
    /// Win11 Fluent 风格圆角卡片面板，替代原生 GroupBox。
    /// </summary>
    public class CardPanel : Panel
    {
        private bool _hovered;

        public string Title { get; set; }

        /// <summary>
        /// 内容区起始 Y 坐标（标题栏下方）。
        /// AddCard 的 build 回调应从此值开始放置控件。
        /// </summary>
        public int ContentTop => Padding.Top;

        public CardPanel()
        {
            SetStyle(
                ControlStyles.UserPaint |
                ControlStyles.AllPaintingInWmPaint |
                ControlStyles.OptimizedDoubleBuffer |
                ControlStyles.ResizeRedraw, true);

            BackColor = Color.FromArgb(42, 42, 42);
            ForeColor = Color.White;
            // 固定内边距：左右8，顶部留给标题行（字体行高+上下各4），底部6
            // 在 OnFontChanged 里同步更新，确保与实际字体匹配
            UpdatePadding();
        }

        protected override void OnFontChanged(System.EventArgs e)
        {
            base.OnFontChanged(e);
            UpdatePadding();
            Invalidate();
        }

        private void UpdatePadding()
        {
            // 标题行高 = 字体行高 + 上边距4 + 下边距4
            int titleRowH = Font.Height + 8;
            Padding = new Padding(8, titleRowH, 8, 6);
        }

        protected override void OnMouseEnter(System.EventArgs e)
        {
            _hovered = true;
            Invalidate();
            base.OnMouseEnter(e);
        }

        protected override void OnMouseLeave(System.EventArgs e)
        {
            _hovered = false;
            Invalidate();
            base.OnMouseLeave(e);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;

            Rectangle rc = new Rectangle(0, 0, Width - 1, Height - 1);

            using (GraphicsPath path = RoundRect(rc, 6))
            {
                using (SolidBrush fill = new SolidBrush(Color.FromArgb(42, 42, 42)))
                    g.FillPath(fill, path);

                Color borderColor = _hovered
                    ? Color.FromArgb(74, 74, 74)
                    : Color.FromArgb(56, 56, 56);
                using (Pen pen = new Pen(borderColor, 1f))
                    g.DrawPath(pen, path);
            }

            // 标题文字：垂直居中在标题行内
            if (!string.IsNullOrEmpty(Title))
            {
                int titleH = Padding.Top;
                using (Font f = new Font(Font, FontStyle.Bold))
                using (SolidBrush brush = new SolidBrush(Color.FromArgb(200, 200, 200)))
                {
                    var titleRect = new RectangleF(10, 0, Width - 20, titleH);
                    var sf = new StringFormat
                    {
                        Alignment     = StringAlignment.Near,
                        LineAlignment = StringAlignment.Center
                    };
                    g.DrawString(Title, f, brush, titleRect, sf);
                }
            }
        }

        internal static GraphicsPath RoundRect(Rectangle r, int radius)
        {
            var path = new GraphicsPath();
            int d = radius * 2;
            path.AddArc(r.X,         r.Y,          d, d, 180, 90);
            path.AddArc(r.Right - d, r.Y,          d, d, 270, 90);
            path.AddArc(r.Right - d, r.Bottom - d, d, d,   0, 90);
            path.AddArc(r.X,         r.Bottom - d, d, d,  90, 90);
            path.CloseFigure();
            return path;
        }
    }
}
