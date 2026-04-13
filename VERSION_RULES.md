# VisionGuard 版本管理规则

## 当前版本

```
3.0.0
```

---

## 版本号格式

```
主版本.次版本.修订号
```

三端版本必须保持同步：

| 端 | 文件位置 | 字段 | 示例 |
|---|---|---|---|
| **Server** | `server/package.json` | `version` | `"3.0.0"` |
| **Android** | `receiver/android/app/build.gradle.kts` | `versionName` | `"3.0.0"` |
| **Android** | `receiver/android/app/build.gradle.kts` | `versionCode` | `3`（十进制递增） |
| **Windows** | `detector/windows/VisionGuard.csproj` | `ApplicationVersion` | `3.0.0.*` |
| **Windows** | `detector/windows/Properties/AssemblyInfo.cs` | `AssemblyVersion` | `"3.0.0.0"` |
| **Windows** | `detector/windows/Properties/AssemblyInfo.cs` | `AssemblyFileVersion` | `"3.0.0.0"` |

---

## 版本号更新规则

每次提交按以下规则判定是否需要升级：

### `feat:` → 次版本 +0.1.0（新增功能）
```
3.0.0 → 3.1.0
```
**适用场景**：新增功能、新增端支持、新增协议消息类型、破坏性 UI 变更

### `fix:` / `refactor:` / `perf:` → 修订号 +0.0.1（修复/重构/性能）
```
3.0.0 → 3.0.1
3.0.1 → 3.0.2
```
**适用场景**：Bug 修复、代码重构（不改变外部行为）、性能优化

### `chore:` / `docs:` / `style:` → 不升级版本
```
3.0.0 → 3.0.0（不变）
```
**适用场景**：CI/CD 配置、文档更新、代码格式调整

### 主版本 +1.0.0（破坏性变更）
```
3.0.0 → 4.0.0
```
**适用场景**：API 不兼容、协议格式破坏性变更、数据库结构变更
（极少发生，需要在 commit message 标题明确注明 BREAKING CHANGE）

---

## 提交时自动版本号机制

项目使用 `prepare-commit-msg` git hook 自动更新版本号。

### 工作原理

1. 开发者执行 `git commit -m "feat: xxx"`
2. Hook 根据 commit message 前缀自动判断 bump 类型
3. 自动更新所有三端的版本号文件
4. 将版本号变更 staged 到同一 commit
5. 完成提交

### 手动触发（指定版本号）

```bash
# 直接编辑 VERSION 文件后提交（跳过自动 bump）
echo "3.1.0" > VERSION
git add VERSION && git commit -m "chore: bump version"
```

---

## VERSION 文件

项目根目录的 `VERSION` 文件是唯一真实版本号来源，所有平台的版本号均从它派生。

每次成功提交后，该文件会被 hook 更新。

```
cat VERSION
3.0.0
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
- [ ] Server / Android / Windows 三端版本号一致
- [ ] Server `package.json` version 已更新
- [ ] Android `versionCode` 已递增
- [ ] Windows `ApplicationVersion` / `AssemblyVersion` 已更新
- [ ] `git log` 中的版本号变更与实际功能匹配
