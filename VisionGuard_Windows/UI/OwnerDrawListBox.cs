// ┌─────────────────────────────────────────────────────────┐
// │ OwnerDrawListBox.cs                                     │
// │ 角色：按日志级别着色的 ListBox（隐藏系统滚动条）        │
// │ 用途：Form1 日志面板，配合 LogManager 使用              │
// │ 着色规则：[WARN]→黄, [ERR]→红, 其他→灰白               │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace VisionGuard.UI
{
    /// <summary>
    /// Owner-Draw ListBox，按日志级别着色行文字。
    /// 继承 ListBox，可直接传给 LogManager(ListBox) 构造参数，无需修改 LogManager。
    /// 系统滚动条通过 WM_NCPAINT / WM_NCCALCSIZE 拦截隐藏。
    /// </summary>
    public class OwnerDrawListBox : ListBox
    {
        private static readonly Color BgEven    = Color.FromArgb(21, 21, 21);
        private static readonly Color BgOdd     = Color.FromArgb(26, 26, 26);
        private static readonly Color BgSel     = Color.FromArgb(0, 84, 153);
        private static readonly Color FgNormal  = Color.FromArgb(204, 204, 204);
        private static readonly Color FgWarn    = Color.FromArgb(249, 199, 79);
        private static readonly Color FgError   = Color.FromArgb(240, 112, 112);
        private static readonly Color FgSel     = Color.White;

        private const int WM_NCPAINT    = 0x0085;
        private const int WM_NCCALCSIZE = 0x0083;
        private const int GWL_STYLE     = -16;
        private const int WS_HSCROLL    = 0x00100000;
        private const int WS_VSCROLL    = 0x00200000;

        [DllImport("user32.dll")] private static extern int GetWindowLong(IntPtr hWnd, int nIndex);
        [DllImport("user32.dll")] private static extern int SetWindowLong(IntPtr hWnd, int nIndex, int dwNewLong);

        protected override CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                cp.Style &= ~WS_HSCROLL;
                cp.Style &= ~WS_VSCROLL;
                return cp;
            }
        }

        protected override void OnHandleCreated(System.EventArgs e)
        {
            base.OnHandleCreated(e);
            RemoveScrollStyles();
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_NCCALCSIZE)
            {
                base.WndProc(ref m);
                RemoveScrollStyles();
                return;
            }
            // WM_NCPAINT：不绘制非客户区（滚动条在此绘制），视觉上彻底消失
            if (m.Msg == WM_NCPAINT)
            {
                m.Result = System.IntPtr.Zero;
                return;
            }
            base.WndProc(ref m);
        }

        private void RemoveScrollStyles()
        {
            if (!IsHandleCreated) return;
            int style = GetWindowLong(Handle, GWL_STYLE);
            int clean = style & ~WS_HSCROLL & ~WS_VSCROLL;
            if (clean != style)
                SetWindowLong(Handle, GWL_STYLE, clean);
        }

        public OwnerDrawListBox()
        {
            DrawMode      = DrawMode.OwnerDrawFixed;
            BorderStyle   = BorderStyle.None;
            BackColor     = Color.FromArgb(15, 15, 15);
            ForeColor     = FgNormal;
            SelectionMode = SelectionMode.None;
            // 字体继承自父容器（Form），不硬编码
            // ItemHeight 在 OnFontChanged 里动态更新
            UpdateItemHeight();
        }

        protected override void OnFontChanged(System.EventArgs e)
        {
            base.OnFontChanged(e);
            UpdateItemHeight();
        }

        private void UpdateItemHeight()
        {
            // 行高 = 字体高度 + 上下各 2px 行间距
            ItemHeight = Font.Height + 4;
        }

        protected override void OnDrawItem(DrawItemEventArgs e)
        {
            if (e.Index < 0) return;

            bool selected = (e.State & DrawItemState.Selected) != 0;
            Color bg = selected ? BgSel
                     : (e.Index % 2 == 0 ? BgEven : BgOdd);

            e.Graphics.FillRectangle(new SolidBrush(bg), e.Bounds);

            string text = Items[e.Index].ToString();
            Color fg;
            if      (text.Contains("[WARN]")) fg = FgWarn;
            else if (text.Contains("[ERR]"))  fg = FgError;
            else                              fg = selected ? FgSel : FgNormal;

            TextRenderer.DrawText(
                e.Graphics, text, Font, e.Bounds, fg,
                TextFormatFlags.Left |
                TextFormatFlags.VerticalCenter |
                TextFormatFlags.EndEllipsis);
        }
    }
}
