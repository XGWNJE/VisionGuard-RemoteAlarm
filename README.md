# VisionGuard

![Version](https://img.shields.io/badge/version-v3.3.0-blue)

基于 AI 的实时监控系统，支持多台 Windows 设备同时接入，通过自建服务器将报警和截图实时推送至 Android 手机。

## 下载

前往 [Releases](https://github.com/XGWNJE/VisionGuard-RemoteAlarm/releases/latest) 获取最新发行版：

| 平台 | 文件 |
|---|---|
| Android | `VisionGuard-Android-vX.X.X.apk` |
| Windows | `VisionGuard-Windows-vX.X.X.zip` |

## 架构

```
detector/windows/          server/                    receiver/android/
  (推理检测端)        ──►  VPS 中继服务器  ──►         (通知接收端)
  Windows PC(s)           Node.js / WebSocket          Android 手机
  YOLOv5 目标检测          HTTP REST + WS               查看报警 / 远程控制
```

| 目录 | 技术栈 | 功能 |
|---|---|---|
| `detector/windows/` | C# / .NET Framework 4.7.2 / WinForms | 屏幕捕获 + YOLOv5 ONNX 推理 + 报警推送 |
| `server/` | Node.js / TypeScript / Express / WebSocket | 中继服务器，桥接检测端与接收端 |
| `receiver/android/` | Kotlin / Jetpack Compose / OkHttp | 接收报警、查看截图、远程控制检测端 |
| `detector/android/` | Kotlin / Jetpack Compose (脚手架) | 备用推理端（当前为空壳，预留扩展） |

## 功能特性

- **实时检测** — YOLOv5nu ONNX 推理，支持全屏或指定窗口捕获
- **多设备接入** — 多台 Windows 同时连接，在线优先排序
- **秒级推送** — 报警与截图通过 WebSocket 实时送达 Android
- **远程控制** — 从 Android 端暂停/恢复监控、调整参数（置信度、冷却时间、目标类别）
- **按需截图** — Android 可主动请求任意检测端的实时截图
- **网络自适应** — Android 端网络切换时自动重建连接，退避重连策略
- **端到端计时** — 完整追踪报警从检测到送达的各环节耗时

## 快速开始

### Server

```bash
cd server
cp .env.example .env
# 编辑 .env 配置 API_KEY
npm install
npm run build
npm start
```

### Windows 检测端

1. Visual Studio 2022 打开 `detector/windows/VisionGuard.csproj`
2. 确保 `Assets/yolov5nu.onnx` 已放置（~10MB）
3. 生成 → 发布

### Android 接收端

```bash
cd receiver/android
# 确保 local.properties 中有 SDK 路径
./gradlew assembleRelease
```

## 版本管理

当前版本：见 [VERSION](VERSION)（纯人工备忘）。

## License

私有项目，保留所有权利。
