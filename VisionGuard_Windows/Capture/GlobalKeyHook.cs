// ┌─────────────────────────────────────────────────────────┐
// │ GlobalKeyHook.cs                                        │
// │ 角色：全局键盘钩子（WH_KEYBOARD_LL），报警时监听 Space   │
// │ 线程：必须在 UI 线程创建/释放（依赖消息循环）            │
// │ 依赖：NativeMethods                                     │
// │ 对外 API：KeyDown 事件, Dispose()                       │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace VisionGuard.Capture
{
    /// <summary>
    /// 低级全局键盘钩子（WH_KEYBOARD_LL）。
    /// 必须在 UI 线程创建和释放（需要消息循环）。
    /// </summary>
    internal sealed class GlobalKeyHook : IDisposable
    {
        public event Action<Keys> KeyDown;

        private const int WH_KEYBOARD_LL = 13;
        private const int WM_KEYDOWN     = 0x0100;

        private IntPtr _hook = IntPtr.Zero;
        // 必须持有委托引用，防止 GC 回收
        private readonly NativeMethods.LowLevelKeyboardProc _proc;

        public GlobalKeyHook()
        {
            _proc = HookCallback;
            _hook = NativeMethods.SetWindowsHookEx(
                WH_KEYBOARD_LL, _proc,
                NativeMethods.GetModuleHandle(null), 0);
        }

        private IntPtr HookCallback(int nCode, IntPtr wParam, IntPtr lParam)
        {
            if (nCode >= 0 && wParam == (IntPtr)WM_KEYDOWN)
            {
                int vkCode = Marshal.ReadInt32(lParam);
                KeyDown?.Invoke((Keys)vkCode);
            }
            return NativeMethods.CallNextHookEx(_hook, nCode, wParam, lParam);
        }

        public void Dispose()
        {
            if (_hook != IntPtr.Zero)
            {
                NativeMethods.UnhookWindowsHookEx(_hook);
                _hook = IntPtr.Zero;
            }
        }
    }
}
