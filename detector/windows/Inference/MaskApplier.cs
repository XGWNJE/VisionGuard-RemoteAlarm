// ┌─────────────────────────────────────────────────────────┐
// │ MaskApplier.cs                                          │
// │ 角色：在捕获后的 Bitmap 上 in-place 涂黑遮罩区域        │
// │ 调用：MonitorService.OnTick 在 ToTensor 之前执行         │
// │ 副作用：推理帧 / 报警截图 / UI 预览三处同源              │
// │ 与 Android cropAndMask 行为对齐（黑色填充，相对坐标）   │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;

namespace VisionGuard.Inference
{
    /// <summary>
    /// 把相对坐标 [0,1] 的遮罩区域以纯黑填充到 Bitmap 上。
    /// 与 Android 端 ImagePreprocessor.cropAndMask 行为一致：
    /// 涂黑后的帧同时进入推理、报警截图、UI 预览，确保用户看到的"黑色区域"
    /// 既不被识别也不出现在通知截图里。
    /// </summary>
    public static class MaskApplier
    {
        /// <summary>
        /// 在 frame 上 in-place 涂黑所有 mask 区域（坐标相对 [0,1]）。
        /// frame 为 null 或 masks 为空时不执行任何操作。
        /// </summary>
        public static void ApplyMasks(Bitmap frame, IReadOnlyList<RectangleF> masks)
        {
            if (frame == null || masks == null || masks.Count == 0) return;

            int W = frame.Width;
            int H = frame.Height;
            if (W <= 0 || H <= 0) return;

            using (var g = Graphics.FromImage(frame))
            using (var brush = new SolidBrush(Color.Black))
            {
                for (int i = 0; i < masks.Count; i++)
                {
                    RectangleF m = masks[i];

                    // 相对坐标 → 像素，并 clamp 到画面范围
                    int x = (int)Math.Round(m.X * W);
                    int y = (int)Math.Round(m.Y * H);
                    int w = (int)Math.Round(m.Width * W);
                    int h = (int)Math.Round(m.Height * H);

                    if (x < 0) { w += x; x = 0; }
                    if (y < 0) { h += y; y = 0; }
                    if (x + w > W) w = W - x;
                    if (y + h > H) h = H - y;
                    if (w <= 0 || h <= 0) continue;

                    g.FillRectangle(brush, x, y, w, h);
                }
            }
        }
    }
}
