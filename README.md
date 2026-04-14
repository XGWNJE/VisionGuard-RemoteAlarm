# VisionGuard

![Version](https://img.shields.io/badge/version-v3.1.0-blue)

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

支持规模：最多 **20 台**检测端同时接入，**50–100 台** Android 查看端，状态变化推送延迟 **< 2 秒**。

## 目录结构

| 目录 | 技术栈 | 功能 |
|---|---|---|
| `detector/windows/` | C# / .NET 4.7.2 / WinForms | 屏幕捕获 + YOLOv5 推理 + 报警推送 |
| `server/` | Node.js / TypeScript / WebSocket | 中继服务器，桥接检测端与查看端 |
| `receiver/android/` | Kotlin / Jetpack Compose | 接收报警、查看截图、远程控制检测端 |

## 功能特性

- **实时检测** — YOLOv5nu ONNX 推理，支持全屏或指定窗口捕获
- **多设备接入** — 多台 Windows 同时连接，按自定义设备名区分；在线优先排序
- **秒级推送** — 报警与截图通过 WebSocket 实时送达 Android，延迟 < 2s
- **远程控制** — 从 Android 端暂停/恢复监控、静默正在响的报警
- **按需截图** — Android 可主动请求任意已连接检测端的实时截图，支持多端并发
- **断线感知** — 连接断开时设备列表立即清空，重连后自动恢复
- **通知分组** — 多台设备同时报警时通知自动合并，不刷屏
- **亮屏报警** — 报警时自动点亮屏幕并全屏展示通知，锁屏状态下也不遗漏
- **呼吸灯提醒** — 支持 LED 呼吸灯闪烁，静默环境下仍可感知报警

## 快速开始

### 服务器

```bash
cd server
npm install
npm run build
node dist/index.js
```

部署至 VPS 请参考 [`server/DEPLOY.md`](server/DEPLOY.md)。

### Windows 检测端

1. 用 Visual Studio 打开 `detector/windows/VisionGuard.slnx`
2. 选择 `Release | x64` 配置，生成
3. 运行 `bin/x64/Release/VisionGuard.exe`
4. 在设置页填写服务器地址和 API Key

### Android 查看端

用 Android Studio 打开 `receiver/android/`，连接手机后直接运行，或安装 `app/release/` 下的 APK。

## 环境要求

| 端 | 要求 |
|---|---|
| **检测端（Windows）** | .NET Framework 4.7.2，Windows 7 SP1 / 10 / 11，x64 |
| **服务器** | Node.js 18+ |
| **查看端（Android）** | Android 9.0（API 28）及以上 |

## 扩展路线

当前架构已为以下扩展预留基础：

- `detector/android/` — Android 推理端（协议层 `clientType` 字段已预留）
- `receiver/web/` — Web 查看端
- 服务器广播防抖（50ms）+ 幽灵连接清理（75s）已验证支持 20 台检测端 + 100 台查看端

## License

私有项目，保留所有权利。
