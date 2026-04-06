// ┌─────────────────────────────────────────────────────────┐
// │ Program.cs                                              │
// │ 角色：程序入口点，高 DPI 兜底 + 启动 Form1              │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace VisionGuard
{
    internal static class Program
    {
        // .NET Framework 4.7.2 不提供 Application.SetHighDpiMode。
        // 高 DPI 感知已通过 app.manifest（dpiAwareness=PerMonitorV2）
        // 和 App.config（DpiAwareness=PerMonitorV2）声明，此处无需额外调用。
        // 保留 SetProcessDPIAware 仅作为 Win7 旧版兜底（DWM 关闭场景）。
        [DllImport("user32.dll")]
        private static extern bool SetProcessDPIAware();

        [STAThread]
        static void Main()
        {
            // 旧版兜底（Win7 / Aero 关闭场景）；Win10/11 已由 manifest 处理
            try { SetProcessDPIAware(); } catch { }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new Form1());
        }
    }
}
