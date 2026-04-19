// ┌─────────────────────────────────────────────────────────┐
// │ AlertService.cs                                         │
// │ 角色：报警判定（冷却逻辑）                              │
// │ 依赖：无（纯逻辑层）                                    │
// │ 对外 API：Evaluate(), IsAlarming                        │
// │ 事件：AlertTriggered                                     │
// │ 说明：所有通知逻辑由 Android 端处理                      │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using VisionGuard.Models;
using VisionGuard.Utils;

namespace VisionGuard.Services
{
    /// <summary>
    /// 接收检测结果，应用冷却逻辑，触发 AlertTriggered 事件。
    /// 所有通知逻辑由 Android 端处理。
    /// 线程安全：Evaluate 可在任意线程调用。
    /// </summary>
    public class AlertService : IDisposable
    {
        // ── 对外事件 ─────────────────────────────────────────────────
        public event EventHandler<AlertEvent> AlertTriggered;

        // ── 冷却（全局，以时间为基准）────────────────────────────────
        private DateTime _lastAlertTime = DateTime.MinValue;
        private readonly object _cooldownLock = new object();

        private bool _disposed;

        // ── 评估入口 ─────────────────────────────────────────────────

        /// <summary>
        /// 评估本帧检测结果，满足冷却条件时触发报警。
        /// 使用推理帧副本绘制检测框后保存，确保坐标完全匹配。
        /// </summary>
        public void Evaluate(List<Detection> detections, MonitorConfig config,
                             Dictionary<string, long> timings, Bitmap inferenceFrame)
        {
            if (detections == null || detections.Count == 0) return;

            DateTime now = DateTime.Now;

            lock (_cooldownLock)
            {
                // 全局冷却：触发报警后，冷却时间内不重复触发
                if ((now - _lastAlertTime).TotalSeconds < config.AlertCooldownSeconds)
                    return;

                _lastAlertTime = now;
            }

            var sw = Stopwatch.StartNew();

            // 使用推理帧的副本绘制检测框（确保坐标完全匹配）
            Bitmap snapshot = null;
            try
            {
                snapshot = (Bitmap)inferenceFrame.Clone();

                // 在截图上绘制检测框
                SnapshotRenderer.DrawDetections(snapshot, detections);
            }
            catch
            {
                snapshot?.Dispose();
                snapshot = null;
            }

            // 生成 alertId，用于本地截图文件名和服务端追踪
            string alertId = Guid.NewGuid().ToString();

            if (config.SaveAlertSnapshot && snapshot != null)
                TrySaveSnapshot(snapshot, alertId);

            long alertMs = sw.ElapsedMilliseconds;
            timings["alertMs"] = alertMs;
            timings["totalProcessMs"] = timings["captureMs"] + timings["preprocessMs"]
                                      + timings["inferMs"] + timings["parseMs"] + alertMs;

            // 触发事件（传递本帧所有检测结果）
            AlertTriggered?.Invoke(this, new AlertEvent(alertId, detections.AsReadOnly(), snapshot, timings));
        }

        /// <summary>当前是否处于报警状态（始终 false，保留接口兼容）</summary>
        public bool IsAlarming => false;

        /// <summary>保留空方法（调用方兼容）</summary>
        public void StopAlarm() { }

        // ── 辅助 ─────────────────────────────────────────────────────

        private static void TrySaveSnapshot(Bitmap bmp, string alertId)
        {
            try
            {
                string dir = Path.Combine(
                    AppDomain.CurrentDomain.BaseDirectory,
                    "alerts");
                Directory.CreateDirectory(dir);

                string filename = alertId + ".png";
                string path     = Path.Combine(dir, filename);
                bmp.Save(path, System.Drawing.Imaging.ImageFormat.Png);
            }
            catch { }
        }

        /// <summary>
        /// 根据 alertId 获取本地截图文件路径。
        /// </summary>
        public static string GetSnapshotPath(string alertId)
        {
            string dir = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "alerts");
            return Path.Combine(dir, alertId + ".png");
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
        }
    }
}
