// ┌─────────────────────────────────────────────────────────┐
// │ YoloOutputParser.cs                                     │
// │ 角色：解析 YOLOv5nu ONNX 输出张量为 Detection 列表      │
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
    /// 解析 YOLOv5nu ONNX 输出张量为 Detection 列表。
    ///
    /// 输出格式 [1, 84, 2100]（与旧版 YOLOv5 不同）：
    ///   - 84 = 4(xywh) + 80(class scores)，无 objectness 列
    ///   - 2100 = 40x40 + 20x20 + 10x10 anchor grid（320px 输入）
    ///   - 坐标已是绝对像素值（相对 320x320），无需乘以 anchors
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
        /// 解析 ONNX 原始输出，返回过滤后并经 NMS 的 Detection 列表。
        /// </summary>
        /// <param name="rawOutput">Run() 返回的展平 float[]，长度 = 84 * 2100</param>
        /// <param name="captureRegion">原始捕获区域（用于将坐标映射回屏幕）</param>
        /// <param name="confThreshold">置信度阈值</param>
        /// <param name="iouThreshold">NMS IoU 阈值</param>
        /// <param name="watchedClasses">只保留这些类名（null 或空集合 = 全部）</param>
        public static List<Detection> Parse(
            float[]         rawOutput,
            Rectangle       captureRegion,
            float           confThreshold,
            float           iouThreshold,
            HashSet<string> watchedClasses)
        {
            // rawOutput 展平自 [1, 84, 2100]
            // 索引: rawOutput[channel * 2100 + anchor]
            const int numAnchors  = 2100;
            const int numChannels = 84; // 4 + 80

            float scaleX = captureRegion.Width  / (float)ModelSize;
            float scaleY = captureRegion.Height / (float)ModelSize;

            var allCandidates = new List<Detection>();

            for (int a = 0; a < numAnchors; a++)
            {
                // 找最高分类分数
                int   bestClass = -1;
                float bestScore = 0f;
                for (int c = 4; c < numChannels; c++)
                {
                    float score = rawOutput[c * numAnchors + a];
                    if (score > bestScore)
                    {
                        bestScore = score;
                        bestClass = c - 4;
                    }
                }

                if (bestScore < confThreshold) continue;

                string label = bestClass < CocoLabels.Count ? CocoLabels[bestClass] : bestClass.ToString();

                // watchedClasses 空 = 全部；非空则只保留匹配项
                if (watchedClasses != null && watchedClasses.Count > 0
                    && !watchedClasses.Contains(label)) continue;

                float cx = rawOutput[0 * numAnchors + a];
                float cy = rawOutput[1 * numAnchors + a];
                float bw = rawOutput[2 * numAnchors + a];
                float bh = rawOutput[3 * numAnchors + a];

                // 转换为捕获区域内的像素坐标
                float x = (cx - bw / 2f) * scaleX;
                float y = (cy - bh / 2f) * scaleY;
                float w = bw * scaleX;
                float h = bh * scaleY;

                allCandidates.Add(new Detection
                {
                    ClassId     = bestClass,
                    Label       = label,
                    Confidence  = bestScore,
                    BoundingBox = new RectangleF(x, y, w, h)
                });
            }

            // 前5置信度顺序匹配：先按置信度降序，取前5名，再 NMS
            allCandidates.Sort((a, b) => b.Confidence.CompareTo(a.Confidence));
            var top5 = allCandidates.Count > 5 ? allCandidates.GetRange(0, 5) : allCandidates;

            return NMS(top5, iouThreshold);
        }

        // ── NMS ─────────────────────────────────────────────────────

        private static List<Detection> NMS(List<Detection> dets, float iouThreshold)
        {
            // 按置信度降序
            dets.Sort((a, b) => b.Confidence.CompareTo(a.Confidence));

            var kept    = new List<Detection>();
            var removed = new bool[dets.Count];

            for (int i = 0; i < dets.Count; i++)
            {
                if (removed[i]) continue;
                kept.Add(dets[i]);
                for (int j = i + 1; j < dets.Count; j++)
                {
                    if (removed[j]) continue;
                    if (dets[i].ClassId == dets[j].ClassId
                        && IoU(dets[i].BoundingBox, dets[j].BoundingBox) > iouThreshold)
                    {
                        removed[j] = true;
                    }
                }
            }
            return kept;
        }

        private static float IoU(RectangleF a, RectangleF b)
        {
            float interX = Math.Max(a.Left, b.Left);
            float interY = Math.Max(a.Top,  b.Top);
            float interW = Math.Min(a.Right, b.Right) - interX;
            float interH = Math.Min(a.Bottom, b.Bottom) - interY;

            if (interW <= 0 || interH <= 0) return 0f;

            float inter = interW * interH;
            float union = a.Width * a.Height + b.Width * b.Height - inter;
            return union <= 0 ? 0f : inter / union;
        }
    }
}
