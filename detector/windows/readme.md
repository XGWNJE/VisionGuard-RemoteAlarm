# VisionGuard

**屏幕区域 / 窗口人员检测 + 循环报警**，专为低配 Windows 设计，开箱即用。

![Version](https://img.shields.io/badge/v2.0.2-0078D4?style=flat-square)
![Platform](https://img.shields.io/badge/Windows%207%2B%20x64-0078D4?style=flat-square&logo=windows&logoColor=white)
![.NET](https://img.shields.io/badge/.NET%204.7.2-512BD4?style=flat-square&logo=dotnet&logoColor=white)
![Model](https://img.shields.io/badge/YOLOv5nu%20320×320-FF6F00?style=flat-square)
![License](https://img.shields.io/badge/MIT-22C55E?style=flat-square)

![界面截图](https://github.com/XGWNJE/VisionGuard/raw/main/Assets/screenshot.png)

---

框选屏幕区域或选择目标窗口 → YOLOv5 CPU 推理 → 检测到目标时循环播放报警音，按 `Space` 停止。全程无需 GPU。

## 快速开始

从 [Releases](../../releases) 下载 `VisionGuard_v2.0.2_x64.zip`，解压直接运行 `VisionGuard.exe`。

1. 点击 **「选择窗口」** 选取目标进程，或点击 **「拖拽选区」** 框选屏幕区域
2. 在「检测参数」卡片调整置信度阈值、冷却时间、报警铃声
3. 在「监控对象」中勾选要检测的 COCO 类别（中英文双语，支持搜索）
4. 点击 **「▶ 开始」** 启动监控
5. 检测到目标 → 铃声循环响起 → 按 `Space` 停止并恢复推理

## v2.0.2 更新

### 捕获方式升级
| 旧版 | v2.0 |
|---|---|
| 仅支持屏幕区域截图（BitBlt） | **新增窗口捕获模式**：选择任意可见窗口，使用 `PrintWindow` API 捕获，支持被遮挡窗口 |
| 四个数字输入框指定坐标 | **拖拽选区**：全屏半透明遮罩 + 鼠标拖拽，所见即所得 |

### UI 重构
- 左侧菜单改为**卡片式滚动布局**（捕获区域 / 检测参数 / 性能参数 / 监控对象）
- 右侧分为**上 70% 预览面板 + 下 30% 日志面板**，支持拖动分割线
- 数字输入从 NumericUpDown 改为 **TextBox + 失焦验证**，风格统一

### 监控对象选择
- 原来的文本输入框改为 **COCO 80 类双语 CheckedListBox**（中文 + 英文对照）
- 顶部搜索框实时过滤，快速定位类别
- 选中数量实时显示

### High DPI 支持（Win10/11 Per-Monitor-v2）
- 通过 `app.manifest` + `App.config` 声明 **PerMonitorV2** DPI 感知
- 所有控件尺寸基于运行时 `Font.Height` 动态计算，适配 100% / 125% / 150% / 175% 任意缩放比例
- UI 构建推迟到 `OnShown`，确保 DPI 字体高度完全生效后再布局，彻底消除控件重叠/截断问题

## 功能一览

| | |
|---|---|
| **窗口捕获** | 选择任意可见窗口，`PrintWindow` 捕获，支持被遮挡窗口；可进一步在窗口内拖拽子区域 |
| **区域截图** | 拖拽框选任意屏幕区域，实时截图推理 |
| **多类别监控** | COCO 80 类中英双语复选，支持搜索过滤（person / car / dog 等） |
| **循环报警** | 触发后无限循环播放 WAV / 系统音，`Space` 键停止 |
| **推理暂停** | 报警期间自动暂停推理，降低 CPU 占用 |
| **冷却机制** | 停止后重置冷却计时，防止立即重触发 |
| **快照保存** | 报警瞬间自动截图至 `alerts\` 目录 |
| **托盘运行** | 最小化后系统托盘常驻，双击唤起 |
| **参数持久化** | 所有设置（含窗口尺寸、分割线位置）自动保存 |
| **High DPI** | Per-Monitor-v2，任意缩放比例下 UI 正常显示 |

## 参数说明

**持久化位置**：`settings.ini` 与 `alerts\` 目录均在 EXE 同级目录下。

**性能**：FPS（1–5，默认 2）、推理线程数（1–8，默认 2）

> 低配机器推荐保持 FPS = 2，单帧推理约 300–800 ms（纯 CPU）。

## 从源码构建

```
git clone <repo>
# 将 yolo26n.onnx / yolo26s.onnx 放入 Assets/（导出方法见 Assets/ASSETS_README.md）
# 用 Visual Studio 2022 打开 VisionGuard.csproj，还原 NuGet，生成即可
```

**环境要求**：Visual Studio 2019+，.NET Framework 4.7.2 SDK

## 技术栈

| | |
|---|---|
| UI | WinForms .NET 4.7.2，Win11 Fluent 暗色，纯 GDI+ Owner-Draw，Per-Monitor-v2 High DPI |
| 捕获 | `BitBlt`（屏幕区域）+ `PrintWindow / DwmGetWindowAttribute`（窗口捕获） |
| 推理 | ONNX Runtime Managed 1.16.3 + native 1.1.0 |
| 模型 | YOLOv5nu，ONNX 格式，输入 320×320，COCO 80 类 |
| 音频 | `SoundPlayer` WAV 循环 |
| 键盘 | `SetWindowsHookEx WH_KEYBOARD_LL` 全局钩子 |

## 项目结构

```
VisionGuard/
├── Assets/          # 模型 (.onnx)、图标、COCO 类别列表
├── Capture/         # 屏幕截图、窗口枚举、PrintWindow 捕获、键盘钩子
├── Data/            # COCO 类别中英文映射表
├── Inference/       # ONNX 推理 + 图像预处理 + NMS
├── Models/          # 数据结构（配置、检测结果、报警事件）
├── Services/        # 监控循环 + 报警状态机
├── UI/              # 自绘控件（卡片、滑块、按钮、日志列表、类别选择器、窗口选择器）
├── Utils/           # 日志管理 + INI 持久化
└── Form1.cs         # 主界面（纯代码，不依赖 Designer）
```

## 路线图

- [ ] PushDeer / Bark 手机推送通知
- [x] **多目标类别选择**（80 类 COCO，中英双语）
- [x] **窗口捕获模式**（PrintWindow）
- [x] **High DPI 支持**（Per-Monitor-v2）
- [ ] HTTP Webhook 对接自有平台
- [ ] 内置历史快照查看器

---

MIT © [xgwnje](https://github.com/xgwnje)
