// ┌─────────────────────────────────────────────────────────┐
// │ DarkStatusRenderer.cs                                   │
// │ 角色：StatusStrip 暗色渲染器 (深色背景+分隔线+Grip)     │
// │ 用途：Form1 状态栏样式                                  │
// └─────────────────────────────────────────────────────────┘
using System.Drawing;
using System.Windows.Forms;

namespace VisionGuard.UI
{
    /// <summary>
    /// Win11 Fluent 风格状态栏渲染器：深色背景 + 顶部分隔线 + 点阵 Grip。
    /// </summary>
    public class DarkStatusRenderer : ToolStripProfessionalRenderer
    {
        private static readonly Color BgColor     = Color.FromArgb(37, 37, 37);
        private static readonly Color BorderColor  = Color.FromArgb(56, 56, 56);
        private static readonly Color GripColor    = Color.FromArgb(85, 85, 85);

        protected override void OnRenderToolStripBackground(ToolStripRenderEventArgs e)
        {
            using (SolidBrush brush = new SolidBrush(BgColor))
                e.Graphics.FillRectangle(brush, e.AffectedBounds);
        }

        protected override void OnRenderToolStripBorder(ToolStripRenderEventArgs e)
        {
            // 顶部 1px 分隔线
            using (Pen pen = new Pen(BorderColor))
                e.Graphics.DrawLine(pen,
                    e.AffectedBounds.Left,  e.AffectedBounds.Top,
                    e.AffectedBounds.Right, e.AffectedBounds.Top);
        }

        protected override void OnRenderStatusStripSizingGrip(ToolStripRenderEventArgs e)
        {
            // 3×3 点阵，模拟 Win11 尺寸调整 Grip
            int x0 = e.AffectedBounds.Right  - 14;
            int y0 = e.AffectedBounds.Bottom - 14;
            using (SolidBrush dot = new SolidBrush(GripColor))
            {
                for (int row = 0; row < 3; row++)
                    for (int col = 0; col < 3; col++)
                        e.Graphics.FillRectangle(dot,
                            x0 + col * 4, y0 + row * 4, 2, 2);
            }
        }
    }
}
