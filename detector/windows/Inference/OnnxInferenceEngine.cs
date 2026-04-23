// ┌─────────────────────────────────────────────────────────┐
// │ OnnxInferenceEngine.cs                                  │
// │ 角色：封装 ONNX Runtime 推理会话生命周期                 │
// │ 线程：Run() 线程安全（InferenceSession 内部同步）        │
// │ 依赖：Microsoft.ML.OnnxRuntime NuGet                    │
// │ 对外 API：Run(tensor, shape), Dispose()                 │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;

namespace VisionGuard.Inference
{
    /// <summary>
    /// 封装 ONNX Runtime InferenceSession 生命周期。
    /// 线程安全：每次 Run 是无状态的，但 InferenceSession 本身线程安全。
    /// </summary>
    public sealed class OnnxInferenceEngine : IDisposable
    {
        private InferenceSession _session;
        private readonly string  _inputName;
        private readonly string  _outputName;
        private bool _disposed;

        public OnnxInferenceEngine(string modelPath, int intraOpNumThreads = 2)
        {
            var opts = new SessionOptions();
            opts.IntraOpNumThreads     = intraOpNumThreads;
            opts.InterOpNumThreads     = 1;
            opts.GraphOptimizationLevel = GraphOptimizationLevel.ORT_ENABLE_ALL;
            opts.ExecutionMode          = ExecutionMode.ORT_SEQUENTIAL;

            _session    = new InferenceSession(modelPath, opts);
            _inputName  = _session.InputMetadata.Keys.First();
            _outputName = _session.OutputMetadata.Keys.First();
        }

        /// <summary>
        /// 运行推理，返回原始 float 数组（output0 展平）。
        /// </summary>
        public float[] Run(float[] inputData, int[] shape)
        {
            if (_disposed) throw new ObjectDisposedException(nameof(OnnxInferenceEngine));

            // DenseTensor<T>(Memory<T>, ReadOnlySpan<int>) — shape 必须是 int[]，不是 long[]
            var tensor = new DenseTensor<float>(inputData, shape);

            var inputs = new List<NamedOnnxValue>
            {
                NamedOnnxValue.CreateFromTensor(_inputName, tensor)
            };

            using (IDisposableReadOnlyCollection<DisposableNamedOnnxValue> outputs = _session.Run(inputs))
            {
                // output0 形状 [1, 300, 6]（YOLO26 已内置 NMS），展平后直接返回
                // 1.1.0 的 IDisposableReadOnlyCollection 无索引器，用 First()
                var outTensor = outputs.First().AsTensor<float>();
                return outTensor.ToArray();
            }
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            _session?.Dispose();
            _session = null;
        }
    }
}
