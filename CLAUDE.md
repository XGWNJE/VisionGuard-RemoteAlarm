# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
- **批量升级脚本**：[scripts/bump-version.sh](scripts/bump-version.sh) 一次性同步 `VERSION`、`server/package.json`、两端 `build.gradle.kts`（含 `versionCode = MAJOR*1000+MINOR*100+PATCH`）、Windows `.csproj` / `AssemblyInfo.cs`
  ```bash
  bash scripts/bump-version.sh patch   # 修订号 +1
  bash scripts/bump-version.sh minor   # 次版本 +1
  bash scripts/bump-version.sh major   # 主版本 +1
  bash scripts/bump-version.sh         # 交互式选择
  ```

## 各端详解

### detector/windows — Windows 推理检测端

**核心模块**：
- `Capture/` — 屏幕捕获、窗口枚举、子区域选择、`GlobalKeyHook.cs` 全局热键
- `Inference/` — ONNX Runtime 推理引擎、YOLO 输出解析、`ImagePreprocessor.cs` 图像预处理、`MaskApplier.cs` 推理前在 Bitmap 上 in-place 涂黑遮罩
- `Services/` — 监控服务（定时推理循环）、报警服务（声光通知）、服务器推送服务（WS 连接）
- `Models/` — DTO 数据对象（`AlertEvent.cs` / `Detection.cs` / `MonitorConfig.cs`，含 `MaskRegions` 字段）
- `UI/` — 自定义 WinForms 控件（暗色主题、圆角按钮、检测框覆盖层）；`MaskEditorForm.cs` 全屏遮罩编辑器
- `Data/` — `CocoClassMap.cs`（独立目录）
- `Utils/` — NTP 时钟同步、设置持久化、截图渲染器

**关键文件**：
- [Form1.cs](detector/windows/Form1.cs) — 主窗体：字段、构造、配置构建、状态控制
- [Form1.Monitor.cs](detector/windows/Form1.Monitor.cs) — 监控控制：区域选择、启停、回调
- [Form1.Server.cs](detector/windows/Form1.Server.cs) — 服务器连接、设置持久化、远程配置
- [Form1.UI.cs](detector/windows/Form1.UI.cs) — UI 构建：主布局、4 页面、辅助方法
- [Form1.Designer.cs](detector/windows/Form1.Designer.cs) — Designer 自动生成的最小桩代码
- [OnnxInferenceEngine.cs](detector/windows/Inference/OnnxInferenceEngine.cs) — ONNX Runtime 封装
- [YoloOutputParser.cs](detector/windows/Inference/YoloOutputParser.cs) — YOLO26 输出解析（格式 `[1, 300, 6]`，已内置 NMS，6 = [x1, y1, x2, y2, conf, class_id]）
- [CocoClassMap.cs](detector/windows/Data/CocoClassMap.cs) — COCO 80 类中英文映射，`TargetClassNames` 定义 6 类监控目标子集

**UI 架构**：固定 960×640 暗色 WinForms，左侧图标菜单 + 右侧内容区（预览 58% + 页面 42%）
- **捕获页**：区域/窗口选择、**遮罩区域绘制**（启动 `MaskEditorForm`）、当前遮罩计数、开始/停止监控
- **参数页**：置信度阈值 Slider（10–95%，显示 "N%"）、目标采样率 Slider（1–5 次/秒）、警报推送冷却时间 Slider（1–300 秒）、模型选择下拉框（yolo26n / yolo26s）
- **目标页**：6 个 `CheckBox`（人 / 自行车 / 汽车 / 摩托车 / 客车 / 卡车），默认只勾选"人"；空选视为检测全部
- **服务器页**：连接状态、设备名、手动重试

**与 Android 检测端的差异**（commit `46f58e9` 仅对齐控件类型/命名风格；遮罩绘制已于 v3.7.0 抹平）：
- 无数码裁切变焦（Android 的纯软件 1x–5x 中心裁切）
- 无高分辨率开关（始终单分辨率，模型尺寸由 ONNX 决定）
- 无 SoC 白名单
- 监控目标为固定 6 类 CheckBox（与 Android Chip 多选语义对齐）

**v3.7.0 新增功能 — 遮罩绘制（Mask）**（与 Android v3.6.0 行为对齐）：
- **数据结构**：`MonitorConfig.MaskRegions: List<RectangleF>`，相对坐标 X/Y/Width/Height ∈ [0,1]，最小相对尺寸 `0.02`
- **编辑入口**：捕获页「遮罩区域…」按钮 → [Form1.Monitor.cs](detector/windows/Form1.Monitor.cs) `BtnEditMasks_Click`：抓一帧底图（`WindowCapturer.CaptureWindow` / `ScreenCapturer.CaptureRegion`，与监控帧尺寸一致），打开 [MaskEditorForm.cs](detector/windows/UI/MaskEditorForm.cs) 多矩形拖拽编辑器（撤销/清空/取消/确定 + ESC，半透明红色填充 + 进行中黄色虚线）
- **耦合点**：[MaskApplier.cs](detector/windows/Inference/MaskApplier.cs) 在 [MonitorService.cs](detector/windows/Services/MonitorService.cs) `OnTick` 第 132–134 行（capture 完、`ToTensor` 之前）`Graphics.FillRectangle` 黑色 in-place 涂黑
- **重要副作用**：**遮罩同时影响推理与报警截图与 UI 预览**（涂黑区域不被识别，截图与 `DetectionOverlayPanel` 也是黑的）
- **持久化**：settings.ini key `MaskRegions`，自定义 DTO `{left, top, right, bottom}` 经 `Utils.SimpleJson` 序列化（避开 `RectangleF` 默认序列化噪音；`SimpleJson.Deserialize<T>` 失败回退）
- **热更新**：监控运行中编辑遮罩 → `_monitorService.UpdateConfig(BuildConfig())` 走 `Volatile.Write`，下个 Tick 即生效
- **远程同步**：与 Android 一致**仅本地配置**，[Form1.Server.cs](detector/windows/Form1.Server.cs) `ApplyRemoteConfig` `default` 分支对 `maskRegions` 等未知项继续 NACK

**依赖**：Microsoft.ML.OnnxRuntime 1.17+、websocket-sharp
**模型**：`Assets/yolo26n.onnx`（~9.4MB，轻量）/ `Assets/yolo26s.onnx`（~37MB，精准），COCO 80 类
**Assets 文件**：
- 运行时打包至输出目录：`yolo26n.onnx` / `yolo26s.onnx`（模型）、`capture.png` / `settings.png` / `target.png` / `server.png`（4 个左侧菜单图标）、`*.wav`（报警音效）
- 仅源码保留、**不复制**到 Release 输出（commit `12cada0` 精简）：`VisionGuard.ico`（运行时由 `Icon.ExtractAssociatedIcon` 从 EXE 自身读取）、`screenshot.png`（仅 README 引用，已通过 `Exclude` 排除）、`yolo26n.pt` / `yolo26s.pt`（PyTorch 源权重）、`yolov5nu.onnx`（旧模型遗留）、`COCO_CLASSES.md`、`ASSETS_README.md`
**ONNX 线程数**：固定 2 线程（与 Android 检测端一致，不可调）
**网络变化**：监听 `NetworkChange.NetworkAddressChanged`，30s 防抖，网络恢复时立即重连
**目标框架**：.NET Framework 4.7.2，x64
**WS role**：`windows`

### server — 中继服务器

**技术栈**：Node.js 20+、TypeScript 6、Express 5、ws 8

**核心模块**：
- `routes/alert.ts` — POST `/api/alert` 接收报警上传（multipart/form-data，可选关闭）
- `routes/alerts.ts` — GET `/api/alerts?deviceId=&since=&limit=` 查询报警历史列表（按时间倒序）
- `routes/screenshot.ts` — GET `/screenshots/:id.png` 提供截图下载
- `services/ConnectionManager.ts` — WebSocket 连接管理：认证（含版本门控）、心跳、设备列表广播、报警广播、命令/配置/截图中继；支持三角色（`windows` / `android` / `android-detector`）
- `services/AlertStore.ts` — 报警记录存储：内存循环缓冲（默认 200 条/设备）+ 文件持久化（`data/alerts.json`，7 天 TTL），启动时立即清理过期 + 之后每 30 分钟周期清理
- `services/ScreenshotCleanup.ts` — 截图过期清理（默认 TTL 72 小时）
- `middleware/auth.ts` — `X-API-Key` 校验中间件
- `models/types.ts` — 类型集中文件（DTO 与 WS 消息体）
- 健康检查：`GET /health`（无鉴权，返回 uptime，用于负载均衡器/监控）

**WebSocket 消息类型**：
| 方向 | 类型 | 发送方 | 说明 |
|---|---|---|---|
| → Server | `auth` | 所有端 | 认证（含 `role` / `deviceId` / `deviceName` / `version`） |
| ← Server | `auth-result` | Server | 认证结果，`success=false` 时含 `reason`（如版本过低） |
| → Server | `heartbeat` | Windows / Android检测端 | 富状态心跳（15s，含 monitoring/alarming/ready/cooldown/confidence/targets） |
| → Server | `heartbeat-android` | Android接收端 | 极简心跳（20s） |
| → Server | `alert` | 检测端 | 报警推送（HTTP POST `/api/alert` + 截图，或纯 WS 模式） |
| → Server | `command` | Android接收端 | 下发控制命令（pause/resume/stop-alarm） |
| → Server | `set-config` | Android接收端 | 调整检测参数（cooldown/confidence/targets/maskRegions/digitalZoom） |
| → Server | `request-screenshot` | Android接收端 | 请求指定设备截图 |
| → Server | `screenshot-data` | 检测端 | 回传截图（base64 JPEG） |
| → Server | `command-ack` | 检测端 | 命令执行结果回执 |
| → Server | `disconnect-reason` | 客户端 | 客户端主动断开时上报原因（用于诊断） |
| → Server | `session-info` | 客户端 | Session 元数据（追踪重连链路） |
| ← Server | `device-list` | Server | 设备列表广播 |
| ← Server | `alert` | Server | 报警推送（含 `timings` 端到端计时字段） |
| ← Server | `command-ack` | Server | 命令执行结果（含 relayed/实际结果两次） |
| ← Server | `kicked` | Server | 重复连接被踢 |
| ← Server | `ping` | Server | 应用层 Ping（30s）+ WS 协议层 Ping，双层幽灵连接检测 |

**`config` 关键字段**（[server/src/config.ts](server/src/config.ts)）：
- `port`（PORT，默认 3000）
- `apiKey`（API_KEY，必填）
- `screenshotDir` / `screenshotTtlHours`（默认 72） / `cleanupIntervalMs`（CLEANUP_INTERVAL_MS，默认 1 小时）
- `maxUploadBytes`（默认 2MB）
- `wsAuthTimeoutMs = 5000`（硬编码）
- `deviceOfflineMs = 75_000`（无消息阈值，超过即终止连接）
- `maxAlertsPerDevice = 200`（循环缓冲容量）
- `minClientVersion = '3.5.0'`（认证版本门控）
- `enableHttpScreenshotUpload`（ENABLE_HTTP_SCREENSHOT_UPLOAD）
- `alertTtlHours`（ALERT_TTL_HOURS，默认 168 / 7 天）

**部署**：VPS `216.36.111.208:3000`，systemd 服务 `visionguard`
**部署脚本**：[server/deploy.sh](server/deploy.sh) — SSH 端口通过 `VPS_PORT` 环境变量覆盖（默认 22）

### receiver/android — Android 接收端

**技术栈**：Kotlin 2.3.20、Jetpack Compose BOM 2026.03、AGP 9.1、minSdk 28 / targetSdk 36
**额外依赖**：`coil.compose`（图片加载）

**架构**：MVVM + 前台 Service + 单状态源事件循环

**核心模块**：
- `VisionGuardApp.kt` — Application 类
- `data/remote/WebSocketClient.kt` — OkHttp WebSocket 封装：退避重连、幽灵检测、Session 隔离
- `data/repository/SettingsRepository.kt` — DataStore 偏好设置持久化
- `data/cache/ScreenshotCache.kt` — 报警截图本地磁盘缓存（LRU 策略）
- `data/model/` — `AlertMessage.kt` / `WsMessage.kt` / `DeviceInfo.kt` / `CocoClassMap.kt`（接收端也持有 COCO 映射）
- `service/AlertForegroundService.kt` — 前台服务（`foregroundServiceType="remoteMessaging"`）：持有 WS 连接、接收报警、发送系统通知
- `service/BootReceiver.kt` — 开机自启 + `MY_PACKAGE_REPLACED` 接收器
- `service/NetworkMonitor.kt` — 独立网络监听类，触发立即重连
- `util/NotificationHelper.kt` — 系统通知封装（含全屏唤醒、呼吸灯）
- `util/NtpSync.kt` — NTP 时钟同步（接收端也需要，用于显示端到端耗时）
- `ui/screen/` — `AlertListScreen`（上新下旧排序）、`AlertDetailScreen`、`DeviceListScreen`
- `ui/component/` — `ConnectionBanner.kt` / `AlertCard.kt` / `DeviceCard.kt` 复用组件
- `ui/viewmodel/` — `AlertViewModel` / `DeviceViewModel`

**UI 与远程控制**：
- **无独立 Settings 屏**。`set-config` 调整 cooldown / confidence / targets / maskRegions / digitalZoom 的 UI 散落在 `DeviceListScreen` 设备卡片与 `AlertDetailScreen` 详情页内联。需要修改远控参数时改这两页
- **三页标题栏均为自定义实现**（commit `42fa632`）：`Surface + Row + statusBars padding`，使用 `contentWindowInsets = WindowInsets(0,0,0,0)` 避免双重 padding。**未抽取为复用组件**，三处代码重复，修改时需同步三个文件

**Manifest 关键声明**：
- 权限：`USE_FULL_SCREEN_INTENT` / `WAKE_LOCK` / `RECEIVE_BOOT_COMPLETED` / `POST_NOTIFICATIONS` / `INTERNET` / `ACCESS_NETWORK_STATE`
- 前台服务类型：`foregroundServiceType="remoteMessaging"`（仅一种类型）

**关键常量**：[AppConstants.kt](receiver/android/app/src/main/java/com/xgwnje/visionguard_android/AppConstants.kt)
- `SERVER_URL = "http://216.36.111.208:3000"`
- `API_KEY = "XG-VisionGuard-2024"`
- 包名：`com.xgwnje.visionguard_android`（与检测端 `com.xgwnje.visionguard` 不同）
- 应用名：`app_name = "VG 接收"`
- 签名：`signingConfigs` 读取 `keystore.properties`（commit `759cab5` 移除的是 keystore 二进制）

### detector/android — Android 检测端

**技术栈**：Kotlin 2.3.20、Jetpack Compose BOM 2026.03、CameraX 1.4.2、ONNX Runtime Mobile 1.20.0、AGP 9.1、minSdk 28 / targetSdk 36

**架构**：前台 Service（`foregroundServiceType="camera"`）+ CameraX `ImageAnalysis`（无 Preview）+ ONNX Runtime Mobile 纯 CPU 推理

**核心模块**：
- `inference/OnnxInferenceEngine.kt` — ONNX Runtime Mobile 会话封装，线程数 2，纯 CPU 执行
- `inference/YoloOutputParser.kt` — yolo26 输出解析 + NMS，支持 320×320 和 640×640 两种分辨率
- `inference/ImagePreprocessor.kt` — `ImageProxy`/`Bitmap` → CHW RGB float 数组；`cropAndMask(bitmap, zoom, masks)` 集成数码变焦中心裁切 + 遮罩区域涂黑（同一函数）
- `inference/SocWhitelist.kt` — SoC 白名单检测（骁龙 7/8 Gen / 天玑 8/9 系列 / 麒麟 / Exynos），决定高分辨率选项是否可用
- `data/model/MaskRegion.kt` — 遮罩区域数据模型（相对坐标 `(left, top, right, bottom) ∈ [0,1]`，分辨率无关）
- `service/MonitorService.kt` — 主监控循环：按 `intervalMs = 1000 / targetSamplingRate` 控制推理间隔（默认 3次/秒，可调 1–5次/秒）；处理裁切偏移以将检测框坐标映射回原帧
- `service/AlertService.kt` — 冷却锁判定，触发报警帧绘制与推送（冷却仅限制推送频率，不影响识别预览）
- `service/DetectorForegroundService.kt` — 前台服务：绑定 CameraX + MonitorService + ServerPushService；**`digitalZoom` 变化触发 CameraX 分辨率自适应 + unbind/rebind 热更新**
- `service/ServerPushService.kt` — WS 认证、心跳、报警推送、命令接收（role: `android-detector`）
- `data/remote/WebSocketClient.kt` — WS 客户端，role 为 `"android-detector"`
- `util/SnapshotRenderer.kt` — Canvas 绘制检测框（LimeGreen 边框 + 标签）
- `util/NetworkMonitor.kt` — 网络变化监听
- `util/NtpSync.kt` — NTP 时钟同步
- `util/InferenceDiagnostics.kt` — 推理性能诊断日志
- `util/LogManager.kt` / `util/NotificationHelper.kt` / `util/ScreenshotCache.kt` — 日志/通知/本地截图缓存

**UI 架构**：BottomNavigation + NavHost 两页分页 + 全屏遮罩编辑器
- **监控页**（`MonitorScreen`）：报警帧暂显（1:1 画布）、状态卡片、启停监控按钮、手动预览按钮、**"设置监控区域"按钮**（启动遮罩/变焦编辑器；仅未监控时可用）
- **设置页**（`SettingsScreen`）：模型选择（yolo26n/s）、置信度滑块、目标采样率滑块、监控目标 Chip 多选（人/汽车/卡车/客车/自行车/摩托车）、冷却时间滑块、高分辨率开关（需 SoC 支持）、自定义设备名、服务器重连。**不含遮罩/变焦设置**
- **遮罩编辑器**（`MaskEditorScreen`，全屏弹出）：在最近预览帧（或灰色网格占位）上 `detectDragGestures` 拖拽画矩形（红色填充+边框，最小尺寸 0.02），按钮 `撤销 / 清空 / 取消 / 确认`；底部 `Slider` 1.0–5.0（约 0.11 步进）控制数码变焦，画布上叠加青色虚线框示意中心裁切区域

**关键文件**：
- [MainActivity.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/MainActivity.kt) — Scaffold + NavHost + Service 绑定 + 遮罩编辑器全屏切换
- [MonitorScreen.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/ui/screen/MonitorScreen.kt) — 监控页
- [SettingsScreen.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/ui/screen/SettingsScreen.kt) — 设置页
- [MaskEditorScreen.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/ui/screen/MaskEditorScreen.kt) — 遮罩 + 变焦编辑器（同一界面）
- [DetectorForegroundService.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/service/DetectorForegroundService.kt) — 前台服务核心 + zoom 触发的 CameraX 重绑定
- [MonitorService.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/service/MonitorService.kt) — 帧处理 → 推理 → 报警判定
- [ImagePreprocessor.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/inference/ImagePreprocessor.kt) — `cropAndMask` 数码变焦+遮罩同源实现
- [StatusCard.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/ui/component/StatusCard.kt) — 连接/监控/采样率/模型状态卡片
- [AlertFrameViewer.kt](detector/android/app/src/main/java/com/xgwnje/visionguard/ui/component/AlertFrameViewer.kt) — 报警帧 1:1 画布显示

**模型**：
- `yolo26n_320.onnx` / `yolo26n_640.onnx`（~5–6MB，轻量高速）
- `yolo26s_320.onnx` / `yolo26s_640.onnx`（~20–22MB，精度更高）
- 全部打包至 APK assets，首次启动按用户选择 + SoC 白名单复制到 `filesDir/models/`
- `assets/models/` 还残留 `yolo26n.pt` / `yolo26s.pt`（PyTorch 源权重，运行时不用，发布前可裁剪）

**v3.6.0 新增功能 — 遮罩绘制（Mask）**：
- **持久化**：DataStore key `mask_regions`，**Gson 序列化为 JSON 字符串**（`SettingsRepository.maskRegionsFlow`）；`MonitorConfig.maskRegions: List<MaskRegion>` 默认空
- **耦合点**：`MonitorService.processFrame` → 调用 `ImagePreprocessor.cropAndMask(bitmap, zoom, masks)`，对裁切后的 Bitmap 用 `Canvas + 黑色 Paint` 直接涂黑遮罩区域，再传给推理
- **重要副作用**：**遮罩同时影响推理与报警帧绘制**（涂黑区域既不识别，截图也是黑的）
- **远程同步**：`set-config` 携带 `maskRegions` 字段经 `saveMonitorConfig` 原子写入

**v3.6.0 新增功能 — 数码裁切变焦 1x~5x**：
- **实现方式**：**纯软件预处理裁切，不调用 CameraX `setZoomRatio` / `cameraControl`**。在 `ImagePreprocessor.cropAndMask` 中 `Bitmap.createBitmap` 中心裁切 `srcW/zoom × srcH/zoom`，再缩放至 inputSize 喂模型
- **CameraX 分辨率自适应**（`DetectorForegroundService` 第 533–548 行）：
  - `zoom ≥ 3` → 请求 1920×1080
  - `zoom ≥ 2` → 请求 1280×960
  - 否则默认（更小）
  - **`digitalZoom` 变化且监控中 → unbind+rebind CameraX**（热更新）
- **持久化**：DataStore `digital_zoom: Float`，默认 `1.0f`
- **报警帧坐标修正**：`MonitorService` 拿到 `(cropOffsetX, cropOffsetY)`，把检测框坐标映射回原帧绘制；`tCropMask`/`preprocessMs` 计入耗时统计

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
CLEANUP_INTERVAL_MS=3600000          # 截图清理周期，默认 1 小时
MAX_UPLOAD_BYTES=2097152
ENABLE_HTTP_SCREENSHOT_UPLOAD=true   # true=HTTP上传截图；false=纯WS按需模型
ALERT_TTL_HOURS=168                  # 报警记录持久化TTL，默认7天
```

### deploy.sh 环境变量

```bash
VPS_PORT=22                          # SSH 端口，可通过 export 覆盖
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
- 编译前确认 `.csproj` 中 `Content` 资源（`Assets/yolo26n.onnx`、`Assets/yolo26s.onnx`、`Assets/*.png`（不含 `screenshot.png`，已 Exclude）等）的 `CopyToOutputDirectory` 已设置为 `PreserveNewest`
- Release 输出已精简（commit `12cada0`）：`GenerateManifests=false` / `SignManifests=false` 关闭 ClickOnce；`AllowedReferenceRelatedFileExtensions=.allowedextension` 阻止引用 DLL 的 `.pdb` / `.xml` 跟随复制；运行时图标用 `Icon.ExtractAssociatedIcon` 从 EXE 自身读取，不再复制 `VisionGuard.ico`
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

1. **版本号管理**：纯人工控制。需要更新时手动修改各端版本号（Server `package.json`、Android `build.gradle.kts`、Windows `.csproj` / `AssemblyInfo.cs`），并同步更新根目录 `VERSION` 备忘。或使用 [scripts/bump-version.sh](scripts/bump-version.sh) 一键同步。
2. **协议兼容性**：修改 WS 消息格式属于 BREAKING CHANGE，必须升级主版本号。
3. **服务端版本门控**：`config.minClientVersion = '3.5.0'`，WS 认证时校验客户端 `version` 字段，低版本直接拒绝连接。
4. **截图双模式**：`ENABLE_HTTP_SCREENSHOT_UPLOAD` 控制截图上传策略。`true` = 检测端 HTTP POST 上传截图到 Server；`false` = 纯 WS 按需模型，截图仅存在检测端本地，接收端通过 `request-screenshot` 按需拉取。
5. **报警数据持久化**：Server `AlertStore` 同时维护内存循环缓冲和磁盘文件 `data/alerts.json`（7 天 TTL），重启后自动恢复；启动时立即清理一次过期，之后每 30 分钟周期清理。
6. **截图路径**：Server 存储截图到 `data/screenshots/<alertId>.png`，通过 HTTP 提供下载（需 `X-API-Key` header），Android 通过 WS 接收 base64 数据。
7. **NTP 同步**：Windows 端启动时同步 NTP 时钟，确保报警时间戳准确；Android 接收端也通过 `util/NtpSync.kt` 同步用于显示端到端耗时。
8. **网络切换**：Android 端监听网络变化，切换网络时立即重建 WS 连接（清除 OkHttp 连接池）。Windows 端监听 `NetworkAddressChanged`，30s 防抖后重连。
9. **幽灵检测**：Server `deviceOfflineMs = 75_000`，超过即终止连接并标记离线；服务端发送应用层 `ping` 消息 + WS 协议层 Ping 双层检测半开连接。Android 45s 无消息主动断开重连。
10. **Android 检测端模型打包**：四个 ONNX 模型（n_320、n_640、s_320、s_640）全部打包到 APK assets，应用首次启动按用户选择复制对应模型到 `filesDir/models/`；`assets/models/` 还残留 `*.pt` 源权重不参与运行，发布前可裁剪以减小 APK。
11. **分辨率策略**：默认 320×320 纯 CPU 运行；640×640 为手动开关，仅 SocWhitelist 检测到的中高端 SoC 可用。
12. **采样率与热管理**：默认目标采样率 3次/秒（上限），可调 1–5次/秒；无 Preview 绑定以降低 GPU 负载；持续监控时注意设备发热。
13. **Android 14+ 前台服务类型**：
    - 检测端 `DetectorForegroundService` 仅声明 `foregroundServiceType="camera"`（无 `remoteMessaging`）
    - 接收端 `AlertForegroundService` 仅声明 `foregroundServiceType="remoteMessaging"`
    - `startForeground()` 必须在 Service 启动后 5 秒内调用
14. **应用名**：Android 检测端 `app_name = "VG 检测"`（包名 `com.xgwnje.visionguard`），接收端 `app_name = "VG 接收"`（包名 `com.xgwnje.visionguard_android`）。
15. **遮罩绘制（v3.6.0+）**：Android 检测端遮罩区域 (`MaskRegion`) 用相对坐标存储，`ImagePreprocessor.cropAndMask` 在裁切后的 Bitmap 上 Canvas 涂黑。遮罩**同时影响推理与报警帧绘制**，涂黑区域既不识别也不可见。
16. **数码裁切变焦（v3.6.0+）**：Android 检测端 1x–5x 为**纯软件预处理裁切**，不调用 CameraX `setZoomRatio`。变焦倍率影响 CameraX 请求分辨率（zoom≥3 → 1920×1080，zoom≥2 → 1280×960），变化时 unbind+rebind 热更新。检测框坐标需通过 `cropOffset` 映射回原帧。
17. **Windows 与 Android 检测端的差异**：v3.7.0 起 Windows 端**已支持遮罩绘制**（[MaskApplier.cs](detector/windows/Inference/MaskApplier.cs) + [MaskEditorForm.cs](detector/windows/UI/MaskEditorForm.cs)，行为与 Android 完全对齐）；仍**不支持**数码裁切变焦、高分辨率开关、SoC 白名单。commit `46f58e9` 时仅做 UI 控件类型对齐，commit `30f3385` 抹平遮罩功能。
18. **接收端无独立 Settings 屏**：远控参数（cooldown / confidence / targets / maskRegions / digitalZoom）调整 UI 散落在 `DeviceListScreen` 设备卡片与 `AlertDetailScreen` 详情页内联，修改时需查这两页。
