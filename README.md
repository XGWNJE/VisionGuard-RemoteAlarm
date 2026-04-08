# VisionGuard

基于 AI 的实时监控系统，支持多台 Windows 设备同时接入，通过自建服务器将报警和截图实时推送至 Android 手机。

## 架构

```
Windows PC(s)  ──────►  VPS 中继服务器  ──────►  Android 手机
  (目标检测)            (Node.js / WebSocket)       (接收报警)
```

## 模块

| 目录 | 技术栈 | 功能 |
|---|---|---|
| `VisionGuard_Windows` | C# / .NET 4.7.2 / WinForms | 屏幕捕获 + YOLOv5 推理 + 报警推送 |
| `VisionGuard_Server` | Node.js / TypeScript / WebSocket | 中继服务器，桥接 Windows 与 Android |
| `VisionGuard_Android` | Kotlin / Jetpack Compose | 接收报警、查看截图、远程控制 Windows |

## 功能特性

- **实时检测** — YOLOv5nu ONNX 推理，支持全屏或指定窗口捕获
- **多设备接入** — 多台 Windows 同时连接，按自定义设备名区分
- **秒级推送** — 报警与截图通过 WebSocket 实时送达 Android
- **远程控制** — 从 Android 端暂停/恢复监控、静默正在响的报警
- **按需截图** — Android 可主动请求任意已连接 Windows 设备的实时截图

## 快速开始

### 服务器

```bash
cd VisionGuard_Server
npm install
npm run build
node dist/index.js
```

### Windows 客户端

1. 用 Visual Studio 打开 `VisionGuard_Windows/VisionGuard.sln`
2. 选择 `Release | x64` 配置，生成
3. 运行 `bin/x64/Release/VisionGuard.exe`
4. 在设置页填写服务器地址和设备名称

### Android

用 Android Studio 打开 `VisionGuard_Android`，连接手机后直接运行。

## 环境要求

- **Windows**：.NET Framework 4.7.2，Windows 7 SP1 / 10 / 11，x64
- **服务器**：Node.js 18+
- **Android**：Android 8.0（API 26）及以上

## License

私有项目，保留所有权利。
