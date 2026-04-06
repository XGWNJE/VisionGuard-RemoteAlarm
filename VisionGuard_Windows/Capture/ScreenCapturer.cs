// ┌─────────────────────────────────────────────────────────┐
// │ ScreenCapturer.cs                                       │
// │ 角色：通过 GDI BitBlt 捕获屏幕指定区域                  │
// │ 线程：在 MonitorService 的 ThreadPool 回调中调用         │
// │ 依赖：NativeMethods (BitBlt 等 P/Invoke)                │
// │ 对外 API：CaptureRegion(Rectangle) → Bitmap             │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Drawing;
using System.Drawing.Imaging;

namespace VisionGuard.Capture
{
    /// <summary>
    /// 通过 GDI BitBlt 捕获屏幕指定区域。
    /// 调用方负责 Dispose 返回的 Bitmap。
    /// </summary>
    public static class ScreenCapturer
    {
        /// <summary>
        /// 捕获 <paramref name="region"/> 对应的屏幕区域，返回新 Bitmap。
        /// 调用方必须 Dispose 返回值。
        /// </summary>
        public static Bitmap CaptureRegion(Rectangle region)
        {
            IntPtr desktop  = IntPtr.Zero;
            IntPtr screenDC = IntPtr.Zero;
            IntPtr memDC    = IntPtr.Zero;
            IntPtr hBitmap  = IntPtr.Zero;
            IntPtr oldBmp   = IntPtr.Zero;

            try
            {
                desktop  = NativeMethods.GetDesktopWindow();
                screenDC = NativeMethods.GetDC(desktop);

                memDC   = NativeMethods.CreateCompatibleDC(screenDC);
                hBitmap = NativeMethods.CreateCompatibleBitmap(screenDC, region.Width, region.Height);
                oldBmp  = NativeMethods.SelectObject(memDC, hBitmap);

                NativeMethods.BitBlt(
                    memDC, 0, 0, region.Width, region.Height,
                    screenDC, region.X, region.Y,
                    NativeMethods.SRCCOPY);

                // 先包装成托管 Bitmap（内部复制像素），再释放 HBITMAP
                Bitmap result = Image.FromHbitmap(hBitmap);

                NativeMethods.SelectObject(memDC, oldBmp);
                return result;
            }
            finally
            {
                if (hBitmap  != IntPtr.Zero) NativeMethods.DeleteObject(hBitmap);
                if (memDC    != IntPtr.Zero) NativeMethods.DeleteDC(memDC);
                if (screenDC != IntPtr.Zero) NativeMethods.ReleaseDC(desktop, screenDC);
            }
        }

    }
}
