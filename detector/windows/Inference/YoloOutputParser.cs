// ┌─────────────────────────────────────────────────────────┐
// │ YoloOutputParser.cs                                     │
// │ 角色：解析 YOLO26 ONNX 输出张量为 Detection 列表        │
// │ 线程：在 MonitorService 的 ThreadPool 回调中调用         │
// │ 依赖：CocoClassMap (类名), ImagePreprocessor (ModelSize) │
// │ 对外 API：Parse() — 静态方法                            │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;
using VisionGuard.Data;
using VisionGuard.Models;

namespace VisionGuard.Inference
{
    /// <summary>
    /// 解析 YOLO26 ONNX 输出张量为 Detection 列表。
    ///
    /// 输出格式 [1, 300, 6]（Ultralytics 默认导出，已内置 NMS）：
    ///   - 300 = 最多保留 300 个检测框
    ///   - 6   = [x1, y1, x2, y2, confidence, class_id]（左上角 + 右下角）
    ///   - 坐标已是绝对像素值（相对 320×320），无需乘以 anchors
    ///   - 模型内部已完成 NMS，无需再次 NMS
    ///
    /// 前5置信度顺序匹配：所有候选按置信度降序，取前5名，
    ///   若配置了 WatchedClasses 则在这5个中匹配，否则直接取前5。
    /// </summary>
    public static class YoloOutputParser
    {
        // ModelSize 统一引用 ImagePreprocessor 定义的常量
        private static int ModelSize => ImagePreprocessor.ModelInputSize;

        // COCO 80 类名：引用 CocoClassMap 消除重复
        private static List<string> CocoLabels => CocoClassMap.EnglishNames;

        /// <summary>
        /// 解析 ONNX 原始输出，返回过滤后的 Detection 列表。
        /// </summary>
        /// <param name="rawOutput">Run() 返回的展平 float[]，长度 = 300 * 6 = 1800</param>
        /// <param name="captureRegion">原始捕获区域（用于将坐标映射回屏幕）</param>
        /// <param name="confThreshold">置信度阈值</param>
        /// <param name="iouThreshold">NMS IoU 阈值（保留参数但忽略，模型已内置 NMS）</param>
        /// <param name="watchedClasses">只保留这些类名（null 或空集合 = 全部）</param>
        public static List<Detection> Parse(
            float[]         rawOutput,
            Rectangle       captureRegion,
            float           confThreshold,
            float           iouThreshold,  // 保留以兼容旧调用，模型已内置 NMS
            HashSet<string> watchedClasses)
        {
            // rawOutput 展平自 [1, 300, 6]
            const int numDetections = 300;
            const int valuesPerBox  = 6;   // [cx, cy, w, h, conf, class_id]

            float scaleX = captureRegion.Width  / (float)ModelSize;
            float scaleY = captureRegion.Height / (float)ModelSize;

            var allCandidates = new List<Detection>();

            for (int i = 0; i < numDetections; i++)
            {
                int idx = i * valuesPerBox;
                float x1   = rawOutput[idx + 0];
                float y1   = rawOutput[idx + 1];
                float x2   = rawOutput[idx + 2];
                float y2   = rawOutput[idx + 3];
                float conf = rawOutput[idx + 4];
                int   cls  = (int)rawOutput[idx + 5];

                // 置信度过滤
                if (conf < confThreshold) continue;

                // class_id 合法性检查
                if (cls < 0 || cls >= CocoLabels.Count) continue;

                string label = CocoLabels[cls];

                // watchedClasses 空 = 全部；非空则只保留匹配项
                if (watchedClasses != null && watchedClasses.Count > 0
                    && !watchedClasses.Contains(label)) continue;

                // 转换为捕获区域内的像素坐标（左上角 + 宽高）
                // yolo26 ONNX 输出格式为 [x1, y1, x2, y2, conf, class_id]
                float x = x1 * scaleX;
                float y = y1 * scaleY;
                float w = (x2 - x1) * scaleX;
                float h = (y2 - y1) * scaleY;

                allCandidates.Add(new Detection
                {
                    ClassId     = cls,
                    Label       = label,
                    Confidence  = conf,
                    BoundingBox = new RectangleF(x, y, w, h)
                });
            }

            // 按置信度降序，取前5名
            allCandidates.Sort((a, b) => b.Confidence.CompareTo(a.Confidence));
            return allCandidates.Count > 5
                ? allCandidates.GetRange(0, 5)
                : allCandidates;
        }
    }
}
