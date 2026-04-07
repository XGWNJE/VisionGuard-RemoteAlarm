# VisionGuard 远程报警系统 — 完整落地方案

## Context

VisionGuard 当前是一个 **Windows 本地单机监控系统**：YOLOv5nu 实时检测屏幕/窗口中的目标 → 本地铃声报警 + 截图保存。用户需要将其升级为 **三端远程报警系统**：Windows 检测到目标后，通过自建 Debian 服务器中继，将报警和截图实时推送到 Android 手机。

### 核心需求
- 多台 Windows 同时推送，手机端按用户自定义设备名（如"书房"、"门口"）区分来源
- 手机端可反向控制：暂停/恢复某台 Windows 的监控、远程静默正在响的报警
- 报警秒级送达，实时性要求高
- 纯个人/家庭使用，安全做基本防护
- **用户端必须简单好用，复杂决策不暴露给用户**

---

## 一、系统架构

```
Windows PC(s)                  Debian VPS                    Android 手机
┌──────────────┐    HTTP POST   ┌──────────────┐   WS push   ┌──────────────┐
│ 检测到目标    │───────────────►│ /api/alert   │────────────►│ 通知+截图     │
│ 截图+元数据   │  multipart     │ 存截图+广播   │             │ 铃声+振动     │
└──────┬───────┘                └──────┬───────┘             └──────┬───────┘
       │                               │                            │
       │ WS (接收命令+发心跳)           │ WS (中继命令)              │ WS (发送命令)
       │◄──────────────────────────────┤◄───────────────────────────┤
       │  pause/resume/stop-alarm      │  relay                     │ 暂停/恢复/停止
```

**协议选择**：
- **HTTP POST** 上传报警（Windows → 服务器）：可靠、支持大文件、fire-and-forget
- **WebSocket** 推送+控制（双向）：服务器→Android 推送报警、Android→服务器→Windows 反向控制、Windows 心跳注册

**为什么不全用 WebSocket？** 报警上传包含截图二进制文件（100-300KB），HTTP multipart 是最自然的传输方式，且即使 WS 正在重连，HTTP POST 仍可独立工作，不丢报警。

---

## 二、协议规范

### 2.1 鉴权
共享 API Key（一个家庭级密码字符串），所有端使用同一个 key：
- HTTP：`X-API-Key` 请求头
- WebSocket：连接后首条消息为 auth 消息，5秒内未认证则断开

### 2.2 HTTP 接口

**`POST /api/alert`** — Windows 上传报警
```
Headers:  X-API-Key: <string>
Body:     multipart/form-data
  - "meta" (application/json):
    {
      "deviceId": "uuid",
      "deviceName": "书房",
      "timestamp": "ISO8601",
      "detections": [
        { "label": "person", "confidence": 0.87,
          "bbox": { "x": 100, "y": 200, "w": 80, "h": 160 } }
      ]
    }
  - "screenshot" (image/png): 二进制 PNG

Response 200: { "ok": true, "alertId": "uuid" }
Response 401: { "ok": false, "error": "unauthorized" }
```

**`GET /screenshots/<alertId>.png?key=<apiKey>`** — Android 加载截图

**`GET /health`** — 健康检查（无需鉴权）

### 2.3 WebSocket 消息 (JSON, type 字段区分)

**连接认证（双端 → 服务器）：**
```json
{ "type": "auth", "apiKey": "xxx", "role": "windows|android",
  "deviceId": "uuid", "deviceName": "书房" }
→ 回复: { "type": "auth-result", "success": true }
```

**Windows 心跳（每30秒）：**
```json
{ "type": "heartbeat", "deviceId": "uuid",
  "isMonitoring": true, "isAlarming": false }
```

**服务器 → Android 推送报警：**
```json
{ "type": "alert", "alertId": "uuid",
  "deviceId": "uuid", "deviceName": "书房",
  "timestamp": "ISO8601",
  "detections": [...],
  "screenshotUrl": "/screenshots/<alertId>.png" }
```

**服务器 → Android 推送设备列表（连接时/设备变化时/心跳更新时）：**
```json
{ "type": "device-list", "devices": [
  { "deviceId": "uuid", "deviceName": "书房",
    "online": true, "isMonitoring": true, "isAlarming": false,
    "lastSeen": "ISO8601" }
] }
```

**Android → 服务器 → Windows 反向控制：**
```json
// Android 发送:
{ "type": "command", "targetDeviceId": "uuid",
  "command": "pause|resume|stop-alarm" }
// 服务器转发给目标 Windows:
{ "type": "command", "command": "pause|resume|stop-alarm" }
// 服务器回复 Android:
{ "type": "command-ack", "targetDeviceId": "uuid",
  "command": "pause", "success": true, "reason": "" }
```

### 2.4 截图管理
- 服务器存储：`./data/screenshots/<alertId>.png`
- 自动清理：每小时扫描，删除超过 72 小时的文件（可配置 `SCREENSHOT_TTL_HOURS`）
- 上传大小限制：2MB

---

## 三、服务器实现 (Phase 1)

### 技术选型
| 组件 | 选择 | 理由 |
|------|------|------|
| HTTP | Express 4 | 轻量、生态完善 |
| WebSocket | ws (npm) | 零依赖、性能好、~50KB |
| 文件上传 | multer | Express 标准中间件 |
| UUID | crypto.randomUUID() | Node 20 内置 |
| 进程管理 | systemd | Debian 原生，崩溃自动重启 |
| 数据库 | **无** | 报警仅存内存（每设备最近200条循环缓冲），家庭场景够用 |

### 文件结构
```
VisionGuard_Server/
├── src/
│   ├── index.ts                    # 入口：HTTP + WS 服务器
│   ├── config.ts                   # 环境变量配置 (PORT, API_KEY, TTL)
│   ├── middleware/
│   │   └── auth.ts                 # API Key 校验 (HTTP + WS)
│   ├── routes/
│   │   ├── alert.ts                # POST /api/alert (multer 接收 + 广播)
│   │   └── screenshot.ts           # GET /screenshots/:id.png
│   ├── services/
│   │   ├── ConnectionManager.ts    # WS 连接管理 (按 deviceId/role 跟踪)
│   │   ├── AlertStore.ts           # 内存循环缓冲 (每设备最近200条)
│   │   └── ScreenshotCleanup.ts    # 定时清理过期截图
│   └── models/
│       └── types.ts                # 所有 TypeScript 接口
├── data/screenshots/               # 截图存储 (gitignore)
├── .env                            # PORT=3000, API_KEY=xxx
├── package.json
└── tsconfig.json
```

### ConnectionManager 核心逻辑
```
windowsClients: Map<deviceId, { ws, deviceName, isMonitoring, isAlarming, lastSeen }>
androidClients: Map<deviceId, { ws }>

broadcastAlert(alert)     → 发给所有 android ws
broadcastDeviceList()     → 发给所有 android ws（设备上线/下线/状态变化时）
relayCommand(cmd)         → 找到目标 windows ws 转发，回复 ack 给 android
handleHeartbeat(msg, ws)  → 更新设备状态，如有变化则 broadcastDeviceList
handleDisconnect(ws)      → 从 map 移除，broadcastDeviceList
```

### 部署
```bash
# 一键部署脚本 (SSH 到 VPS 后执行)
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt-get install -y nodejs
cd /opt && git clone <repo> visionguard-server
cd visionguard-server && npm install && npm run build
cp .env.example .env  # 编辑 API_KEY
cp visionguard.service /etc/systemd/system/
systemctl enable --now visionguard
```

预计内存占用：~30-50MB RSS，完全在 1-2G VPS 能力范围内。

---

## 四、Windows 客户端改造 (Phase 2)

### 4A. UI 架构重构 — 左侧菜单导航

**现状问题**：当前所有控制卡片垂直堆叠在左侧滚动面板中，再加服务器推送卡片会更加拥挤。窗口允许用户自由拖拽大小，导致 WinForms 手动布局代码复杂度极高（262行 BuildCards + 大量 Resize 事件同步）。

**改造方案**：固定窗口大小 + 左侧图标菜单导航 + 右侧分页内容区。

#### 新布局结构
```
┌──────┬──────────────────────────────────────────────┐
│ MENU │  CONTENT AREA                                │
│      ├──────────────────────┬───────────────────────┤
│ [📷] │                      │                       │
│ [⚙️] │   Preview Panel      │                       │
│ [🎯] │   (DetectionOverlay) │   Page Content        │
│ [🌐] │                      │   (切换显示)           │
│      │──────────────────────│                       │
│      │   Log Panel          │                       │
│      │   (OwnerDrawListBox) │                       │
│      │                      │                       │
│      ├──────────────────────┴───────────────────────┤
│      │  StatusStrip                                  │
└──────┴───────────────────────────────────────────────┘
```

**菜单页面（4页）：**

| 图标 | 页面 | 内容 |
|------|------|------|
| 📷 捕获 | 捕获区域 | 选择窗口/拖拽选区按钮 + 区域信息 + **开始/停止按钮** |
| ⚙️ 参数 | 检测参数 | 置信度滑块 + 冷却时间 + 铃声设置 + FPS + 线程数 |
| 🎯 目标 | 监控对象 | CocoClassPickerControl（可占满整个页面高度） |
| 🌐 服务器 | 服务器推送 | 服务器地址 + API密钥 + 设备名称 + 连接状态 |

**关键设计决策：**
- **窗口大小写死**：960×640（根据布局计算的最优尺寸），不可拖拽调整。保留高 DPI 适配（AutoScaleMode.Dpi），DPI 变化时窗口按比例缩放。
- **菜单用图标+文字竖排**：宽度固定 72px，每项 64px 高，选中态高亮背景色
- **预览+日志始终可见**（左半部分），页面内容在右半部分切换
- **开始/停止按钮放在"捕获"页**（这是用户最常操作的页面，启动流程在此完成）

#### 实现方式
```
Form1 (960×640, FormBorderStyle.FixedSingle, MaximizeBox=false)
├── _menuPanel (Panel, 72px wide, Dock.Left)
│   ├── MenuButton "捕获" (selected by default)
│   ├── MenuButton "参数"
│   ├── MenuButton "目标"
│   └── MenuButton "服务器"
├── _leftPanel (Panel, ~45% width)
│   ├── DetectionOverlayPanel (70%)
│   └── LogPanel (30%)
├── _pageContainer (Panel, ~remaining width)
│   ├── _pageCapturePanel    (Dock.Fill, Visible=true by default)
│   ├── _pageParamsPanel     (Dock.Fill, Visible=false)
│   ├── _pageTargetsPanel    (Dock.Fill, Visible=false)
│   └── _pageServerPanel     (Dock.Fill, Visible=false)
└── StatusStrip
```

菜单切换逻辑极简：点击菜单项 → 隐藏所有 page panel → 显示目标 page panel → 更新菜单高亮。

**新增 UI 文件：**
```
UI/
└── MenuButton.cs   # 新增：左侧菜单按钮控件（图标+文字，选中态高亮）
```
约 60-80 行，继承自 Control，自绘图标+文字+选中态背景。

### 4B. 代码精简 & 重构

#### 审计发现的冗余问题

| 问题 | 涉及文件 | 处理 |
|------|---------|------|
| COCO 80类标签重复 | YoloOutputParser.cs + CocoClassMap.cs | **合并**：YoloOutputParser 引用 CocoClassMap.EnglishNames，删除自身的硬编码数组 |
| Bitmap Resize 方法重复 | ImagePreprocessor.cs + ScreenCapturer.cs | **删除** ScreenCapturer.Resize()（未被调用），仅保留 ImagePreprocessor 内的 |
| 未使用的 P/Invoke | NativeMethods.cs GetGuiResources | **删除** |
| 未使用的统计字段 | MonitorService.cs _totalFrames/_totalInferenceMs | **删除** |
| ModelSize 硬编码两处 | YoloOutputParser.cs + ImagePreprocessor.cs | **统一**：在 ImagePreprocessor 定义常量，YoloOutputParser 引用 |
| 滚动条隐藏逻辑重复 | HiddenScrollPanel.cs + OwnerDrawListBox.cs | **提取公共基类** ScrollbarHider 或直接在 OwnerDrawListBox 中继承 HiddenScrollPanel 的逻辑 |
| SystemSound 循环用 Sleep 轮询 | AlertService.cs StartSystemSoundLoop | **改用** `token.WaitHandle.WaitOne(1200)` 替代 for+Sleep |

#### AI 友好注释（文件级摘要）

每个 .cs 文件顶部添加结构化摘要注释块，格式统一：

```csharp
// ┌─────────────────────────────────────────────────────────┐
// │ MonitorService.cs                                       │
// │ 角色：主监控循环，定时截图→推理→报警                        │
// │ 线程：Timer回调在 ThreadPool 执行，UI 更新通过事件        │
// │ 依赖：OnnxInferenceEngine, AlertService, ImagePreprocessor│
// │ 对外 API：Start(), Stop(), Pause(), Resume()            │
// │ 事件：FrameProcessed (每帧结果通知 Form1 更新 UI)        │
// └─────────────────────────────────────────────────────────┘
```

**这对 AI 辅助编程非常有用**：当 Claude Code 读到一个文件时，前 5 行就能理解该文件的角色、线程模型、依赖关系和公开 API，无需通读全部代码。这不是给人看的文档，而是给 AI 的"速查摘要"。

所有 .cs 文件都会加此注释（约 18 个文件，每个 4-6 行摘要）。

#### 预估代码量变化

| 度量 | 改造前 | 改造后 | 变化 |
|------|--------|--------|------|
| Form1.cs | 1024 行 | ~750 行 | -27%（BuildCards 拆为4个简单页面方法，删除 Resize 同步代码） |
| 总项目行数 | ~3900 行 | ~3500 行 | -10%（删除重复 + 简化） |
| 新增 ServerPushService | 0 | ~220 行 | +220 |
| 新增 SimpleJson | 0 | ~50 行 | +50 |
| 新增 MenuButton | 0 | ~70 行 | +70 |
| **净增** | | | **~-10 行**（功能增加但代码量持平） |

### 4C. 服务器推送集成（新增功能）

#### 新增文件
```
VisionGuard_Windows/
├── Services/
│   └── ServerPushService.cs    # ★ 新增：HTTP上传 + WS客户端
└── Utils/
    └── SimpleJson.cs           # ★ 新增：轻量JSON序列化/反序列化
```

#### `ServerPushService.cs` 设计

```csharp
public sealed class ServerPushService : IDisposable
{
    // 事件 (Form1 监听以更新 UI)
    event EventHandler<string> ConnectionStateChanged;  // "connected"/"disconnected"/"connecting"
    event EventHandler<string> CommandReceived;          // "pause"/"resume"/"stop-alarm"

    // 配置 (Form1 在加载设置后调用)
    void Configure(string serverUrl, string apiKey, string deviceId, string deviceName);

    // 上传报警 (AlertTriggered 事件处理中调用, fire-and-forget)
    void PushAlert(AlertEvent alert);

    // 心跳 (30秒定时器调用)
    void SendHeartbeat(bool isMonitoring, bool isAlarming);

    // 状态
    bool IsConnected { get; }
    void Disconnect();
}
```

**关键实现要点：**

1. **PushAlert 是 fire-and-forget**：在调用线程克隆 PNG bytes，然后 `Task.Run(async)` 发 HTTP POST。绝不阻塞检测管线。失败时仅 LogManager 记录，本地报警流程不受影响。

2. **WS 客户端用 `System.Net.WebSockets.ClientWebSocket`**（.NET 4.7.2 内置，无需 NuGet）。自动重连：指数退避 1s→2s→4s→...→30s 上限，连接成功后重置。

3. **JSON 序列化用 `System.Web.Script.Serialization.JavaScriptSerializer`**（添加 System.Web.Extensions 引用），避免引入 Newtonsoft.Json。

4. **ServerUrl 为空时整个服务静默不启动**，所有公开方法直接 return。确保不配置服务器时 = 当前版本行为零变化。

#### SettingsStore 新增键
```ini
ServerUrl=http://66.154.112.91:3000
ApiKey=my-family-key
DeviceId=a1b2c3d4-...    # 首次运行自动生成 Guid, 用户不可见
DeviceName=书房            # 用户设置, 默认 = 计算机名
```

#### Form1 集成改动

1. `AlertTriggered` 事件处理中，**在现有逻辑之后**添加一行：
   ```csharp
   _serverPushService.PushAlert(e);  // fire-and-forget, snapshot 内部克隆
   ```
2. `CommandReceived` 事件处理：
   ```csharp
   switch (cmd) {
       case "pause":      _monitorService.Pause(); break;
       case "resume":     _monitorService.Resume(); break;
       case "stop-alarm": _alertService.StopAlarm(); break;
   }
   ```
3. 启动监控后开启 30s 心跳定时器
4. `Dispose()` 中 `_serverPushService.Dispose()`

#### 离线行为保证
| 场景 | 行为 |
|------|------|
| 未配置服务器地址 | ServerPushService 完全静默，= 当前版本 |
| 服务器不可达 | HTTP POST 超时后静默失败，本地截图照存，报警照响 |
| WS 断线 | 自动重连，UI 显示"未连接"，不影响本地功能 |
| API Key 错误 | 服务器拒绝，日志记录，不影响本地功能 |

---

## 五、Android 客户端实现 (Phase 3)

### 新增依赖 (app/build.gradle.kts)
```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")        // WebSocket
implementation("com.google.code.gson:gson:2.11.0")           // JSON
implementation("io.coil-kt:coil-compose:2.7.0")              // 异步图片加载
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
implementation("androidx.lifecycle:lifecycle-service:2.8.7")
implementation("androidx.datastore:datastore-preferences:1.1.1")
implementation("androidx.navigation:navigation-compose:2.8.4")
```

### 文件结构
```
app/src/main/java/com/xgwnje/visionguard_android/
├── VisionGuardApp.kt                      # Application, 单例 DI
├── MainActivity.kt                        # NavHost + 权限 + Service 绑定
├── data/
│   ├── model/
│   │   ├── AlertMessage.kt                # 报警数据类
│   │   ├── DeviceInfo.kt                  # 设备状态数据类
│   │   └── WsMessage.kt                   # 所有 WS 消息类型 (sealed class)
│   ├── repository/
│   │   └── SettingsRepository.kt          # DataStore (serverUrl, apiKey)
│   └── remote/
│       └── WebSocketClient.kt             # OkHttp WS + 自动重连
├── service/
│   ├── AlertForegroundService.kt          # 前台服务，持有 WS 连接
│   └── BootReceiver.kt                    # 开机自启
├── ui/
│   ├── screen/
│   │   ├── SetupScreen.kt                 # 首次配置：服务器地址 + API Key
│   │   ├── AlertListScreen.kt             # 主页：报警列表
│   │   ├── AlertDetailScreen.kt           # 报警详情：全屏截图
│   │   └── DeviceListScreen.kt            # 设备列表 + 控制按钮
│   ├── component/
│   │   ├── AlertCard.kt                   # 报警卡片
│   │   ├── DeviceCard.kt                  # 设备卡片 + 暂停/恢复/停止按钮
│   │   └── ConnectionBanner.kt            # 顶部连接状态条
│   └── viewmodel/
│       ├── AlertViewModel.kt              # 报警列表 StateFlow
│       └── DeviceViewModel.kt             # 设备列表 + 命令发送
└── util/
    └── NotificationHelper.kt              # 通知渠道 + 构建通知
```

### AndroidManifest.xml 权限
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

### 用户体验流程

**首次启动（SetupScreen）：**
```
┌─────────────────────────────────┐
│        🛡️ VisionGuard          │
│                                 │
│   服务器地址                     │
│   ┌───────────────────────────┐ │
│   │ 66.154.112.91:3000        │ │  ← 只需输入 IP:端口
│   └───────────────────────────┘ │
│                                 │
│   API 密钥                      │
│   ┌───────────────────────────┐ │
│   │ ●●●●●●●●●●                │ │
│   └───────────────────────────┘ │
│                                 │
│        [  连接并开始  ]          │
│                                 │
└─────────────────────────────────┘
```
用户只需输入两项信息（IP:端口 + 密钥），点按钮即连接。之后直接进入主界面。

**主界面（AlertListScreen）：**
```
┌─────────────────────────────────┐
│ ● 已连接  (2 台设备在线)    ⚙️  │
├─────────────────────────────────┤
│ ┌─────────────────────────────┐ │
│ │ 🔴 书房       14:30:05      │ │
│ │ person 87%   [缩略图]       │ │
│ └─────────────────────────────┘ │
│ ┌─────────────────────────────┐ │
│ │ 🔴 门口       14:28:12      │ │
│ │ person 92%   [缩略图]       │ │
│ └─────────────────────────────┘ │
│              ...                │
├─────────────────────────────────┤
│    📋 警报       📱 设备        │
└─────────────────────────────────┘
```

**设备控制（DeviceListScreen）：**
```
┌─────────────────────────────────┐
│           在线设备               │
├─────────────────────────────────┤
│ ┌─────────────────────────────┐ │
│ │ 书房              ● 监控中   │ │
│ │  [暂停]  [恢复]  [停止报警]  │ │
│ └─────────────────────────────┘ │
│ ┌─────────────────────────────┐ │
│ │ 门口             ⚠ 报警中   │ │
│ │  [暂停]  [恢复]  [停止报警]  │ │  ← "停止报警"按钮脉冲高亮
│ └─────────────────────────────┘ │
├─────────────────────────────────┤
│    📋 警报       📱 设备        │
└─────────────────────────────────┘
```

按钮智能显示：
- 设备在监控中且未报警 → 只显示"暂停"
- 设备已暂停 → 只显示"恢复"
- 设备报警中 → 突出显示"停止报警"（红色脉冲）
- 设备离线 → 按钮全部禁用灰色

### 后台保活策略
- `AlertForegroundService` + `START_STICKY`：OS 杀死后自动重启
- `BootReceiver`：设备重启后自动启动服务
- OkHttp `pingInterval(25s)`：穿透 NAT，检测死连接
- WS 断线自动重连：指数退避 1s→30s 上限

### 通知
- **报警通知**：HIGH 优先级，Heads-up 弹出，默认铃声 + 振动，BigPicture 样式显示截图
- **常驻通知**：LOW 优先级（前台服务要求），显示"VisionGuard 守护中"+ 连接状态

---

## 六、关键边界场景处理

| 场景 | Windows 行为 | 服务器行为 | Android 行为 |
|------|-------------|-----------|-------------|
| 服务器宕机 | HTTP 超时静默失败，WS 自动重连。本地报警正常 | — | WS 断开，自动重连，常驻通知显示"连接断开" |
| 服务器重启 | WS 重连后自动 auth+心跳注册 | 内存报警历史丢失（可接受） | WS 重连，报警列表清空（新报警会重新推送） |
| Windows 网络断开 | 报警照存本地，PushAlert 静默失败 | 30s 后标记设备离线 | 设备列表显示离线 |
| Android 被 OS 杀死 | — | 设备列表移除该 Android | START_STICKY 重启服务，WS 重连 |
| 短时间内连续报警 | 受 AlertCooldownSeconds(5s) 控制 | 每条独立存储和广播 | 每条独立通知（系统自动堆叠） |
| Android 发命令给离线 Windows | — | 回复 command-ack success:false reason:"device offline" | Toast 提示"设备离线" |
| 多台 Android 同时连接 | — | 广播给所有 Android | 所有 Android 收到相同报警 |

---

## 七、实施顺序

### Phase 0: Windows 代码精简 & UI 重构（先做，为后续集成扫清障碍）

**Step 0.1 — 代码精简（不改变任何功能）**
1. 合并 COCO 标签：YoloOutputParser.cs 引用 CocoClassMap.EnglishNames
2. 删除 ScreenCapturer.Resize()（未使用）
3. 删除 NativeMethods.GetGuiResources（未使用）
4. 删除 MonitorService._totalFrames/_totalInferenceMs（未使用）
5. 统一 ModelSize 常量到 ImagePreprocessor
6. AlertService 系统音循环改用 WaitHandle
7. 添加所有 .cs 文件的 AI 友好摘要注释

**Step 0.2 — UI 架构重构**
1. 新建 MenuButton.cs 自绘控件
2. 重写 Form1.BuildUI()：固定窗口 960×640 + 三区布局（菜单/预览+日志/页面内容）
3. 拆 BuildCards() 为 4 个独立页面构建方法：BuildCapturePage(), BuildParamsPage(), BuildTargetsPage(), BuildServerPage()
4. 实现菜单切换逻辑（显示/隐藏 page panel）
5. 删除所有 Resize 同步代码（固定尺寸后不需要了）
6. 验证：所有现有功能正常，窗口不可缩放，DPI 适配正常

### Phase 1: 服务器（两端都需要它来测试）
1. npm install express ws multer + TypeScript 类型
2. config.ts → types.ts → auth.ts
3. ConnectionManager.ts → AlertStore.ts
4. alert.ts (POST 路由) → screenshot.ts (静态文件)
5. index.ts 组装 → ScreenshotCleanup.ts
6. 本地测试：curl + wscat
7. 部署到 VPS：systemd

### Phase 2: Windows 服务器推送集成
1. SimpleJson.cs — JSON 辅助工具
2. ServerPushService.cs — 完整实现
3. Form1 BuildServerPage() — 填充服务器页面控件
4. Form1 事件集成 — AlertTriggered + CommandReceived + 心跳
5. 测试：触发检测 → 服务器收到 → wscat 观察推送

### Phase 3: Android 客户端
1. 数据层：model/ + repository/ + WebSocketClient
2. 服务层：AlertForegroundService + BootReceiver + NotificationHelper
3. UI层：SetupScreen → AlertListScreen → AlertDetailScreen → DeviceListScreen
4. ViewModel 层：绑定 Service 的 StateFlow
5. MainActivity：NavHost + 权限请求 + Service 绑定
6. 端到端测试

### Phase 4: 打磨
1. 连接状态指示器（三端）
2. 错误提示（密钥错误、服务器不可达）
3. 截图加载状态（Coil shimmer）
4. 通知分组

---

## 八、验证方案

### 单元验证
- 服务器：curl POST /api/alert + wscat 连接验证广播
- Windows：Mock 服务器测试 PushAlert 和 WS 命令接收
- Android：连接真实服务器验证 WS 握手

### 端到端验证
1. 启动服务器 → Windows 配置服务器地址 → Android 配置服务器地址
2. Windows 启动监控 → 触发检测 → 验证 Android 在 3 秒内收到通知+截图
3. Android 点击"暂停" → 验证 Windows 监控暂停
4. Android 点击"恢复" → 验证 Windows 监控恢复
5. Windows 报警中 → Android 点击"停止报警" → 验证 Windows 铃声停止
6. 拔掉 Windows 网线 → 验证 Android 显示设备离线 → 本地报警正常
7. 杀掉 Android app → 验证前台服务自动重启
8. 重启服务器 → 验证双端自动重连

### 关键文件清单
| 文件 | 操作 |
|------|------|
| **Phase 0 — Windows 精简 & UI 重构** | |
| `VisionGuard_Windows/Form1.cs` | 重构（UI 架构改为菜单导航，~1024行→~750行） |
| `VisionGuard_Windows/UI/MenuButton.cs` | 新建（左侧菜单按钮自绘控件） |
| `VisionGuard_Windows/Inference/YoloOutputParser.cs` | 修改（删除重复 COCO 标签，引用 CocoClassMap） |
| `VisionGuard_Windows/Inference/ImagePreprocessor.cs` | 修改（导出 ModelSize 常量） |
| `VisionGuard_Windows/Capture/ScreenCapturer.cs` | 修改（删除未使用的 Resize 方法） |
| `VisionGuard_Windows/Capture/NativeMethods.cs` | 修改（删除未使用的 GetGuiResources） |
| `VisionGuard_Windows/Services/MonitorService.cs` | 修改（删除未使用统计字段） |
| `VisionGuard_Windows/Services/AlertService.cs` | 修改（系统音循环改用 WaitHandle） |
| `VisionGuard_Windows/*.cs (全部 ~18 个文件)` | 添加 AI 友好摘要注释 |
| **Phase 1 — 服务器** | |
| `VisionGuard_Server/src/index.ts` | 重写 |
| `VisionGuard_Server/src/config.ts` | 新建 |
| `VisionGuard_Server/src/middleware/auth.ts` | 新建 |
| `VisionGuard_Server/src/routes/alert.ts` | 新建 |
| `VisionGuard_Server/src/routes/screenshot.ts` | 新建 |
| `VisionGuard_Server/src/services/ConnectionManager.ts` | 新建 |
| `VisionGuard_Server/src/services/AlertStore.ts` | 新建 |
| `VisionGuard_Server/src/services/ScreenshotCleanup.ts` | 新建 |
| `VisionGuard_Server/src/models/types.ts` | 新建 |
| `VisionGuard_Server/package.json` | 修改（加依赖） |
| **Phase 2 — Windows 服务器集成** | |
| `VisionGuard_Windows/Services/ServerPushService.cs` | 新建 |
| `VisionGuard_Windows/Utils/SimpleJson.cs` | 新建 |
| `VisionGuard_Windows/Form1.cs` | 修改（服务器页面 + 事件集成） |
| `VisionGuard_Windows/VisionGuard.csproj` | 修改（加 System.Web.Extensions 引用） |
| **Phase 3 — Android** | |
| `VisionGuard_Android/` 下约 20 个文件 | 新建/重写 |
