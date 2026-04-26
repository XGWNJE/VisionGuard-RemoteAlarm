// ┌─────────────────────────────────────────────────────────┐
// │ MonitorService.cs                                       │
// │ 角色：主监控循环，定时截图→推理→报警                    │
// │ 线程：Timer回调在 ThreadPool 执行，UI 更新通过事件      │
// │ 依赖：OnnxInferenceEngine, AlertService, ImagePreprocessor│
// │ 对外 API：Start(), Stop(), Pause(), Resume()            │
// │ 事件：FrameProcessed (每帧结果通知 Form1 更新 UI)       │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Threading;
using VisionGuard.Capture;
using VisionGuard.Inference;
using VisionGuard.Models;

namespace VisionGuard.Services
{
    /// <summary>
    /// 主监控循环：定时截图 → 推理 → 报警。
    /// 所有推理在 ThreadPool 线程执行，UI 线程不受阻塞。
    /// </summary>
    public sealed class MonitorService : IDisposable
    {
        public event EventHandler<FrameResultEventArgs> FrameProcessed;

        private OnnxInferenceEngine  _engine;
        private AlertService         _alertService;
        private MonitorConfig        _config;
        private Timer                _timer;
        private int                  _isRunning;   // 0=idle, 1=processing（Interlocked 防重入）
        private int                  _isPaused;    // 0=运行, 1=暂停（报警期间）
        private bool                 _disposed;
        // 停止同步：确保 OnTick 完全结束（包括 finally）后才能安全 Dispose _engine
        private readonly ManualResetEvent _tickCompleted = new ManualResetEvent(true);

        public bool IsStarted => _timer != null;

        /// <summary>选区/窗口是否已设定（用于心跳同步给 Android 显示准备状态）</summary>
        public bool IsReady
        {
            get
            {
                if (_config == null) return false;
                if (_config.CaptureMode == CaptureMode.WindowHandle)
                    return _config.TargetWindowHandle != IntPtr.Zero;
                return _config.CaptureRegion.Width >= 32 && _config.CaptureRegion.Height >= 32;
            }
        }

        public MonitorService(AlertService alertService)
        {
            _alertService = alertService;
        }

        /// <summary>
        /// 启动监控。modelPath = yolo26n.onnx 或 yolo26s.onnx 完整路径。
        /// </summary>
        public void Start(string modelPath, MonitorConfig config)
        {
            if (_disposed) throw new ObjectDisposedException(nameof(MonitorService));
            if (_timer != null) return;

            _config  = config;
            _engine  = new OnnxInferenceEngine(modelPath, intraOpNumThreads: 2);

            int intervalMs = 1000 / Math.Max(1, config.TargetFps);
            _timer = new Timer(OnTick, null, 0, intervalMs);
        }

        public void Stop()
        {
            // 阻止新 OnTick 进入，并等待正在执行的 Tick 完全结束
            _tickCompleted.Reset();           // 未完成信号
            _timer?.Dispose();
            _timer = null;
            _tickCompleted.WaitOne(2000);     // 最多等2秒让 OnTick 退出
            _engine?.Dispose();
            _engine = null;
            _isRunning = 0;
            _isPaused  = 0;
            _tickCompleted.Set();             // 恢复为已结束状态
        }

        /// <summary>暂停推理（报警期间调用）</summary>
        public void Pause()  => Interlocked.Exchange(ref _isPaused, 1);

        /// <summary>恢复推理（用户停止铃声后调用）</summary>
        public void Resume() => Interlocked.Exchange(ref _isPaused, 0);

        public void UpdateConfig(MonitorConfig config)
        {
            Volatile.Write(ref _config, config);
        }

        // ── 每帧回调（ThreadPool 线程）──────────────────────────────

        private void OnTick(object state)
        {
            // 停止中：跳过本次Tick（Stop 已调用 WaitOne，这里直接返回）
            if (!_tickCompleted.WaitOne(0)) return;

            // 报警期间暂停推理
            if (Interlocked.CompareExchange(ref _isPaused, 0, 0) == 1) return;

            // 防重入：若上一帧还在推理，跳过本帧
            if (Interlocked.CompareExchange(ref _isRunning, 1, 0) != 0) return;

            // 标记 Tick 开始执行（Stop 会等待此信号）
            _tickCompleted.Reset();

            MonitorConfig cfg = Volatile.Read(ref _config);
            Bitmap frame    = null;

            try
            {
                var sw = Stopwatch.StartNew();

                // 1. 截图（根据捕获模式选择方式）
                if (cfg.CaptureMode == Models.CaptureMode.WindowHandle
                    && cfg.TargetWindowHandle != IntPtr.Zero)
                {
                    frame = WindowCapturer.CaptureWindow(cfg.TargetWindowHandle, cfg.WindowSubRegion);
                }
                else
                {
                    frame = ScreenCapturer.CaptureRegion(cfg.CaptureRegion);
                }
                long captureMs = sw.ElapsedMilliseconds;

                // 2. 预处理（内部 resize + 转张量）
                sw.Restart();
                float[] tensor = ImagePreprocessor.ToTensor(frame);
                long preprocessMs = sw.ElapsedMilliseconds;

                // 3. 推理
                sw.Restart();
                float[] rawOutput = _engine.Run(tensor, ImagePreprocessor.InputShape);
                long inferMs = sw.ElapsedMilliseconds;

                // 4. 解析（使用实际帧尺寸，避免窗口缩放导致坐标偏移）
                sw.Restart();
                var frameRegion = new Rectangle(0, 0, frame.Width, frame.Height);
                List<Detection> detections = YoloOutputParser.Parse(
                    rawOutput,
                    frameRegion,
                    cfg.ConfidenceThreshold,
                    cfg.IouThreshold,
                    cfg.WatchedClasses);
                long parseMs = sw.ElapsedMilliseconds;

                // 5. 报警评估（使用推理帧绘制检测框，确保坐标匹配）
                var timings = new Dictionary<string, long>
                {
                    ["captureMs"]     = captureMs,
                    ["preprocessMs"]  = preprocessMs,
                    ["inferMs"]       = inferMs,
                    ["parseMs"]       = parseMs,
                };
                _alertService.Evaluate(detections, cfg, timings, frame);

                // 6. 通知 UI
                FrameProcessed?.Invoke(this, new FrameResultEventArgs(
                    detections, (Bitmap)frame.Clone(), inferMs));
            }
            catch (ObjectDisposedException)
            {
                // 服务已停止，忽略
            }
            catch (Exception ex)
            {
                FrameProcessed?.Invoke(this, new FrameResultEventArgs(ex));
            }
            finally
            {
                // 标记 Tick 结束：Stop() 可以安全 Dispose _engine
                _tickCompleted.Set();
                frame?.Dispose();
                Interlocked.Exchange(ref _isRunning, 0);
            }
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            Stop();
        }
    }

    // ── 事件参数 ──────────────────────────────────────────────────────

    public class FrameResultEventArgs : EventArgs
    {
        public List<Detection> Detections  { get; }
        public Bitmap          Frame       { get; }   // 调用方负责 Dispose
        public long            InferenceMs { get; }
        public Exception       Error       { get; }
        public bool            HasError    => Error != null;

        public FrameResultEventArgs(List<Detection> dets, Bitmap frame, long inferMs)
        {
            Detections  = dets;
            Frame       = frame;
            InferenceMs = inferMs;
        }

        public FrameResultEventArgs(Exception error)
        {
            Error = error;
        }
    }
}
