// ┌─────────────────────────────────────────────────────────┐
// │ LogManager.cs                                           │
// │ 角色：线程安全日志，输出到 IDE 输出窗口 (Debug.WriteLine)│
// │ 线程：Info/Warn/Error 可在任意线程调用                  │
// │ 对外 API：Info(), Warn(), Error()                       │
// │           StaticInfo/StaticWarn/StaticError (后台线程)  │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Diagnostics;

namespace VisionGuard.Utils
{
    /// <summary>
    /// 线程安全的日志管理器，将消息输出到 IDE 输出窗口（Debug.WriteLine）。
    /// </summary>
    public class LogManager
    {
        public LogManager() { }

        /// <summary>后台线程安全：INFO</summary>
        public static void StaticInfo(string message)
            => Debug.WriteLine(FormatLine("[INFO] " + message));

        public static void StaticWarn(string message)
            => Debug.WriteLine(FormatLine("[WARN] " + message));

        public static void StaticError(string message)
            => Debug.WriteLine(FormatLine("[ERR]  " + message));

        public void Info(string message)  => Debug.WriteLine(FormatLine("[INFO] " + message));
        public void Warn(string message)  => Debug.WriteLine(FormatLine("[WARN] " + message));
        public void Error(string message) => Debug.WriteLine(FormatLine("[ERR]  " + message));

        private static string FormatLine(string message)
            => DateTime.Now.ToString("HH:mm:ss.fff") + " " + message;
    }
}
