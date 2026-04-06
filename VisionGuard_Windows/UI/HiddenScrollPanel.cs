// ┌─────────────────────────────────────────────────────────┐
// │ HiddenScrollPanel.cs                                    │
// │ 角色：隐藏滚动条的 Panel（手动 WM_MOUSEWHEEL 驱动滚动） │
// │ 包含：HiddenScrollCheckedListBox (CheckedListBox 隐藏滚动条)│
// │ 用途：页面内容容器, CocoClassPickerControl 内部列表     │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace VisionGuard.UI
{
    // ═══════════════════════════════════════════════════════════════════
    // HiddenScrollPanel
    // 左侧菜单滚动容器：不使用 AutoScroll（AutoScroll 会强制显示系统滚动条），
    // 改为手动监听 WM_MOUSEWHEEL 驱动滚动位置，彻底无滚动条。
    // ═══════════════════════════════════════════════════════════════════
    public class HiddenScrollPanel : Panel
    {
        private const int WM_MOUSEWHEEL = 0x020A;
        private const int WHEEL_DELTA   = 120;

        // 每次滚轮步进：3 行 × 约 20px/行
        private const int ScrollStep = 60;

        public HiddenScrollPanel()
        {
            // 不用 AutoScroll，手动管理 AutoScrollPosition
            AutoScroll = false;
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_MOUSEWHEEL)
            {
                // delta > 0 → 向上滚（内容下移，Position 减小）
                int delta = (short)((m.WParam.ToInt64() >> 16) & 0xFFFF);
                int step  = ScrollStep * (delta > 0 ? -1 : 1);

                // AutoScrollPosition 读出来是负值，写入时要用负值
                var cur = AutoScrollPosition;
                AutoScrollPosition = new Point(-cur.X, Math.Max(0, -cur.Y + step));
                return;
            }
            base.WndProc(ref m);
        }

        // 子控件把 WM_MOUSEWHEEL 冒泡给父级
        protected override void OnMouseWheel(MouseEventArgs e)
        {
            // 由 WndProc 统一处理，base 会重复，跳过
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HiddenScrollCheckedListBox
    // 监控对象勾选列表：隐藏滚动条外观，但保留鼠标滚轮滚动能力。
    // 方案：拦截 WM_NCPAINT，自行绘制非客户区但不画滚动条。
    // ShowScrollBar 不调用，避免它同时禁用滚轮输入。
    // ═══════════════════════════════════════════════════════════════════
    internal class HiddenScrollCheckedListBox : CheckedListBox
    {
        private const int WM_NCPAINT    = 0x0085;
        private const int WM_NCCALCSIZE = 0x0083;
        private const int GWL_STYLE     = -16;
        private const int WS_HSCROLL    = 0x00100000;
        private const int WS_VSCROLL    = 0x00200000;

        [DllImport("user32.dll")] private static extern int  GetWindowLong(IntPtr hWnd, int nIndex);
        [DllImport("user32.dll")] private static extern int  SetWindowLong(IntPtr hWnd, int nIndex, int dwNewLong);
        [DllImport("user32.dll")] private static extern bool RedrawWindow(IntPtr hWnd, IntPtr lprcUpdate, IntPtr hrgnUpdate, uint flags);

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

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            RemoveScrollStyles();
        }

        protected override void WndProc(ref Message m)
        {
            // NCCALCSIZE：系统重算非客户区时，先让 base 执行（保持正确的 client 尺寸），
            // 再把滚动条样式剥掉
            if (m.Msg == WM_NCCALCSIZE)
            {
                base.WndProc(ref m);
                RemoveScrollStyles();
                return;
            }

            // NCPAINT：自行处理非客户区绘制，只画边框，不画滚动条
            if (m.Msg == WM_NCPAINT)
            {
                // 返回 0 告诉系统"我自己处理了"，什么都不画 → 无滚动条外观
                m.Result = IntPtr.Zero;
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
    }
}
