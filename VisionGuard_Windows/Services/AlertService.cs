// ┌─────────────────────────────────────────────────────────┐
// │ AlertService.cs                                         │
// │ 角色：报警判定+铃声循环管理                              │
// │ 线程：Evaluate 在 ThreadPool 调用，StopAlarm 可任意线程  │
// │ 依赖：无（纯逻辑层）                                    │
// │ 对外 API：Evaluate(), StopAlarm(), IsAlarming           │
// │ 事件：AlertTriggered, AlarmStarted, AlarmStopped        │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Media;
using System.Threading;
using VisionGuard.Models;

namespace VisionGuard.Services
{
    /// <summary>
    /// 接收检测结果，应用冷却逻辑，触发 AlertTriggered 事件。
    /// 支持循环铃声报警：报警期间推理暂停，用户按停止键后恢复。
    /// 线程安全：Evaluate / StopAlarm 可在任意线程调用。
    /// </summary>
    public class AlertService : IDisposable
    {
        // ── 对外事件 ─────────────────────────────────────────────────
        public event EventHandler<AlertEvent> AlertTriggered;
        /// <summary>铃声开始循环时触发（通知外部暂停推理）</summary>
        public event EventHandler AlarmStarted;
        /// <summary>铃声停止后触发（通知外部恢复推理）</summary>
        public event EventHandler AlarmStopped;

        // ── 冷却（全局，以时间为基准）────────────────────────────────
        private DateTime _lastAlertTime = DateTime.MinValue;
        private readonly object _cooldownLock = new object();

        // ── 报警状态（0=静默, 1=报警中）──────────────────────────────
        private int _alarmState;   // Interlocked
        private SoundPlayer _loopPlayer;
        private readonly object _playerLock = new object();

        private bool _disposed;

        // ── 评估入口 ─────────────────────────────────────────────────

        /// <summary>
        /// 评估本帧检测结果，满足冷却条件时触发报警。
        /// 若已处于报警状态则跳过（不重复触发）。
        /// frame 由调用方管理生命周期（此方法内部 Clone）。
        /// </summary>
        public void Evaluate(List<Detection> detections, Bitmap frame, MonitorConfig config)
        {
            if (detections == null || detections.Count == 0) return;

            // 报警中：跳过，避免重复触发
            if (Interlocked.CompareExchange(ref _alarmState, 0, 0) == 1) return;

            DateTime now = DateTime.Now;

            lock (_cooldownLock)
            {
                // 全局冷却：触发报警后，冷却时间内任何类别都不再触发
                if ((now - _lastAlertTime).TotalSeconds < config.AlertCooldownSeconds)
                    return;

                _lastAlertTime = now;
            }

            Bitmap snapshot;
            try { snapshot = (Bitmap)frame.Clone(); }
            catch { snapshot = null; }

            if (config.SaveAlertSnapshot && snapshot != null)
                TrySaveSnapshot(snapshot, now);

            // 触发事件（传递本帧所有检测结果）
            AlertTriggered?.Invoke(this, new AlertEvent(detections.AsReadOnly(), snapshot));

            // 启动循环铃声（需要配置了铃声开关）
            if (config.PlayAlertSound)
                StartLoopAlarm(config.AlertSoundPath);
        }

        // ── 铃声控制 ─────────────────────────────────────────────────

        private void StartLoopAlarm(string wavPath)
        {
            // CAS：从 0 → 1，确保只有一个线程能启动报警
            if (Interlocked.CompareExchange(ref _alarmState, 1, 0) != 0) return;

            lock (_playerLock)
            {
                try
                {
                    if (!string.IsNullOrEmpty(wavPath) && File.Exists(wavPath))
                    {
                        _loopPlayer = new SoundPlayer(wavPath);
                        _loopPlayer.Load();        // 预加载，减少首次播放延迟
                        _loopPlayer.PlayLooping(); // 异步无限循环
                    }
                    else
                    {
                        // 无自定义 WAV：用系统音循环模拟（每 1s 一次）
                        _loopPlayer = null;
                        StartSystemSoundLoop();
                        return;
                    }
                }
                catch
                {
                    _loopPlayer = null;
                }
            }

            AlarmStarted?.Invoke(this, EventArgs.Empty);
        }

        private Thread _systemSoundThread;
        private CancellationTokenSource _systemSoundCts;

        private void StartSystemSoundLoop()
        {
            _systemSoundCts = new CancellationTokenSource();
            var token = _systemSoundCts.Token;

            _systemSoundThread = new Thread(() =>
            {
                while (!token.IsCancellationRequested)
                {
                    try { SystemSounds.Exclamation.Play(); }
                    catch { }
                    // 使用 WaitHandle 等待 1.2s，取消时立即响应（替代 Sleep 轮询）
                    token.WaitHandle.WaitOne(1200);
                }
            })
            { IsBackground = true, Name = "SystemSoundLoop" };
            _systemSoundThread.Start();

            AlarmStarted?.Invoke(this, EventArgs.Empty);
        }

        /// <summary>
        /// 停止循环铃声并将报警状态重置为静默。
        /// 可在任意线程安全调用。
        /// </summary>
        public void StopAlarm()
        {
            // CAS：从 1 → 0，确保只执行一次
            if (Interlocked.CompareExchange(ref _alarmState, 0, 1) != 1) return;

            lock (_playerLock)
            {
                try { _loopPlayer?.Stop(); }
                catch { }
                _loopPlayer?.Dispose();
                _loopPlayer = null;
            }

            // 停止系统音循环线程
            _systemSoundCts?.Cancel();
            _systemSoundCts?.Dispose();
            _systemSoundCts = null;
            _systemSoundThread = null;

            // 关键：把冷却时间戳重置为当前时刻，
            // 确保从"用户主动停止"这一刻起才开始计算冷却，
            // 避免报警期间耗掉了冷却时间导致停止后立即再次触发。
            lock (_cooldownLock)
            {
                _lastAlertTime = DateTime.Now;
            }

            AlarmStopped?.Invoke(this, EventArgs.Empty);
        }

        /// <summary>当前是否处于报警状态</summary>
        public bool IsAlarming => Interlocked.CompareExchange(ref _alarmState, 0, 0) == 1;

        // ── 辅助 ─────────────────────────────────────────────────────

        private static void TrySaveSnapshot(Bitmap bmp, DateTime timestamp)
        {
            try
            {
                string dir = Path.Combine(
                    AppDomain.CurrentDomain.BaseDirectory,
                    "alerts");
                Directory.CreateDirectory(dir);

                string filename = timestamp.ToString("yyyyMMdd_HHmmss_fff") + ".png";
                string path     = Path.Combine(dir, filename);
                bmp.Save(path, System.Drawing.Imaging.ImageFormat.Png);
            }
            catch { }
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            StopAlarm();
        }
    }
}
