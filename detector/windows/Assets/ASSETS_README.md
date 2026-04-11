# Assets 目录说明

此目录存放运行时依赖的二进制资源文件。

## yolov5nu.onnx（必需）

- **模型**：YOLOv5n ultralytics 版（yolov5nu），COCO 80类
- **用途**：目标检测，本项目只过滤 class=0 (person)
- **输入形状**：`[1, 3, 320, 320]` float32，CHW，RGB，归一化到 [0,1]
- **输出形状**：`[1, 84, 2100]`（84 = 4坐标 + 80类概率，无单独 objectness）
- **Opset**：12
- **文件大小**：约 10.2 MB

> ⚠️ 输出格式与旧版 YOLOv5 不同：
> - 旧版：`[1, 2100, 85]`，含 objectness 列（第5列）
> - 本模型：`[1, 84, 2100]`，无 objectness，直接是 `[cx,cy,w,h,cls0..79]`，需转置后解析

### 重新导出（如需更换）

```bash
pip install ultralytics onnxslim
python -c "
from ultralytics import YOLO
YOLO('yolov5nu.pt').export(format='onnx', imgsz=320, opset=12, simplify=True)
"
```

导出后用 [Netron](https://netron.app) 验证 Input/Output shape 正确。
