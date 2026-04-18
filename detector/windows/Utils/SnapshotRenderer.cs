// ┌─────────────────────────────────────────────────────────┐
// │ SnapshotRenderer.cs                                     │
// │ 角色：在 Bitmap 上绘制检测框和标签（用于报警截图标注）  │
// │ 视觉风格与 DetectionOverlayPanel 保持一致               │
// └─────────────────────────────────────────────────────────┘
using System.Collections.Generic;
using System.Drawing;
using VisionGuard.Models;

namespace VisionGuard.Utils
{
    public static class SnapshotRenderer
    {
        private static readonly Color BoxColor = Color.LimeGreen;
        private static readonly Color TextBg   = Color.FromArgb(180, Color.Black);

        /// <summary>
        /// 在 Bitmap 上绘制检测框和标签（原地修改，不创建副本）。
        /// BoundingBox 坐标为帧像素坐标，直接绘制无需缩放。
        /// </summary>
        public static void DrawDetections(Bitmap bmp, IReadOnlyList<Detection> detections)
        {
            if (bmp == null || detections == null || detections.Count == 0) return;

            using (var g = Graphics.FromImage(bmp))
            using (var pen = new Pen(BoxColor, 2))
            using (var font = new Font("Consolas", 8, FontStyle.Bold))
            using (var textBrush = new SolidBrush(BoxColor))
            using (var bgBrush = new SolidBrush(TextBg))
            {
                foreach (var det in detections)
                {
                    var box = det.BoundingBox;
                    g.DrawRectangle(pen, box.X, box.Y, box.Width, box.Height);

                    string label = $"{det.Label} {det.Confidence:P0}";
                    SizeF sz = g.MeasureString(label, font);

                    // 标签默认在框上方；超出画面则放框内顶部
                    float ly = box.Y - sz.Height - 2;
                    if (ly < 0) ly = box.Y + 2;

                    g.FillRectangle(bgBrush, box.X, ly, sz.Width + 4, sz.Height);
                    g.DrawString(label, font, textBrush, box.X + 2, ly);
                }
            }
        }
    }
}
