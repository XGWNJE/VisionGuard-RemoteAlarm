// ┌─────────────────────────────────────────────────────────┐
// │ WindowCapturer.cs                                       │
// │ 角色：通过 PrintWindow API 捕获窗口内容（支持遮挡/最小化）│
// │ 线程：在 MonitorService 的 ThreadPool 回调中调用         │
// │ 依赖：NativeMethods, WindowEnumerator (获取边界)         │
// │ 对外 API：CaptureWindow(hwnd, subRegion) → Bitmap       │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Drawing;
using System.Drawing.Imaging;

namespace VisionGuard.Capture
{
    /// <summary>
    /// 使用 PrintWindow API 捕获目标窗口内容。
    /// 即使窗口被其他窗口遮挡或最小化，也能正常捕获。
    /// 调用方负责 Dispose 返回的 Bitmap。
    /// </summary>
    public static class WindowCapturer
    {
        /// <summary>
        /// 捕获指定窗口的内容。
        /// </summary>
        /// <param name="hwnd">目标窗口句柄</param>
        /// <param name="subRegion">
        /// 要裁剪的子区域（相对于捕获 Bitmap 的坐标系）。
        /// Rectangle.Empty 表示返回整个窗口图像。
        /// </param>
        /// <returns>捕获得到的 Bitmap，调用方负责 Dispose。</returns>
        /// <exception cref="InvalidOperationException">PrintWindow 失败时抛出。</exception>
        public static Bitmap CaptureWindow(IntPtr hwnd, Rectangle subRegion)
        {
            // 1. 获取窗口真实边界（含 DWM 阴影补偿）
            Rectangle bounds = WindowEnumerator.GetWindowBounds(hwnd);
            if (bounds.IsEmpty || bounds.Width <= 0 || bounds.Height <= 0)
                throw new InvalidOperationException("无法获取目标窗口尺寸，窗口可能已关闭。");

            // 2. 创建匹配尺寸的目标 Bitmap + HDC
            var bitmap  = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format32bppArgb);
            using (Graphics g = Graphics.FromImage(bitmap))
            {
                IntPtr hdc = g.GetHdc();
                try
                {
                    // 3. 尝试 PW_RENDERFULLCONTENT（含 GPU 加速内容）
                    bool ok = NativeMethods.PrintWindow(hwnd, hdc, NativeMethods.PW_RENDERFULLCONTENT);
                    if (!ok)
                    {
                        // 回退：PW_CLIENTONLY
                        ok = NativeMethods.PrintWindow(hwnd, hdc, NativeMethods.PW_CLIENTONLY);
                    }
                    if (!ok)
                    {
                        bitmap.Dispose();
                        throw new InvalidOperationException(
                            "PrintWindow 失败，目标窗口可能不支持该捕获方式。");
                    }
                }
                finally
                {
                    g.ReleaseHdc(hdc);
                }
            }

            // 4. 可选：黑屏检测（采样10点，全黑时记录警告但不抛出）
            if (IsAllBlack(bitmap))
            {
                System.Diagnostics.Debug.WriteLine(
                    "[WindowCapturer] 警告：捕获画面全黑，目标窗口可能使用 GPU 加速渲染。");
            }

            // 5. 裁剪子区域
            if (subRegion != Rectangle.Empty && subRegion.Width > 0 && subRegion.Height > 0)
            {
                // 确保子区域在 Bitmap 范围内
                var clipped = Rectangle.Intersect(
                    subRegion,
                    new Rectangle(0, 0, bitmap.Width, bitmap.Height));

                if (clipped.IsEmpty)
                {
                    bitmap.Dispose();
                    throw new InvalidOperationException("子区域超出窗口边界。");
                }

                Bitmap cropped = bitmap.Clone(clipped, PixelFormat.Format32bppArgb);
                bitmap.Dispose();
                return cropped;
            }

            return bitmap;
        }

        /// <summary>
        /// 采样10个点检测是否为全黑（判断 PrintWindow 黑屏情形）。
        /// </summary>
        private static bool IsAllBlack(Bitmap bmp)
        {
            if (bmp.Width == 0 || bmp.Height == 0) return true;

            int stepX = Math.Max(1, bmp.Width  / 5);
            int stepY = Math.Max(1, bmp.Height / 2);

            for (int x = stepX; x < bmp.Width;  x += stepX)
            for (int y = stepY; y < bmp.Height; y += stepY)
            {
                Color c = bmp.GetPixel(x, y);
                if (c.R > 5 || c.G > 5 || c.B > 5)
                    return false;
            }
            return true;
        }
    }
}
