// ┌─────────────────────────────────────────────────────────┐
// │ DetectionOverlayPanel.cs                                │
// │ 角色：双缓冲 Panel，显示最后一帧截图+叠加检测框         │
// │ 线程：UpdateFrame 可在任意线程调用（内部 lock + Invoke） │
// │ 对外 API：UpdateFrame(Bitmap, List<Detection>)          │
// └─────────────────────────────────────────────────────────┘
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;
using VisionGuard.Models;

namespace VisionGuard.UI
{
    /// <summary>
    /// 双缓冲 Panel：显示最后一帧截图并叠加绘制检测框。
    /// 线程安全：UpdateFrame / UpdateDetections 可在任意线程调用。
    /// </summary>
    public class DetectionOverlayPanel : Panel
    {
        private Bitmap           _frame;
        private List<Detection>  _detections;
        private readonly object  _lock = new object();

        // 检测框颜色
        private static readonly Color BoxColor  = Color.LimeGreen;
        private static readonly Color TextBg    = Color.FromArgb(180, Color.Black);

        public DetectionOverlayPanel()
        {
            DoubleBuffered = true;
            BackColor      = Color.FromArgb(20, 20, 20);
        }

        /// <summary>
        /// 更新预览帧和检测结果，同时触发重绘。
        /// 接管 frame 的所有权（内部 Dispose 旧帧）。
        /// </summary>
        public void UpdateFrame(Bitmap frame, List<Detection> detections)
        {
            Bitmap old;
            lock (_lock)
            {
                old         = _frame;
                _frame      = frame;
                _detections = detections;
            }
            old?.Dispose();

            if (InvokeRequired)
                BeginInvoke(new System.Action(Invalidate));
            else
                Invalidate();
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);

            Bitmap           frameCopy = null;
            List<Detection>  dets;
            lock (_lock)
            {
                if (_frame != null)
                    frameCopy = (Bitmap)_frame.Clone();
                dets = _detections;
            }

            Graphics g = e.Graphics;

            if (frameCopy == null)
            {
                using (var brush = new SolidBrush(Color.FromArgb(60, 60, 60)))
                    g.FillRectangle(brush, ClientRectangle);

                using (var font = new Font("Consolas", 10))
                using (var brush = new SolidBrush(Color.Gray))
                {
                    string msg = "等待捕获...";
                    SizeF sz   = g.MeasureString(msg, font);
                    g.DrawString(msg, font, brush,
                        (Width - sz.Width) / 2f,
                        (Height - sz.Height) / 2f);
                }
                return;
            }

            try
            {
            // 等比缩放到面板区域
            RectangleF dst = FitRect(frameCopy.Width, frameCopy.Height, Width, Height);
            g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.Bilinear;
            g.DrawImage(frameCopy, dst);

            if (dets == null || dets.Count == 0) return;

            // 坐标缩放比
            float sx = dst.Width  / frameCopy.Width;
            float sy = dst.Height / frameCopy.Height;

            using (var pen  = new Pen(BoxColor, 2))
            using (var font = new Font("Consolas", 8, FontStyle.Bold))
            {
                foreach (var det in dets)
                {
                    float x = dst.X + det.BoundingBox.X * sx;
                    float y = dst.Y + det.BoundingBox.Y * sy;
                    float w = det.BoundingBox.Width  * sx;
                    float h = det.BoundingBox.Height * sy;

                    g.DrawRectangle(pen, x, y, w, h);

                    string label = $"{det.Label} {det.Confidence:P0}";
                    SizeF  sz    = g.MeasureString(label, font);

                    float lx = x;
                    float ly = y - sz.Height - 2;
                    if (ly < 0) ly = y + 2;

                    using (var bgBrush = new SolidBrush(TextBg))
                        g.FillRectangle(bgBrush, lx, ly, sz.Width, sz.Height);
                    using (var textBrush = new SolidBrush(BoxColor))
                        g.DrawString(label, font, textBrush, lx, ly);
                }
            }
            }
            finally
            {
                frameCopy.Dispose();
            }
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                lock (_lock) { _frame?.Dispose(); _frame = null; }
            }
            base.Dispose(disposing);
        }

        private static RectangleF FitRect(int srcW, int srcH, int dstW, int dstH)
        {
            float scale = System.Math.Min((float)dstW / srcW, (float)dstH / srcH);
            float w     = srcW * scale;
            float h     = srcH * scale;
            return new RectangleF((dstW - w) / 2f, (dstH - h) / 2f, w, h);
        }
    }
}
