# Assets 目录说明

此目录存放运行时依赖的二进制资源文件。

## yolo26n.onnx（默认）

- **模型**：YOLO26n ultralytics 版，COCO 80类
- **用途**：目标检测，轻量高速
- **输入形状**：`[1, 3, 320, 320]` float32，CHW，RGB，归一化到 [0,1]
- **输出形状**：`[1, 300, 6]`（已内置 NMS，6 = [cx, cy, w, h, confidence, class_id]）
- **Opset**：20
- **文件大小**：约 9.4 MB

## yolo26s.onnx（可选）

- **模型**：YOLO26s ultralytics 版，COCO 80类
- **用途**：目标检测，精度更高
- **输入形状**：`[1, 3, 320, 320]` float32，CHW，RGB，归一化到 [0,1]
- **输出形状**：`[1, 300, 6]`（已内置 NMS）
- **Opset**：20
- **文件大小**：约 36.4 MB

> ⚠️ 与旧版 YOLOv5nu 输出格式不同：
> - 旧版：`[1, 84, 2100]` 原始输出，需手动 NMS
> - 新模型：`[1, 300, 6]` 已内置 NMS，直接输出过滤后的检测框

### 重新导出

```bash
pip install ultralytics onnxslim
python -c "
from ultralytics import YOLO
YOLO('yolo26n.pt').export(format='onnx', imgsz=320, simplify=True)
YOLO('yolo26s.pt').export(format='onnx', imgsz=320, simplify=True)
"
```

导出后用 [Netron](https://netron.app) 验证 Input/Output shape 正确。
