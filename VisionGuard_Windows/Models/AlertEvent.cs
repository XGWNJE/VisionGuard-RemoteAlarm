// ┌─────────────────────────────────────────────────────────┐
// │ AlertEvent.cs                                           │
// │ 角色：报警事件 DTO (时间戳 + 检测列表 + 截图快照)       │
// │ 用途：AlertService.AlertTriggered 事件参数              │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;

namespace VisionGuard.Models
{
    public class AlertEvent : EventArgs
    {
        public DateTime Timestamp { get; }
        public IReadOnlyList<Detection> Detections { get; }
        // 调用方负责 Dispose，AlertService 不持有引用
        public Bitmap Snapshot { get; }

        public AlertEvent(IReadOnlyList<Detection> detections, Bitmap snapshot)
        {
            Timestamp  = DateTime.Now;
            Detections = detections;
            Snapshot   = snapshot;
        }
    }
}
