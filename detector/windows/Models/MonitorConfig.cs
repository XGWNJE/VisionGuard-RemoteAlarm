// ┌─────────────────────────────────────────────────────────┐
// │ MonitorConfig.cs                                        │
// │ 角色：监控配置 DTO (捕获模式/阈值/FPS/类别等)           │
// │ 用途：Form1 构建 → MonitorService.Start() 消费          │
// │ 包含：CaptureMode 枚举 (ScreenRegion / WindowHandle)    │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;

namespace VisionGuard.Models
{
    public enum CaptureMode
    {
        ScreenRegion,   // 原有 BitBlt 屏幕区域捕获
        WindowHandle    // PrintWindow 窗口句柄捕获
    }

    public class MonitorConfig
    {
        // ── 原有字段 ─────────────────────────────────────────────────
        public Rectangle CaptureRegion { get; set; } = new Rectangle(0, 0, 640, 480);
        public float ConfidenceThreshold { get; set; } = 0.45f;
        public float IouThreshold { get; set; } = 0.45f;
        public int TargetFps { get; set; } = 3;
        // 要监控的类名集合，空集合 = 检测全部（空白配置也视为全部）
        public HashSet<string> WatchedClasses { get; set; } = new HashSet<string>();
        public int AlertCooldownSeconds { get; set; } = 5;
        public bool SaveAlertSnapshot { get; set; } = true;

        // ── 新增字段 ─────────────────────────────────────────────────
        /// <summary>捕获模式：屏幕区域或窗口句柄</summary>
        public CaptureMode CaptureMode { get; set; } = CaptureMode.ScreenRegion;

        /// <summary>目标窗口标题（跨会话恢复用，不可序列化 HWND 时用此重匹配）</summary>
        public string TargetWindowTitle { get; set; } = string.Empty;

        /// <summary>
        /// WindowHandle 模式下捕获的子区域（相对于窗口客户区坐标）。
        /// Rectangle.Empty 表示捕获整个窗口。
        /// </summary>
        public Rectangle WindowSubRegion { get; set; } = Rectangle.Empty;

        /// <summary>
        /// 运行时持有的目标窗口句柄（不持久化，每次启动时由 Form1 注入）。
        /// </summary>
        [System.Xml.Serialization.XmlIgnore]
        public IntPtr TargetWindowHandle { get; set; } = IntPtr.Zero;
    }
}
