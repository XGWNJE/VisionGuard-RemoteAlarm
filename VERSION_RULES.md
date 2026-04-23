# VisionGuard 版本管理规则

## 当前版本

```
3.5.0
```

---

## 版本号格式

```
主版本.次版本.修订号
```

四端版本必须保持同步：

| 端 | 文件位置 | 字段 | 示例 |
|---|---|---|---|
| **Server** | `server/package.json` | `version` | `"3.5.0"` |
| **Receiver Android** | `receiver/android/app/build.gradle.kts` | `versionName` | `"3.5.0"` |
| **Receiver Android** | `receiver/android/app/build.gradle.kts` | `versionCode` | `3500` |
| **Detector Android** | `detector/android/app/build.gradle.kts` | `versionName` | `"3.5.0"` |
| **Detector Android** | `detector/android/app/build.gradle.kts` | `versionCode` | `3500` |
| **Windows** | `detector/windows/VisionGuard.csproj` | `ApplicationVersion` | `3.5.0.*` |
| **Windows** | `detector/windows/Properties/AssemblyInfo.cs` | `AssemblyVersion` | `"3.5.0.0"` |

---

## 版本号更新规则

### `feat:` → 次版本 +0.1.0（新增功能）
```
3.5.0 → 3.6.0
```
**适用场景**：新增功能、新增端支持、新增协议消息类型、破坏性 UI 变更

### `fix:` / `refactor:` / `perf:` → 修订号 +0.0.1（修复/重构/性能）
```
3.5.0 → 3.5.1
```
**适用场景**：Bug 修复、代码重构（不改变外部行为）、性能优化

### `chore:` / `docs:` / `style:` → 不升级版本
```
3.5.0 → 3.5.0（不变）
```
**适用场景**：CI/CD 配置、文档更新、代码格式调整

### 主版本 +1.0.0（破坏性变更）
```
3.5.0 → 4.0.0
```
**适用场景**：API 不兼容、协议格式破坏性变更、数据库结构变更
（极少发生，需要在 commit message 标题明确注明 BREAKING CHANGE）

---

## 自动版本号机制

项目使用 `prepare-commit-msg` git hook 自动更新版本号。

### 触发条件（需同时满足）

1. commit message 符合 conventional commit 格式且类型为 `feat`/`fix`/`perf`/`refactor`
2. **版本文件未被用户手动修改并 staged**
3. **不是 amend 操作**
4. **环境变量 `SKIP_VERSION_BUMP` 未设置**
5. **commit message 不包含 `[no-bump]`**

### 跳过自动 bump 的方式

| 方式 | 命令 |
|---|---|
| 环境变量 | `SKIP_VERSION_BUMP=1 git commit -m "feat: xxx"` |
| commit 标记 | `git commit -m "feat: xxx [no-bump]"` |
| amend | `git commit --amend` |
| 手动 staged 版本文件 | 先 `git add VERSION` 再提交 |

### 手动触发版本更新

```bash
# 交互式选择 bump 类型
bash scripts/bump-version.sh

# 直接指定 bump 类型
bash scripts/bump-version.sh patch   # 修订号 +1
bash scripts/bump-version.sh minor   # 次版本 +1
bash scripts/bump-version.sh major   # 主版本 +1
```

---

## VERSION 文件

项目根目录的 `VERSION` 文件是唯一真实版本号来源，所有平台的版本号均从它派生。

```
cat VERSION
3.5.0
```

---

## 版本号变更必须包含在同一个 commit

**错误示例**：
```
commit A: "feat: 新功能"   ← 没改版本
commit B: "bump version"  ← 版本和代码分离
```

**正确示例**：
```
commit A: "feat: 新功能"   ← 自动包含版本号变更
```

---

## 发布检查清单

每次发布前确认：
- [ ] `VERSION` 文件已更新到目标版本
- [ ] Server / Android (receiver + detector) / Windows 四端版本号一致
- [ ] Server `package.json` version 已更新
- [ ] Android `versionCode` 已递增
- [ ] Windows `ApplicationVersion` / `AssemblyVersion` 已更新
- [ ] `git log` 中的版本号变更与实际功能匹配
