// ┌─────────────────────────────────────────────────────────┐
// │ WindowInfo.cs                                           │
// │ 角色：顶层窗口信息 DTO (Handle, Title, ClassName, Bounds)│
// │ 用途：WindowEnumerator 返回值，Form1 持有当前目标窗口   │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Drawing;

namespace VisionGuard.Capture
{
    /// <summary>
    /// 描述一个顶层窗口的基本信息。
    /// </summary>
    internal class WindowInfo
    {
        public IntPtr   Handle    { get; }
        public string   Title     { get; }
        public string   ClassName { get; }
        public Rectangle Bounds   { get; }

        public WindowInfo(IntPtr handle, string title, string className, Rectangle bounds)
        {
            Handle    = handle;
            Title     = title;
            ClassName = className;
            Bounds    = bounds;
        }

        public override string ToString() => $"{Title}  [{ClassName}]";
    }
}
