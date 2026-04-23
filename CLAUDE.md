# VisionGuard — Claude Code 项目指南

> 基于 AI 的实时监控系统。Windows 检测端通过 YOLO26 推理，经自建服务器实时推送报警至 Android 手机。

## 项目概览

```
detector/windows/          server/                    receiver/android/
  (推理检测端)        ──►  VPS 中继服务器  ──►         (通知接收端)
  Windows PC(s)           Node.js / WebSocket          Android 手机
  YOLO26 目标检测          HTTP REST + WS               查看报警 / 远程控制
```

| 目录 | 技术栈 | 功能 |
|---|---|---|
| `detector/windows/` | C# / .NET Framework 4.7.2 / WinForms | 屏幕/窗口捕获 + YOLO26 ONNX 推理 + 报警推送 |
| `server/` | Node.js / TypeScript / Express / ws | 中继服务器：设备管理、报警转发、截图存储 |
| `receiver/android/` | Kotlin / Jetpack Compose / OkHttp | 接收报警通知、查看截图、远程控制检测端 |
| `detector/android/` | Kotlin / Jetpack Compose / CameraX / ONNX Runtime Mobile | Android 检测端（后置摄像头 + YOLO26 推理 + 报警推送） |

## 版本管理

- **当前版本**：`3.3.1`（见根目录 [VERSION](VERSION)）
- **三端版本必须严格同步**：Server `package.json`、Android `build.gradle.kts`、Windows `AssemblyInfo.cs` / `.csproj`
- **规则文件**：[VERSION_RULES.md](VERSION_RULES.md)
- **Commit 前缀规范**：
  - `feat:` → 次版本 +1（3.0.0 → 3.1.0）
  - `fix:` / `refactor:` / `perf:` → 修订号 +1（3.0.0 → 3.0.1）
  - `chore:` / `docs:` / `style:` → 不升级版本
  - `BREAKING CHANGE` → 主版本 +1

## 各端详解

### detector/windows — Windows 推理检测端

**核心模块**：
- `Capture/` — 屏幕捕获、窗口枚举、子区域选择
- `Inference/` — ONNX Runtime 推理引擎、YOLO 输出解析、图像预处理
- `Services/` — 监控服务（定时推理循环）、报警服务（声光通知）、服务器推送服务（WS 连接）
- `UI/` — 自定义 WinForms 控件（暗色主题、圆角按钮、检测框覆盖层）
- `Utils/` — NTP 时钟同步、设置持久化、截图渲染器

**关键文件**：
- [Form1.cs](detector/windows/Form1.cs) — 主窗体：字段、构造、配置构建、状态控制
- [Form1.Monitor.cs](detector/windows/Form1.Monitor.cs) — 监控控制：区域选择、启停、回调
- [Form1.Server.cs](detector/windows/Form1.Server.cs) — 服务器连接、设置持久化、远程配置
- [Form1.UI.cs](detector/windows/Form1.UI.cs) — UI 构建：主布局、页面切换
- [OnnxInferenceEngine.cs](detector/windows/Inference/OnnxInferenceEngine.cs) — ONNX Runtime 封装
- [YoloOutputParser.cs](detector/windows/Inference/YoloOutputParser.cs) — YOLO26 输出解析（格式 `[1, 300, 6]`，已内置 NMS，6 = [x1, y1, x2, y2, conf, class_id]）

**依赖**：Microsoft.ML.OnnxRuntime 1.17+、websocket-sharp
**模型**：`Assets/yolo26n.onnx`（~9.4MB，轻量）/ `Assets/yolo26s.onnx`（~37MB，精准），COCO 80 类
**模型选择**：参数页下拉框切换 yolo26n / yolo26s
**目标框架**：.NET Framework 4.7.2，x64

### server — 中继服务器

**技术栈**：Node.js 20+、TypeScript 6、Express 5、ws 8

**核心模块**：
- `routes/alert.ts` — POST `/api/alert` 接收报警上传（multipart/form-data）
- `routes/screenshot.ts` — GET `/screenshots/:id.png` 提供截图下载
- `services/ConnectionManager.ts` — WebSocket 连接管理：认证、心跳、设备列表广播、命令中继
- `services/AlertStore.ts` — 内存报警记录存储（按设备循环缓冲，默认 200 条/设备）
- `services/ScreenshotCleanup.ts` — 截图过期清理（默认 TTL 72 小时）

**WebSocket 消息类型**：
| 方向 | 类型 | 说明 |
|---|---|---|
| → Server | `auth` | 认证（含 role: windows/android） |
| → Server | `heartbeat` | Windows 心跳（15s） |
| → Server | `heartbeat-android` | Android 心跳（20s） |
| → Server | `alert` | Windows 上报报警 |
| → Server | `command` | Android 下发控制命令 |
| → Server | `set-config` | Android 调整检测参数 |
| → Server | `request-sccreenshot` | Android 请求截图 |
| → Server | `screenshot-data` | Windows 回传截图（base64 JPEG） |
| ← Server | `device-list` | 设备列表广播 |
| ← Server | `alert` | 报警推送 |
| ← Server | `command-ack` | 命令执行结果 |
| ← Server | `kicked` | 重复连接被踢 |

**部署**：VPS `66.154.112.91:3000`，systemd 服务 `visionguard`
**部署脚本**：[server/deploy.sh](server/deploy.sh)

### receiver/android — Android 接收端

**技术栈**：Kotlin 2.3.20、Jetpack Compose BOM 2026.03、AGP 9.1、minSdk 28 / targetSdk 36

**架构**：MVVM + 前台 Service + 单状态源事件循环

**核心模块**：
- `data/remote/WebSocketClient.kt` — OkHttp WebSocket 封装：退避重连、幽灵检测、Session 隔离
- `service/AlertForegroundService.kt` — 前台服务：持有 WS 连接、接收报警、发送系统通知
- `ui/screen/` — AlertListScreen、AlertDetailScreen、DeviceListScreen
- `ui/viewmodel/` — AlertViewModel、DeviceViewModel
- `data/repository/SettingsRepository.kt` — DataStore 偏好设置持久化

**关键常量**：[AppConstants.kt](receiver/android/app/src/main/java/com/xgwnje/visionguard_android/AppConstants.kt)
- `SERVER_URL = "http://66.154.112.91:3000"`
- `API_KEY = "XG-VisionGuard-2024"`

### detector/android — Android 检测端

**技术栈**：Kotlin 2.3.20、Jetpack Compose BOM 2026.03、CameraX 1.4.2、ONNX Runtime Mobile 1.20.0、AGP 9.1、minSdk 28 / targetSdk 36

**架构**：前台 Service（`foregroundServiceType="camera"`）+ CameraX `ImageAnalysis`（无 Preview）+ ONNX Runtime Mobile 纯 CPU 推理

**核心模块**：
- `inference/OnnxInferenceEngine.kt` — ONNX Runtime Mobile 会话封装，线程数 2，默认 CPU 执行
- `inference/YoloOutputParser.kt` — yolo26 输出解析 + NMS，支持 320×320 和 640×640 两种分辨率
- `inference/ImagePreprocessor.kt` — `ImageProxy`/`Bitmap` → CHW RGB float 数组
- `inference/SocWhitelist.kt` — SoC 白名单检测（骁龙 8 Gen / 天玑 9000+ / 麒麟 9000 / Exynos 2200+），决定默认分辨率 320 或 640
- `service/MonitorService.kt` — 主监控循环：按 `intervalMs = 1000 / targetFps` 控制推理间隔（默认 2 FPS，可调范围 1–5 FPS）
- `service/AlertService.kt` — 冷却锁判定，触发报警帧绘制与推送
- `service/DetectorForegroundService.kt` — 前台服务：绑定 CameraX + MonitorService + ServerPushService
- `service/ServerPushService.kt` — WS 认证、心跳、报警推送、命令接收（role: `android-detector`）
- `data/remote/WebSocketClient.kt` — 复用 receiver 端 WS 封装，role 改为 `"android-detector"`
- `util/SnapshotRenderer.kt` — Canvas 绘制检测框（LimeGreen 边框 + 标签）

**关键文件**：
- [计划文档](C:\Users\Administrator\.claude\plans\windows-detector-android-android-hidden-mccarthy.md) — Android 检测端完整实现方案
- [UI 设计稿](detector/android/design/AndroidDetectorDesign.pen) — ConfigScreen + AlertFrameScreen 两屏设计

**模型**：
- `yolo26n_320.onnx` / `yolo26n_640.onnx`（~5–6MB，轻量高速）
- `yolo26s_320.onnx` / `yolo26s_640.onnx`（~20–22MB，精度更高）
- 全部打包至 APK assets，首次启动按用户选择 + SoC 白名单复制到 `filesDir/models/`

**关键约束**：
- 无实时预览：仅绑定 `ImageAnalysis`，不绑定 `Preview`，降低 GPU/CPU 负载
- 报警帧暂显：检测到目标时临时显示绘制后的报警帧，3 秒后自动清空
- FPS 限制：默认 2 FPS，可调范围 1–5 FPS，控制设备发热

## 环境配置

### Server `.env`

```bash
PORT=3000
API_KEY=your-secret-key
SCREENSHOT_TTL_HOURS=72
MAX_UPLOAD_BYTES=2097152
```

### 构建与运行

**Server**：
```bash
cd server
npm install
npm run build
npm start
# 开发模式
npm run dev
```

一键部署到 VPS（含类型检查、同步、编译、重启）：
```bash
bash server/deploy.sh        # 仅同步 src/ 并重建
bash server/deploy.sh --full # 同时同步 package.json 并 npm install
```

**Android**：
使用 Android Studio 编译（推荐）。首次打开前确保 `local.properties` 已配置 SDK 路径：
```properties
sdk.dir=C:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk
```
Gradle 命令行编译：
```bash
cd receiver/android
./gradlew assembleRelease
```

**Windows**：
- 框架为 .NET Framework 4.7.2（老框架），推荐用 Visual Studio 2022 打开 `detector/windows/VisionGuard.csproj`
- 编译前确认 `.csproj` 中所有 `Content` 资源文件（`Assets/yolo26n.onnx`、`Assets/yolo26s.onnx`、`Assets/VisionGuard.ico`、`Assets/*.png` 等）的 `CopyToOutputDirectory` 已正确设置为 `PreserveNewest`
- 若使用自动编译/CI，需确保 MSBuild 能解析 NuGet packages 路径，或预先执行 `nuget restore`
- 生成 → 发布时，检查输出目录是否包含完整的 `Assets/` 子目录，缺失资源文件会导致运行时报错

## 通信时序

### Windows 检测端 ↔ Server ↔ Android 接收端

```
Windows                    Server                     Android
  │      WS auth(role=windows) │                           │
  │ ─────────────────────────> │                           │
  │      WS auth(role=android) │ <──────────────────────── │
  │        heartbeat(15s)      │                           │
  │ ─────────────────────────> │                           │
  │                            │      device-list          │
  │                            │ <────────────────────────>│
  │          alert + screenshot│                           │
  │ ───────POST /api/alert────>│                           │
  │                            │         alert(WS)         │
  │                            │ <──────────────────────── │
  │     request-screenshot     │                           │
  │ <───────────────────────── │ <────request-screenshot── │
  │      screenshot-data       │                           │
  │ ─────────────────────────> │ ─────screenshot-data────> │
```

### Android 检测端 ↔ Server ↔ Android 接收端

```
Detector                   Server                   Receiver
(android)                                           (android)
  │  WS auth(role=android-detector)│                         │
  │ ──────────────────────────────>│                         │
  │       heartbeat(15s)           │                         │
  │ ──────────────────────────────>│                         │
  │                                │      device-list        │
  │                                │ <──────────────────────>│
  │      alert + screenshot        │                         │
  │ ───────POST /api/alert────────>│                         │
  │                                │        alert(WS)        │
  │                                │ <──────────────────────>│
  │                                │   command / set-config  │
  │ <──────────────────────────────│ <───────────────────────│
  │       command-ack              │                         │
  │ ──────────────────────────────>│ ───────────────────────>│
```

## 开发注意事项

1. **版本号同步**：任何功能提交必须同时更新三端版本号。使用根目录 `VERSION` 文件作为唯一来源。
2. **协议兼容性**：修改 WS 消息格式属于 BREAKING CHANGE，必须升级主版本号。
3. **截图路径**：Server 存储截图到 `data/screenshots/<alertId>.png`，通过 HTTP 提供下载，Android 通过 WS 接收 base64 数据。
4. **NTP 同步**：Windows 端启动时同步 NTP 时钟，确保报警时间戳准确。
5. **网络切换**：Android 端监听网络变化，切换网络时立即重建 WS 连接（清除 OkHttp 连接池）。
6. **幽灵检测**：Server 75s 无消息视为离线；Android 45s 无消息主动断开重连。
7. **Android 检测端模型打包**：四个模型（n_320、n_640、s_320、s_640）全部打包到 APK assets，应用首次启动时按用户选择 + SoC 白名单复制对应模型到 `filesDir/models/`。
8. **分辨率策略**：默认 320×320 纯 CPU 运行；SocWhitelist 检测到高端 SoC（骁龙 8 Gen / 天玑 9000+ / 麒麟 9000 / Exynos 2200+）自动切换至 640×640。
9. **FPS 与热管理**：默认 2 FPS，可调范围 1–5 FPS；无 Preview 绑定以降低 GPU 负载；持续监控时注意设备发热。
10. **Android 14+ 前台服务**：`DetectorForegroundService` 需声明 `foregroundServiceType="camera|remoteMessaging"`，`startForeground()` 必须在 Service 启动后 5 秒内调用。
