// ┌─────────────────────────────────────────────────────────┐
// │ LogManager.cs                                           │
// │ 角色：线程安全日志，将消息 marshal 到 UI 线程写入 ListBox│
// │ 线程：Info/Warn/Error 可在任意线程调用                  │
// │ 依赖：ListBox (OwnerDrawListBox)                        │
// │ 对外 API：Info(), Warn(), Error()                       │
// │           StaticInfo/StaticWarn/StaticError (后台线程)  │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Windows.Forms;

namespace VisionGuard.Utils
{
    /// <summary>
    /// 线程安全的日志管理器，将消息 marshal 到 UI 线程写入 ListBox。
    /// 静态方法供无法访问实例的后台服务（如 ServerPushService）使用。
    /// </summary>
    public class LogManager
    {
        // ── 静态路由（供后台线程调用）───────────────────────────────
        private static LogManager _instance;

        private readonly ListBox _listBox;
        private const int MaxEntries = 500;

        public LogManager(ListBox listBox)
        {
            _listBox  = listBox;
            _instance = this;   // 注册全局实例
        }

        /// <summary>后台线程安全：INFO（转发到实例，无实例则 trace 丢弃）</summary>
        public static void StaticInfo(string message)  => _instance?.Info(message);
        public static void StaticWarn(string message)  => _instance?.Warn(message);
        public static void StaticError(string message) => _instance?.Error(message);

        public void Info(string message)  => Append("[INFO] " + message);
        public void Warn(string message)  => Append("[WARN] " + message);
        public void Error(string message) => Append("[ERR]  " + message);

        private void Append(string message)
        {
            string line = DateTime.Now.ToString("HH:mm:ss.fff") + " " + message;

            if (_listBox.InvokeRequired)
                _listBox.BeginInvoke(new Action<string>(WriteToListBox), line);
            else
                WriteToListBox(line);
        }

        private void WriteToListBox(string line)
        {
            if (_listBox.Items.Count >= MaxEntries)
                _listBox.Items.RemoveAt(0);

            _listBox.Items.Add(line);
            _listBox.TopIndex = _listBox.Items.Count - 1;
        }
    }
}
