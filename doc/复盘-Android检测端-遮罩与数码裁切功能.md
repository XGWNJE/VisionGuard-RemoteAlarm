# 复盘：Android 检测端 —— 遮罩绘制 + 数码裁切功能

> 记录从需求提出到最终落地的完整曲折路线，提炼经验教训，提升后续需求表达精度。

---

## 一、需求概述

**目标**：在 Android 检测端新增"设置监控区域"功能，替代原有的"手动预览"按钮。

**核心功能**：
1. **遮罩绘制**：用户在摄像头预览帧上手动画矩形，遮挡不需要识别的区域（如远处无关区域、反光区域）
2. **数码裁切变焦**：1x ~ 5x 中心裁切，放大远处目标
3. **预处理环节应用**：遮罩在图像预处理时涂黑，让模型完全看不到这些区域
4. **动态分辨率**：随裁切倍率提升采集分辨率（1x=640×480，5x≈2K），避免裁切后画面过糊

**关键约束**：
- 手机固定竖屏，不处理旋转
- 无实时 Preview，仅 ImageAnalysis 推理
- 遮罩绘制必须在**监控未启动时**进行
- 配置变更不热更新 Service，统一在下次启动监控时应用

---

## 二、实现路线（按时间顺序）

### Phase 1：方案设计与基础实现

**涉及文件（9个）**：
1. `data/model/MaskRegion.kt` — 新建遮罩区域数据类（相对坐标 0~1）
2. `data/model/MonitorConfig.kt` — 新增 `maskRegions` + `digitalZoom`
3. `data/repository/SettingsRepository.kt` — 新增 DataStore 持久化字段（JSON 序列化遮罩列表）
4. `ui/screen/MonitorScreen.kt` — "手动预览"按钮改为"设置监控区域"
5. `ui/screen/MaskEditorScreen.kt` — 新建遮罩编辑器（全屏 Dialog）
6. `inference/ImagePreprocessor.kt` — 新增 `cropAndMask()` 中心裁切 + 遮罩涂黑
7. `service/MonitorService.kt` — `processFrame()` 插入裁切/遮罩流程
8. `service/DetectorForegroundService.kt` — 动态分辨率策略 + 预览帧捕获
9. `MainActivity.kt` — 状态管理、Service 绑定、配置加载/保存

**坐标映射管道（6层）**：
```
用户触摸（像素）→ 相对坐标（0~1）→ 原始帧像素 → 裁切后像素 → inputSize → 检测框 → 绘制
```

---

### Phase 2：UI/UX 问题修复（密集调试期）

| 问题 | 现象 | 根因 | 修复 |
|---|---|---|---|
| 编辑器无法退出 | 进入后按返回键无响应 | 无 BackHandler 拦截 | `BackHandler(enabled = showMaskEditor)` |
| 遮罩绘制不正确 | 拉出小框后自动缩回消失 | 使用了 `dragAmount`（增量）而非 `change.position`（绝对位置） | `detectDragGestures` 中改用 `change.position` |
| 画布显示灰色网格 | 无摄像头画面背景 | 未实现预览帧捕获 | Service 新增 `capturePreviewFrame()` 临时绑定 CameraX 获取单帧 |
| 确认/取消按钮不可见 | 编辑器底部按钮被挤出屏幕 | `weight(1f)` + 无滚动导致内容溢出 | `Column` 添加 `verticalScroll`，画布 `Box` 移除 `weight` |
| 二次编辑显示旧帧 | 第二次打开编辑器仍显示上次的帧 | `lastAlertFrame != null` 时复用旧帧 | 每次打开都调用 `capturePreviewFrame()` |
| 应用崩溃 | `IllegalStateException: Not in application's main thread` | `ProcessCameraProvider.unbindAll()` 在 CameraX 分析器线程调用 | 包装到 `ContextCompat.getMainExecutor().execute { ... }` |

---

### Phase 3：热更新移除

**用户反馈**："为了避免逻辑干扰，没有必要热更新的设置统一在停止监控的时候进行"

**修改**：从 `saveConfig` 中移除 `service.updateConfig(newConfig)`，所有配置变更只在用户下次点击"开始监控"时通过 `service.startMonitoring(config)` 生效。

---

### Phase 4：持久化不生效（最曲折的阶段）

#### 第一轮修复（表象）

**用户反馈**："测试发现 Android 检测端的持久化不生效"

**诊断**：
- `saveConfig` 使用 `rememberCoroutineScope()`（composable 级别），Activity 销毁时可能被取消
- 7 个独立的 `edit()` 调用，文件 IO 次数过多
- Slider `onValueChange` 每帧触发，并发写入堆积

**修复**：
1. `SettingsRepository` 新增 `saveMonitorConfig(config)` — 单次 `edit` 原子写入 8 个字段
2. `MainActivity` 改用 `LocalLifecycleOwner.current.lifecycleScope`（Activity 级别）
3. `SettingsScreen` Slider 改为 `onValueChangeFinished`，只在释放时保存

**结果**：仍不生效。

#### 第二轮修复（并发覆盖）

**用户反馈**："名字持久化成功了，其他设置都没成功，每次都恢复默认"

**诊断**：
- 日志显示 `saveConfig` 3 秒内被调用 6 次，且值都是默认值
- 多个协程以不确定顺序执行，保存默认值的协程覆盖了用户修改值
- 但更深入分析日志后发现：**DataStore 读写实际上是正确的**（cooldown=213s 被成功保存和加载）

**修复**：
1. `saveConfig` 引入 `Job` 防抖（延迟 500ms，连续操作只保存最终状态）
2. 协程执行时读取当前 `config` 状态，避免 lambda 捕获的旧快照覆盖新值
3. 添加调用栈日志追踪触发源

**结果**：日志显示持久化确实生效，但用户反馈 UI 仍显示默认值。

#### 第三轮修复（找到根因）

**关键发现**：
- `DetectorForegroundService` 的 `_currentConfigFlow` 初始值是 `MonitorConfig()`（默认值）
- `initModelAndServices()` 从 DataStore 读取了配置到 `currentConfig`，但**漏了同步 `_currentConfigFlow`**
- `MainActivity` 绑定 Service 后，`LaunchedEffect` 立即收集到默认值，覆盖了已从 DataStore 加载的配置

**修复**：
1. `DetectorForegroundService.initModelAndServices()`：
   - 完整读取所有配置字段（confidence、cooldown、targets、samplingRate、maskRegions、digitalZoom）
   - 用 `MonitorConfig(...)` 重建完整配置
   - **同步 `_currentConfigFlow.value = currentConfig`**
2. `MainActivity` 的 `currentConfigFlow.collect` 增加保护：`if (configLoaded && remoteConfig != config)` 才同步

**结果**：持久化真正生效。

---

## 三、技术难点深度记录

### 1. 坐标映射的正确性

**6 层映射 pipeline**：

```
1. 触摸事件（Canvas 像素坐标）
   ↓ / size.width, / size.height
2. 相对坐标（0~1）存储在 MaskRegion
   ↓ * bitmap.width, * bitmap.height
3. 原始帧像素坐标
   ↓ - cropLeft, - cropTop（若数码裁切 > 1x）
4. 裁切后帧像素坐标
   ↓ / config.inputSize（YOLO 输入尺寸）
5. 模型输入坐标系
   ↓ * scaleX, * scaleY（裁切后 Bitmap 尺寸 / inputSize）
6. 检测框在裁切后帧上的坐标
   ↓ 报警绘制在 croppedBitmap 上（当前未加回 offset，因绘制基于裁切后坐标系）
```

**关键约束**：所有坐标映射必须基于**实际 Bitmap 尺寸**，不能假设 CameraX 返回的分辨率等于请求的分辨率。

### 2. DataStore 持久化的陷阱

| 陷阱 | 说明 |
|---|---|
| 协程作用域层级 | `rememberCoroutineScope` 绑定 composable 生命周期，Activity 销毁时被取消；改用 `lifecycleScope` 更持久 |
| 并发写入覆盖 | 多个 `saveConfig` 调用启动多个协程，lambda 捕获的快照可能过时，后执行的协程覆盖先执行的 |
| 外部状态覆盖 | Service 的 StateFlow 默认值可能在 UI 加载配置后覆盖之，需确保两端初始化顺序一致 |
| 批量写入 | 7 次独立 `edit()` 不如 1 次原子 `edit()` 可靠 |

### 3. CameraX 动态分辨率

```kotlin
// 目标采集分辨率 = 640×480 × digitalZoom，上限约 2K
val targetSize = when {
    currentConfig.digitalZoom >= 5f -> Size(1920, 1080)
    currentConfig.digitalZoom >= 4f -> Size(1920, 1080)
    currentConfig.digitalZoom >= 3f -> Size(1920, 1080)
    currentConfig.digitalZoom >= 2f -> Size(1280, 960)
    else -> Size(640, 480)
}
```

**注意**：`ResolutionStrategy` 请求的尺寸和实际返回的尺寸可能不一致，必须在运行时根据实际 Bitmap 尺寸做裁切。

---

## 四、经验教训（如何成为更高的 AI 驾驶员）

### 对于需求表达

| 建议 | 本次教训 |
|---|---|
| **尽早声明约束** | 手机固定竖屏、不处理旋转、无实时 Preview 等约束如果在最开始就说明，可以避免大量无关讨论 |
| **明确"不做"的范围** | "不热更新 Service"这个约束在实现后才提出，导致需要回头移除已写好的热更新逻辑 |
| **提供验收标准** | 如果早期就明确"修改设置→杀应用→重新打开→应显示修改值"，可以更早发现持久化问题 |
| **区分症状和根因** | "持久化不生效"是症状，实际根因是 Service 的 StateFlow 默认值覆盖，而非 DataStore 写入失败 |

### 对于调试协作

| 建议 | 本次教训 |
|---|---|
| **日志是最好的证据** | 第二轮修复时，用户提供的 `VG_Persist` 日志直接揭示了"并发覆盖"和"实际已生效"两个关键信息 |
| **提供完整时间线** | 包含操作顺序的日志（何时修改、何时杀应用、何时重启）比孤立的错误截图更有价值 |
| **区分 UI 表现和数据层** | "UI 显示默认值"≠"DataStore 没写入"，可能是数据被后续覆盖了 |

### 对于复杂功能

| 建议 | 本次教训 |
|---|---|
| **坐标映射要显式画出 pipeline** | 6 层映射若不显式列出，极易在某个环节出错 |
| **状态同步要画出时序图** | Activity、Service、DataStore 三方的状态同步若有时序图，能更早发现"Service 默认值覆盖"问题 |
| **持久化要双向验证** | 写入后立即读取验证，比单纯相信"无异常=成功"更可靠 |

---

## 五、最终变更文件清单

| 文件 | 变更类型 | 关键修改 |
|---|---|---|
| `data/model/MaskRegion.kt` | 新建 | 相对坐标遮罩区域 |
| `data/model/MonitorConfig.kt` | 修改 | 新增 `maskRegions` + `digitalZoom` |
| `data/repository/SettingsRepository.kt` | 修改 | 新增 `saveMonitorConfig()` 批量写入 |
| `ui/screen/MonitorScreen.kt` | 修改 | "设置监控区域"按钮 |
| `ui/screen/MaskEditorScreen.kt` | 新建 | 拖拽绘制遮罩 + 裁切滑块 |
| `ui/screen/SettingsScreen.kt` | 修改 | Slider `onValueChangeFinished` 防抖 |
| `inference/ImagePreprocessor.kt` | 修改 | `cropAndMask()` 中心裁切 + 遮罩涂黑 |
| `service/MonitorService.kt` | 修改 | `processFrame()` 插入裁切/遮罩流程 |
| `service/DetectorForegroundService.kt` | 修改 | 动态分辨率 + 预览帧捕获 + **完整恢复配置到 `currentConfigFlow`** |
| `MainActivity.kt` | 修改 | Service 绑定 + 配置加载/保存 + **防覆盖保护** |

---

## 六、版本

- 功能版本：v3.6.0
- 文档日期：2026-04-27
