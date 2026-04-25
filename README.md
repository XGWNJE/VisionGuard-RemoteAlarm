# VisionGuard

![Version](https://img.shields.io/badge/version-v3.5.2-blue)

基于 AI 的实时监控系统。支持 Windows PC 和 Android 手机作为检测端，通过自建服务器将报警实时推送至 Android 接收端。

## 下载

前往 [Releases](https://github.com/XGWNJE/VisionGuard-RemoteAlarm/releases/latest) 获取最新发行版：

| 平台 | 文件 |
|---|---|
| Android 接收端 | `VisionGuard-Receiver-vX.X.X.apk` |
| Android 检测端 | `VisionGuard-Detector-vX.X.X.apk` |
| Windows 检测端 | `VisionGuard-Windows-vX.X.X.zip` |

## 架构

```
detector/windows/    detector/android/         server/                    receiver/android/
  (Win检测端)           (安卓检测端)       ──►  VPS 中继服务器  ──►         (接收端)
  屏幕/窗口捕获         后置摄像头 + ONNX          Node.js / WebSocket          Android 手机
  YOLO26 目标检测       YOLO26 目标检测            HTTP REST + WS               查看报警 / 远程控制
```

| 目录 | 技术栈 | 功能 |
|---|---|---|
| `detector/windows/` | C# / .NET Framework 4.7.2 / WinForms | 屏幕/窗口捕获 + YOLO26 ONNX 推理 + 报警推送 |
| `detector/android/` | Kotlin / Jetpack Compose / CameraX / ONNX Runtime Mobile | 后置摄像头 + YOLO26 推理 + 报警推送 + 本地截图缓存 |
| `server/` | Node.js / TypeScript / Express / ws | 中继服务器：设备管理、报警转发、报警记录持久化、截图按需中继 |
| `receiver/android/` | Kotlin / Jetpack Compose / OkHttp | 接收报警通知、查看截图、历史报警列表、远程控制检测端 |

## 功能特性

### 检测端（Windows / Android）
- **实时检测** — YOLO26 ONNX 推理，支持 n/s 双模型切换
- **多平台支持** — Windows 屏幕捕获 或 Android 后置摄像头
- **本地截图缓存** — 报警截图本地压缩缓存（7天/100MB/2000张），供接收端按需拉取
- **冷却期控制** — 可配置报警推送冷却时间，避免重复通知
- **目标过滤** — 支持按类别过滤（人、车、卡车、客车、自行车、摩托车等）

### 服务端
- **报警记录持久化** — 内存循环缓冲 + 磁盘持久化（`data/alerts.json`），支持历史查询
- **按需截图中继** — 接收端请求截图 → 服务端转发 → 检测端回传 base64
- **轻量 WS Alert** — 报警仅推送 meta 信息，截图按需拉取，降低带宽
- **可选 HTTP 截图上传** — `ENABLE_HTTP_SCREENSHOT_UPLOAD=true` 时兼容旧模式
- **设备管理** — 心跳检测、设备列表广播、三角色支持（windows / android / android-detector）

### 接收端（Android）
- **报警列表** — 实时推送 + 历史记录恢复（最近 7 天），上新下旧排序
- **按需查看截图** — 列表纯文本展示，详情页从检测端实时拉取截图
- **远程控制** — 暂停/恢复监控、调整置信度、冷却时间、目标类别
- **网络自适应** — 网络切换时自动重建连接，退避重连 + 幽灵检测
- **端到端计时** — 完整追踪报警从检测到送达的各环节耗时

## 快速开始

### Server

```bash
cd server
cp .env.example .env
# 编辑 .env 配置 API_KEY、PORT 等
npm install
npm run build
npm start
```

**部署到 VPS**（一键脚本）：
```bash
bash server/deploy.sh        # 仅同步 src/ 并重建
bash server/deploy.sh --full # 同时同步 package.json 并 npm install
```

### Windows 检测端

1. Visual Studio 2022 打开 `detector/windows/VisionGuard.csproj`
2. 确保 `Assets/yolo26n.onnx` 和 `Assets/yolo26s.onnx` 已放置
3. 生成 → 发布

### Android 检测端

```bash
cd detector/android
# 确保 local.properties 中有 SDK 路径
./gradlew assembleRelease
```

### Android 接收端

```bash
cd receiver/android
# 确保 local.properties 中有 SDK 路径
./gradlew assembleRelease
```

## 版本管理

当前版本：见 [VERSION](VERSION)（纯人工备忘，无自动同步）。

版本号规则：
- `feat:` → 次版本 +1
- `fix:` / `refactor:` / `perf:` → 修订号 +1
- `chore:` / `docs:` / `style:` → 不升级版本
- `BREAKING CHANGE` → 主版本 +1

## License

私有项目，保留所有权利。
