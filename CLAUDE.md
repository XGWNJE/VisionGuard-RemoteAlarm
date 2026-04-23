# VisionGuard — Claude Code 项目指南

> 基于 AI 的实时监控系统。Windows 检测端通过 YOLOv5 推理，经自建服务器实时推送报警至 Android 手机。

## 项目概览

```
detector/windows/          server/                    receiver/android/
  (推理检测端)        ──►  VPS 中继服务器  ──►         (通知接收端)
  Windows PC(s)           Node.js / WebSocket          Android 手机
  YOLOv5 目标检测          HTTP REST + WS               查看报警 / 远程控制
```

| 目录 | 技术栈 | 功能 |
|---|---|---|
| `detector/windows/` | C# / .NET Framework 4.7.2 / WinForms | 屏幕/窗口捕获 + YOLOv5 ONNX 推理 + 报警推送 |
| `server/` | Node.js / TypeScript / Express / ws | 中继服务器：设备管理、报警转发、截图存储 |
| `receiver/android/` | Kotlin / Jetpack Compose / OkHttp | 接收报警通知、查看截图、远程控制检测端 |
| `detector/android/` | Kotlin / Jetpack Compose (脚手架) | 备用推理端（当前为空壳，预留扩展） |

## 版本管理

- **当前版本**：`3.3.0`（见根目录 [VERSION](VERSION)）
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

**依赖**：Microsoft.ML.OnnxRuntime 1.1.0、websocket-sharp
**模型**：`Assets/yolov5nu.onnx`（~10MB，YOLOv5nu，COCO 80 类）
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
- 编译前确认 `.csproj` 中所有 `Content` 资源文件（`Assets/yolov5nu.onnx`、`Assets/VisionGuard.ico`、`Assets/*.png` 等）的 `CopyToOutputDirectory` 已正确设置为 `PreserveNewest`
- 若使用自动编译/CI，需确保 MSBuild 能解析 NuGet packages 路径，或预先执行 `nuget restore`
- 生成 → 发布时，检查输出目录是否包含完整的 `Assets/` 子目录，缺失资源文件会导致运行时报错

## 通信时序

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

## 开发注意事项

1. **版本号同步**：任何功能提交必须同时更新三端版本号。使用根目录 `VERSION` 文件作为唯一来源。
2. **协议兼容性**：修改 WS 消息格式属于 BREAKING CHANGE，必须升级主版本号。
3. **截图路径**：Server 存储截图到 `data/screenshots/<alertId>.png`，通过 HTTP 提供下载，Android 通过 WS 接收 base64 数据。
4. **NTP 同步**：Windows 端启动时同步 NTP 时钟，确保报警时间戳准确。
5. **网络切换**：Android 端监听网络变化，切换网络时立即重建 WS 连接（清除 OkHttp 连接池）。
6. **幽灵检测**：Server 75s 无消息视为离线；Android 45s 无消息主动断开重连。
