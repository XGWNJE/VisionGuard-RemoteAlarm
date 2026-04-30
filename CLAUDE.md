# VisionGuard — Claude Code 项目指南

> 基于 AI 的实时监控系统。Windows 检测端通过 YOLO26 推理，经自建服务器实时推送报警至 Android 手机。

## 项目概览

```
detector/windows/    detector/android/         server/                    receiver/android/
  (Win检测端)           (安卓检测端)       ──►  VPS 中继服务器  ──►         (接收端)
  屏幕/窗口捕获         后置摄像头 + ONNX          Node.js / WebSocket          Android 手机
  YOLO26 目标检测       YOLO26 目标检测            HTTP REST + WS               查看报警 / 远程控制
```

| 目录 | 技术栈 | 功能 |
|---|---|---|
| `detector/windows/` | C# / .NET Framework 4.7.2 / WinForms | 屏幕/窗口捕获 + YOLO26 ONNX 推理 + 报警推送 |
| `server/` | Node.js / TypeScript / Express / ws | 中继服务器：设备管理、报警转发、截图存储 |
| `receiver/android/` | Kotlin / Jetpack Compose / OkHttp | 接收报警通知、查看截图、远程控制检测端 |
| `detector/android/` | Kotlin / Jetpack Compose / CameraX / ONNX Runtime Mobile | Android 检测端（后置摄像头 + YOLO26 推理 + 报警推送） |

## 版本管理

- **当前版本**：见根目录 [VERSION](VERSION)（纯人工备忘，无自动同步）
- **版本号规则**：
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
- [Form1.UI.cs](detector/windows/Form1.UI.cs) — UI 构建：主布局、4 页面、辅助方法
- [OnnxInferenceEngine.cs](detector/windows/Inference/OnnxInferenceEngine.cs) — ONNX Runtime 封装
- [YoloOutputParser.cs](detector/windows/Inference/YoloOutputParser.cs) — YOLO26 输出解析（格式 `[1, 300, 6]`，已内置 NMS，6 = [x1, y1, x2, y2, conf, class_id]）
- [CocoClassMap.cs](detector/windows/Data/CocoClassMap.cs) — COCO 80 类中英文映射，`TargetClassNames` 定义 6 类监控目标子集

**UI 架构**：固定 960×640 暗色 WinForms，左侧图标菜单 + 右侧内容区（预览 58% + 页面 42%）
- **捕获页**：区域/窗口选择、开始/停止监控
- **参数页**：置信度阈值 Slider（10–95%，显示 "N%"）、目标采样率 Slider（1–5 次/秒）、警报推送冷却时间 Slider（1–300 秒）、模型选择下拉框（yolo26n / yolo26s）
- **目标页**：6 个 `CheckBox`（人 / 自行车 / 汽车 / 摩托车 / 客车 / 卡车），默认只勾选"人"；空选视为检测全部
- **服务器页**：连接状态、设备名、手动重试

**依赖**：Microsoft.ML.OnnxRuntime 1.17+、websocket-sharp
**模型**：`Assets/yolo26n.onnx`（~9.4MB，轻量）/ `Assets/yolo26s.onnx`（~37MB，精准），COCO 80 类
**模型选择**：参数页下拉框切换 yolo26n / yolo26s
**监控目标**：与 Android 检测端对齐，仅 6 个常用 COCO 类（`CocoClassMap.TargetClassNames`），UI 用 `CheckBox[]` 而非完整 80 类搜索勾选
**ONNX 线程数**：固定 2 线程（与 Android 检测端一致，不可调）
**网络变化**：监听 `NetworkChange.NetworkAddressChanged`，30s 防抖，网络恢复时立即重连
**目标框架**：.NET Framework 4.7.2，x64

### server — 中继服务器

**技术栈**：Node.js 20+、TypeScript 6、Express 5、ws 8

**核心模块**：
- `routes/alert.ts` — POST `/api/alert` 接收报警上传（multipart/form-data，可选关闭）
- `routes/alerts.ts` — GET `/api/alerts?deviceId=&since=&limit=` 查询报警历史列表（按时间倒序）
- `routes/screenshot.ts` — GET `/screenshots/:id.png` 提供截图下载
- `services/ConnectionManager.ts` — WebSocket 连接管理：认证（含版本门控）、心跳、设备列表广播、报警广播、命令/配置/截图中继；支持三角色（`windows` / `android` / `android-detector`）
- `services/AlertStore.ts` — 报警记录存储：内存循环缓冲（默认 200 条/设备）+ 文件持久化（`data/alerts.json`，7 天 TTL）
- `services/ScreenshotCleanup.ts` — 截图过期清理（默认 TTL 72 小时）

**WebSocket 消息类型**：
| 方向 | 类型 | 发送方 | 说明 |
|---|---|---|---|
| → Server | `auth` | 所有端 | 认证（含 `role` / `deviceId` / `deviceName` / `version`） |
| ← Server | `auth-result` | Server | 认证结果，`success=false` 时含 `reason`（如版本过低） |
| → Server | `heartbeat` | Windows / Android检测端 | 富状态心跳（15s，含 monitoring/alarming/ready/cooldown/confidence/targets） |
| → Server | `heartbeat-android` | Android接收端 | 极简心跳（20s） |
| → Server | `alert` | 检测端 | 报警推送（HTTP POST `/api/alert` + 截图，或纯 WS 模式） |
| → Server | `command` | Android接收端 | 下发控制命令（pause/resume/stop-alarm） |
| → Server | `set-config` | Android接收端 | 调整检测参数（cooldown/confidence/targets） |
| → Server | `request-screenshot` | Android接收端 | 请求指定设备截图 |
| → Server | `screenshot-data` | 检测端 | 回传截图（base64 JPEG） |
| → Server | `command-ack` | 检测端 | 命令执行结果回执 |
| ← Server | `device-list` | Server | 设备列表广播 |
| ← Server | `alert` | Server | 报警推送（含 `timings` 端到端计时字段） |
| ← Server | `command-ack` | Server | 命令执行结果（含 relayed/实际结果两次） |
| ← Server | `kicked` | Server | 重复连接被踢 |

**部署**：VPS `216.36.111.208:3000`（SSH 端口 `53111`），systemd 服务 `visionguard`
**部署脚本**：[server/deploy.sh](server/deploy.sh)

### receiver/android — Android 接收端

**技术栈**：Kotlin 2.3.20、Jetpack Compose BOM 2026.03、AGP 9.1、minSdk 28 / targetSdk 36

**架构**：MVVM + 前台 Service + 单状态源事件循环

**核心模块**：
- `data/remote/WebSocketClient.kt` — OkHttp WebSocket 封装：退避重连、幽灵检测、Session 隔离
- `service/AlertForegroundService.kt` — 前台服务：持有 WS 连接、接收报警、发送系统通知、本地截图缓存
- `ui/screen/` — AlertListScreen（上新下旧排序）、AlertDetailScreen、DeviceListScreen
- `ui/viewmodel/` — AlertViewModel、DeviceViewModel
- `data/repository/SettingsRepository.kt` — DataStore 偏好设置持久化
- `data/cache/ScreenshotCache.kt` — 报警截图本地磁盘缓存（LRU 策略）

**关键常量**：[AppConstants.kt](receiver/android/app/src/main/java/com/xgwnje/visionguard_android/AppConstants.kt)
- `SERVER_URL = "http://216.36.111.208:3000"`
- `API_KEY = "XG-VisionGuard-2024"`

### detector/android — Android 检测端

**技术栈**：Kotlin 2.3.20、Jetpack Compose BOM 2026.03、CameraX 1.4.2、ONNX Runtime Mobile 1.20.0、AGP 9.1、minSdk 28 / targetSdk 36

**架构**：前台 Service（`foregroundServiceType="camera"`）+ CameraX `ImageAnalysis`（无 Preview）+ ONNX Runtime Mobile 纯 CPU 推理

**核心模块**：
- `inference/OnnxInferenceEngine.kt` — ONNX Runtime Mobile 会话封装，线程数 2，纯 CPU 执行
- `inference/YoloOutputParser.kt` — yolo26 输出解析 + NMS，支持 320×320 和 640×640 两种分辨率
- `inference/ImagePreprocessor.kt` — `ImageProxy`/`Bitmap` → CHW RGB float 数组
- `inference/SocWhitelist.kt` — SoC 白名单检测（骁龙 7/8 Gen / 天玑 8/9 系列 / 麒麟 / Exynos），决定高分辨率选项是否可用
- `service/MonitorService.kt` — 主监控循环：按 `intervalMs = 1000 / targetSamplingRate` 控制推理间隔（默认 3次/秒，可调 1–5次/秒）
- `service/AlertService.kt` — 冷却锁判定，触发报警帧绘制与推送（冷却仅限制推送频率，不影响识别预览）
- `service/DetectorForegroundService.kt` — 前台服务：绑定 CameraX + MonitorService + ServerPushService
- `service/ServerPushService.kt` — WS 认证、心跳、报警推送、命令接收（role: `android-detector`）
- `data/remote/WebSocketClient.kt` — WS 客户端，role 为 `"android-detector"`
- `util/SnapshotRenderer.kt` — Canvas 绘制检测框（LimeGreen 边框 + 标签）

**UI 架构**：BottomNavigation + NavHost 两页分页
- **监控页**（`MonitorScreen`）：报警帧暂显（1:1 画布）、状态卡片、启停监控按钮、手动预览按钮
- **设置页**（`SettingsScreen`）：模型选择（yolo26n/s）、置信度滑块、目标采样率滑块、监控目标 Chip 多选（人/汽车/卡车/客车/自行车/摩托车）、冷却时间滑块、高分辨率开关（需 SoC 支持）、服务器重连

**关键文件**：
- [MainActivity.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/MainActivity.kt) — Scaffold + NavHost + Service 绑定
- [MonitorScreen.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/ui/screen/MonitorScreen.kt) — 监控页（报警帧 + 状态 + 启停/预览按钮）
- [SettingsScreen.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/ui/screen/SettingsScreen.kt) — 设置页（所有推理参数）
- [DetectorForegroundService.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/service/DetectorForegroundService.kt) — 前台服务核心
- [MonitorService.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/service/MonitorService.kt) — 帧处理 → 推理 → 报警判定
- [StatusCard.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/ui/component/StatusCard.kt) — 连接/监控/采样率/模型状态卡片
- [AlertFrameViewer.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/ui/component/AlertFrameViewer.kt) — 报警帧 1:1 画布显示

**模型**：
- `yolo26n_320.onnx` / `yolo26n_640.onnx`（~5–6MB，轻量高速）
- `yolo26s_320.onnx` / `yolo26s_640.onnx`（~20–22MB，精度更高）
- 全部打包至 APK assets，首次启动按用户选择 + SoC 白名单复制到 `filesDir/models/`

**关键约束**：
- 无实时预览：仅绑定 `ImageAnalysis`，不绑定 `Preview`，降低 GPU/CPU 负载
- 报警帧暂显：检测到目标时显示绘制后的报警帧；冷却期内识别继续，预览持续展示
- 手动预览：监控中可随时捕获当前帧到预览画布，辅助无目标时的摄像头部署
- 目标采样率：默认 3次/秒（上限），可调 1–5次/秒；实际采样率受推理耗时影响，低于目标 80% 时橙色提示性能不足
- 高分辨率：640×640 需手动开启，仅 SoC 白名单内设备可用
- 监控目标：Chip 按钮多选（人 / 汽车 / 卡车 / 客车 / 自行车 / 摩托车）
- 自定义设备名：设置页可修改，DataStore 持久化
- JPEG 压缩：quality 65，长边最大 960px（与 Windows 端对齐）

## 部署环境

| 端 | 最低系统要求 |
|---|---|
| Server | Ubuntu 20.04+ / Debian 11+，Node.js 20+ |
| Windows 检测端 | **Windows 10 及以上**（.NET Framework 4.7.2，x64） |
| Android 检测端 | Android 9.0+（API 28+），推荐骁龙 7/8 Gen 或天玑 8/9 系列 |
| Android 接收端 | Android 9.0+（API 28+） |

## 环境配置

### Server `.env`

```bash
PORT=3000
API_KEY=your-secret-key
SCREENSHOT_TTL_HOURS=72
MAX_UPLOAD_BYTES=2097152
ENABLE_HTTP_SCREENSHOT_UPLOAD=true   # true=HTTP上传截图；false=纯WS按需模型
ALERT_TTL_HOURS=168                  # 报警记录持久化TTL，默认7天
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

1. **版本号管理**：纯人工控制。需要更新时手动修改各端版本号（Server `package.json`、Android `build.gradle.kts`、Windows `.csproj` / `AssemblyInfo.cs`），并同步更新根目录 `VERSION` 备忘。
2. **协议兼容性**：修改 WS 消息格式属于 BREAKING CHANGE，必须升级主版本号。
3. **服务端版本门控**：`config.minClientVersion = '3.5.0'`，WS 认证时校验客户端 `version` 字段，低版本直接拒绝连接。
4. **截图双模式**：`ENABLE_HTTP_SCREENSHOT_UPLOAD` 控制截图上传策略。`true` = 检测端 HTTP POST 上传截图到 Server；`false` = 纯 WS 按需模型，截图仅存在检测端本地，接收端通过 `request-screenshot` 按需拉取。
5. **报警数据持久化**：Server `AlertStore` 同时维护内存循环缓冲和磁盘文件 `data/alerts.json`（7 天 TTL），重启后自动恢复。
6. **截图路径**：Server 存储截图到 `data/screenshots/<alertId>.png`，通过 HTTP 提供下载（需 `X-API-Key` header），Android 通过 WS 接收 base64 数据。
7. **NTP 同步**：Windows 端启动时同步 NTP 时钟，确保报警时间戳准确。
8. **网络切换**：Android 端监听网络变化，切换网络时立即重建 WS 连接（清除 OkHttp 连接池）。Windows 端监听 `NetworkAddressChanged`，30s 防抖后重连。
9. **幽灵检测**：Server 75s 无消息视为离线；服务端每 30s 主动发送 Ping 帧检测半开连接。Android 45s 无消息主动断开重连。
10. **Android 检测端模型打包**：四个模型（n_320、n_640、s_320、s_640）全部打包到 APK assets，应用首次启动按用户选择复制对应模型到 `filesDir/models/`。
11. **分辨率策略**：默认 320×320 纯 CPU 运行；640×640 为手动开关，仅 SocWhitelist 检测到的中高端 SoC 可用。
12. **采样率与热管理**：默认目标采样率 3次/秒（上限），可调 1–5次/秒；无 Preview 绑定以降低 GPU 负载；持续监控时注意设备发热。
13. **Android 14+ 前台服务**：`DetectorForegroundService` 需声明 `foregroundServiceType="camera|remoteMessaging"`，`startForeground()` 必须在 Service 启动后 5 秒内调用。
14. **应用名**：Android 检测端 `app_name = "VG 检测"`，接收端 `app_name = "VG 接收"`（桌面图标不省略）。
