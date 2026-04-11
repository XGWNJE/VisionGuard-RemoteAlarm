// ┌─────────────────────────────────────────────────────────┐
// │ WindowEnumerator.cs                                     │
// │ 角色：枚举系统顶层窗口，供 WindowPickerForm 选择        │
// │ 线程：在 UI 线程或 ThreadPool 调用                      │
// │ 依赖：NativeMethods                                     │
// │ 对外 API：GetWindows(), GetWindowBounds()               │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Text;

namespace VisionGuard.Capture
{
    /// <summary>
    /// 枚举系统中所有可见的顶层窗口，过滤后返回 <see cref="WindowInfo"/> 列表。
    /// </summary>
    internal static class WindowEnumerator
    {
        // 黑名单：不应出现在选择列表中的 Shell 窗口类名
        private static readonly HashSet<string> _classBlacklist = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "Shell_TrayWnd",       // 任务栏
            "Progman",             // 桌面
            "WorkerW",             // 桌面壁纸层
            "DV2ControlHost",      // 开始菜单旧版
            "Windows.UI.Core.CoreWindow", // UWP Shell 层
            "ApplicationFrameWindow",     // UWP 宿主（可视需要放开）
        };

        /// <summary>
        /// 枚举所有符合条件的顶层窗口。
        /// </summary>
        /// <param name="excludeHwnd">排除的窗口句柄（传入主窗口以排除自身）</param>
        public static List<WindowInfo> GetWindows(IntPtr excludeHwnd)
        {
            var result = new List<WindowInfo>();

            NativeMethods.EnumWindows((hwnd, _) =>
            {
                // 排除自身
                if (hwnd == excludeHwnd) return true;

                // 必须可见
                if (!NativeMethods.IsWindowVisible(hwnd)) return true;

                // 排除最小化窗口
                if (NativeMethods.IsIconic(hwnd)) return true;

                // 获取标题
                int titleLen = NativeMethods.GetWindowTextLength(hwnd);
                if (titleLen <= 0) return true;

                var titleSb = new StringBuilder(titleLen + 1);
                NativeMethods.GetWindowText(hwnd, titleSb, titleSb.Capacity);
                string title = titleSb.ToString().Trim();
                if (string.IsNullOrEmpty(title)) return true;

                // 获取类名
                var classSb = new StringBuilder(256);
                NativeMethods.GetClassName(hwnd, classSb, classSb.Capacity);
                string className = classSb.ToString();

                // 黑名单过滤
                if (_classBlacklist.Contains(className)) return true;

                // 获取边界（优先 DWM 真实边界，失败回退 GetWindowRect）
                Rectangle bounds = GetWindowBounds(hwnd);

                result.Add(new WindowInfo(hwnd, title, className, bounds));
                return true;
            }, IntPtr.Zero);

            return result;
        }

        /// <summary>
        /// 获取窗口的真实边界矩形（含 DWM 阴影补偿）。
        /// </summary>
        internal static Rectangle GetWindowBounds(IntPtr hwnd)
        {
            int hr = NativeMethods.DwmGetWindowAttribute(
                hwnd,
                NativeMethods.DWMWA_EXTENDED_FRAME_BOUNDS,
                out NativeMethods.RECT dwmRect,
                System.Runtime.InteropServices.Marshal.SizeOf(typeof(NativeMethods.RECT)));

            if (hr == 0) // S_OK
                return dwmRect.ToRectangle();

            // 回退到 GetWindowRect
            if (NativeMethods.GetWindowRect(hwnd, out NativeMethods.RECT winRect))
                return winRect.ToRectangle();

            return Rectangle.Empty;
        }
    }
}
