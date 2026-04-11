// ┌─────────────────────────────────────────────────────────┐
// │ FlatRoundButton.cs                                      │
// │ 角色：Win11 Fluent 风格扁平圆角按钮（三态色自绘）       │
// │ 用途：全局按钮统一风格                                  │
// │ 对外 API：NormalColor, HoverColor, PressColor           │
// └─────────────────────────────────────────────────────────┘
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace VisionGuard.UI
{
    /// <summary>
    /// Win11 Fluent 风格扁平圆角按钮，完全自绘，支持三态色。
    /// </summary>
    public class FlatRoundButton : Button
    {
        private bool _hovered;
        private bool _pressed;

        public Color NormalColor   { get; set; } = Color.FromArgb(58, 58, 58);
        public Color HoverColor    { get; set; } = Color.FromArgb(72, 72, 72);
        public Color PressColor    { get; set; } = Color.FromArgb(45, 45, 45);
        public Color DisabledColor { get; set; } = Color.FromArgb(42, 42, 42);

        public FlatRoundButton()
        {
            SetStyle(
                ControlStyles.UserPaint |
                ControlStyles.AllPaintingInWmPaint |
                ControlStyles.OptimizedDoubleBuffer, true);
            FlatStyle = FlatStyle.Flat;
            FlatAppearance.BorderSize = 0;
        }

        protected override void OnMouseEnter(System.EventArgs e) { _hovered = true;  Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(System.EventArgs e) { _hovered = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnMouseDown(System.Windows.Forms.MouseEventArgs e) { _pressed = true;  Invalidate(); base.OnMouseDown(e); }
        protected override void OnMouseUp(System.Windows.Forms.MouseEventArgs e)   { _pressed = false; Invalidate(); base.OnMouseUp(e); }
        protected override void OnEnabledChanged(System.EventArgs e) { Invalidate(); base.OnEnabledChanged(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;

            Rectangle rc = new Rectangle(0, 0, Width - 1, Height - 1);

            Color bg;
            Color fg;

            if (!Enabled)
            {
                bg = DisabledColor;
                fg = Color.FromArgb(136, 136, 136);
            }
            else if (_pressed)
            {
                bg = PressColor;
                fg = ForeColor;
            }
            else if (_hovered)
            {
                bg = HoverColor;
                fg = ForeColor;
            }
            else
            {
                bg = NormalColor;
                fg = ForeColor;
            }

            using (GraphicsPath path = CardPanel.RoundRect(rc, 5))
            {
                using (SolidBrush fill = new SolidBrush(bg))
                    g.FillPath(fill, path);

                if (Enabled)
                {
                    using (Pen pen = new Pen(Color.FromArgb(74, 74, 74), 1f))
                        g.DrawPath(pen, path);
                }
            }

            TextFormatFlags flags =
                TextFormatFlags.HorizontalCenter |
                TextFormatFlags.VerticalCenter |
                TextFormatFlags.SingleLine;

            TextRenderer.DrawText(g, Text, Font, ClientRectangle, fg, flags);
        }
    }
}
