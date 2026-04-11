// ┌─────────────────────────────────────────────────────────┐
// │ MenuButton.cs                                           │
// │ 角色：左侧菜单按钮自绘控件（图标+文字，选中态高亮）     │
// │ 用途：Form1 左侧导航菜单，切换 捕获/参数/目标/服务器 页 │
// │ 对外 API：IconText, IsSelected, Click 事件              │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace VisionGuard.UI
{
    /// <summary>
    /// 左侧菜单按钮：上方图标文字 + 下方标签文字，自绘。
    /// 选中态高亮背景，悬停态微亮。
    /// </summary>
    public class MenuButton : Control
    {
        private bool _isSelected;
        private bool _hovered;

        // ── 颜色 ──────────────────────────────────────────────────
        private static readonly Color BgNormal   = Color.FromArgb(30, 30, 30);
        private static readonly Color BgHover    = Color.FromArgb(45, 45, 45);
        private static readonly Color BgSelected = Color.FromArgb(0, 100, 180);
        private static readonly Color FgNormal   = Color.FromArgb(170, 170, 170);
        private static readonly Color FgSelected = Color.White;
        private static readonly Color Indicator  = Color.FromArgb(0, 120, 212);

        /// <summary>显示在按钮上方的图标字符（如 emoji 或符号字符）</summary>
        public string IconText { get; set; } = "";

        /// <summary>图标图片（优先于 IconText）</summary>
        public Image IconImage { get; set; }

        /// <summary>是否处于选中状态</summary>
        public bool IsSelected
        {
            get => _isSelected;
            set { _isSelected = value; Invalidate(); }
        }

        public MenuButton()
        {
            SetStyle(
                ControlStyles.UserPaint |
                ControlStyles.AllPaintingInWmPaint |
                ControlStyles.OptimizedDoubleBuffer |
                ControlStyles.ResizeRedraw, true);

            Cursor = Cursors.Hand;
            Size   = new Size(72, 64);
        }

        protected override void OnMouseEnter(EventArgs e) { _hovered = true;  Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { _hovered = false; Invalidate(); base.OnMouseLeave(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode     = SmoothingMode.AntiAlias;
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;

            // 背景
            Color bg = _isSelected ? BgSelected : (_hovered ? BgHover : BgNormal);
            using (var brush = new SolidBrush(bg))
                g.FillRectangle(brush, ClientRectangle);

            // 选中态左侧指示条（3px 宽）
            if (_isSelected)
            {
                using (var brush = new SolidBrush(Indicator))
                    g.FillRectangle(brush, 0, 4, 3, Height - 8);
            }

            Color fg = _isSelected ? FgSelected : FgNormal;

            // 图标（上半部分）
            if (IconImage != null)
            {
                // 绘制图片图标，居中，保留上下边距
                float imgSize = Math.Min(Width - 16, Height * 0.50f);
                float imgX = (Width - imgSize) / 2;
                float imgY = 4;
                g.DrawImage(IconImage, new RectangleF(imgX, imgY, imgSize, imgSize));
            }
            else if (!string.IsNullOrEmpty(IconText))
            {
                using (var iconFont = new Font("Segoe UI Emoji", Font.Size + 2, FontStyle.Regular, GraphicsUnit.Point))
                using (var brush = new SolidBrush(fg))
                {
                    var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Far };
                    var iconRect = new RectangleF(0, 2, Width, Height * 0.55f);
                    g.DrawString(IconText, iconFont, brush, iconRect, sf);
                }
            }

            // 文字标签（下半部分）
            using (var labelFont = new Font(Font.FontFamily, Font.Size - 1, FontStyle.Regular, GraphicsUnit.Point))
            using (var brush = new SolidBrush(fg))
            {
                var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Near };
                var textRect = new RectangleF(0, Height * 0.58f, Width, Height * 0.42f);
                g.DrawString(Text, labelFont, brush, textRect, sf);
            }
        }
    }
}
