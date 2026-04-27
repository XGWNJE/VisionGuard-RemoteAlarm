// ┌─────────────────────────────────────────────────────────┐
// │ AlertService.cs                                         │
// │ 角色：报警判定（冷却逻辑）+ 截图本地缓存管理              │
// │ 依赖：无（纯逻辑层）                                    │
// │ 对外 API：Evaluate(), IsAlarming, GetSnapshotPath()      │
// │ 缓存策略：1GB / 7天 / 5000张上限，LRU 清理               │
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

        // ── 缓存约束 ─────────────────────────────────────────────────
        private const long MAX_CACHE_SIZE_BYTES = 1024L * 1024 * 1024; // 1 GB
        private const int MAX_CACHE_COUNT = 5000;
        private const long MAX_CACHE_AGE_MS = 7L * 24 * 60 * 60 * 1000; // 7 天

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
            long processMs = timings["captureMs"] + timings["preprocessMs"]
                           + timings["inferMs"] + timings["parseMs"] + alertMs;
            // 简化表达：只保留本地计算处理总耗时
            timings.Clear();
            timings["processMs"] = processMs;

            // 触发事件（传递本帧所有检测结果）
            AlertTriggered?.Invoke(this, new AlertEvent(alertId, detections.AsReadOnly(), snapshot, timings));
        }

        /// <summary>当前是否处于报警状态（始终 false，保留接口兼容）</summary>
        public bool IsAlarming => false;

        /// <summary>保留空方法（调用方兼容）</summary>
        public void StopAlarm() { }

        // ── 截图缓存管理 ─────────────────────────────────────────────

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

                // 保存后执行缓存约束清理
                CleanupCache(dir);
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

        /// <summary>
        /// 清理截图缓存：满足 1GB / 7天 / 5000张 约束（LRU）。
        /// </summary>
        private static void CleanupCache(string dir)
        {
            try
            {
                if (!Directory.Exists(dir)) return;

                var files = new DirectoryInfo(dir)
                    .GetFiles("*.png")
                    .Where(f => f.Length > 0)
                    .ToList();

                if (files.Count == 0) return;

                var now = DateTime.Now;
                long totalSize = files.Sum(f => f.Length);
                int removed = 0;

                // 1. 按时间清理：删除超过 7 天的文件
                var expired = files.Where(f => (now - f.LastWriteTime).TotalMilliseconds > MAX_CACHE_AGE_MS).ToList();
                foreach (var f in expired)
                {
                    try { f.Delete(); removed++; } catch { }
                }
                if (removed > 0)
                {
                    files = files.Except(expired.Where(f => !f.Exists)).ToList();
                    totalSize = files.Sum(f => f.Length);
                }

                // 2. 按条数清理：超出 5000 条时删除最旧的
                if (files.Count > MAX_CACHE_COUNT)
                {
                    var toDelete = files.OrderBy(f => f.LastWriteTime).Take(files.Count - MAX_CACHE_COUNT);
                    foreach (var f in toDelete)
                    {
                        try { f.Delete(); removed++; totalSize -= f.Length; } catch { }
                    }
                    files = files.Except(toDelete.Where(f => !f.Exists)).ToList();
                }

                // 3. 按大小清理：超出 1GB 时删除最旧的
                if (totalSize > MAX_CACHE_SIZE_BYTES)
                {
                    var sorted = files.OrderBy(f => f.LastWriteTime).ToList();
                    foreach (var f in sorted)
                    {
                        if (totalSize <= MAX_CACHE_SIZE_BYTES) break;
                        try { f.Delete(); removed++; totalSize -= f.Length; } catch { }
                    }
                }

                if (removed > 0)
                {
                    LogManager.StaticInfo($"[AlertService] 截图缓存清理完成: 删除 {removed} 个文件");
                }
            }
            catch { /* 清理失败不阻塞报警流程 */ }
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
        }
    }
}
