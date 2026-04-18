# VisionGuard

![Version](https://img.shields.io/badge/version-v3.1.1-blue)

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
| `detector/windows/` | C# / .NET 4.7.2 / WinForms | 屏幕捕获 + YOLOv5 推理 + 报警推送 |
| `server/` | Node.js / TypeScript / WebSocket | 中继服务器，桥接检测端与查看端 |
| `receiver/android/` | Kotlin / Jetpack Compose | 接收报警、查看截图、远程控制检测端 |

## 功能特性

- **实时检测** — YOLOv5nu ONNX 推理，支持全屏或指定窗口捕获
- **多设备接入** — 多台 Windows 同时连接，在线优先排序
- **秒级推送** — 报警与截图通过 WebSocket 实时送达 Android
- **远程控制** — 从 Android 端暂停/恢复监控、调整参数
- **按需截图** — Android 可主动请求任意检测端的实时截图

## License

私有项目，保留所有权利。
