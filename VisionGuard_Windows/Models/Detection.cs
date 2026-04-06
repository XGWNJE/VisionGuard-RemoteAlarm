// ┌─────────────────────────────────────────────────────────┐
// │ Detection.cs                                            │
// │ 角色：单个检测结果 DTO (类ID/标签/置信度/包围框)        │
// │ 用途：YoloOutputParser 输出 → MonitorService → UI 绘制  │
// └─────────────────────────────────────────────────────────┘
using System.Drawing;

namespace VisionGuard.Models
{
    public class Detection
    {
        public int ClassId { get; set; }
        public string Label { get; set; }
        public float Confidence { get; set; }
        // 相对于原始捕获区域的像素坐标
        public RectangleF BoundingBox { get; set; }
    }
}
