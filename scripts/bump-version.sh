#!/bin/bash
# bump-version.sh — 手动触发版本号更新
# 用法:
#   ./scripts/bump-version.sh minor    # 次版本 +1 (3.0.0 → 3.1.0)
#   ./scripts/bump-version.sh patch    # 修订号 +1 (3.0.0 → 3.0.1)
#   ./scripts/bump-version.sh major    # 主版本 +1 (3.0.0 → 4.0.0)
#   ./scripts/bump-version.sh          # 交互式选择

set -e

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
VERSION_FILE="$REPO_ROOT/VERSION"

if [ ! -f "$VERSION_FILE" ]; then
    echo "错误: VERSION 文件不存在"
    exit 1
fi

CURRENT_VERSION=$(cat "$VERSION_FILE" | tr -d '[:space:]')
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"
MAJOR=${MAJOR:-0}
MINOR=${MINOR:-0}
PATCH=${PATCH:-0}

echo "当前版本: $CURRENT_VERSION"
echo ""

# 解析参数或交互式选择
BUMP_TYPE="${1:-}"

if [ -z "$BUMP_TYPE" ]; then
    echo "选择 bump 类型:"
    echo "  1) patch — 修订号 +1 (fix/perf/refactor)"
    echo "  2) minor — 次版本 +1 (feat)"
    echo "  3) major — 主版本 +1 (BREAKING CHANGE)"
    echo ""
    read -p "输入数字 [1-3]: " choice
    case "$choice" in
        1) BUMP_TYPE="patch" ;;
        2) BUMP_TYPE="minor" ;;
        3) BUMP_TYPE="major" ;;
        *) echo "无效选择"; exit 1 ;;
    esac
fi

case "$BUMP_TYPE" in
    patch)
        NEW_PATCH=$((PATCH + 1))
        NEW_VERSION="$MAJOR.$MINOR.$NEW_PATCH"
        ;;
    minor)
        NEW_MINOR=$((MINOR + 1))
        NEW_VERSION="$MAJOR.$NEW_MINOR.0"
        ;;
    major)
        NEW_MAJOR=$((MAJOR + 1))
        NEW_VERSION="$NEW_MAJOR.0.0"
        ;;
    *)
        echo "错误: 未知 bump 类型 '$BUMP_TYPE'，可选: patch / minor / major"
        exit 1
        ;;
esac

echo ""
echo "新版本: $NEW_VERSION"
echo ""
read -p "确认更新? [y/N] " confirm
if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
    echo "已取消"
    exit 0
fi

# ── 更新各端版本文件 ──────────────────────────────────────────

UPDATE_LOG=""

# 1. Server: package.json
PKG_JSON="$REPO_ROOT/server/package.json"
if [ -f "$PKG_JSON" ]; then
    sed -i 's/"version": "'"$CURRENT_VERSION"'"/"version": "'"$NEW_VERSION"'"/' "$PKG_JSON"
    UPDATE_LOG="${UPDATE_LOG}\n  ✓ server/package.json"
fi

# 2. receiver/android: build.gradle.kts
RX_GRADLE="$REPO_ROOT/receiver/android/app/build.gradle.kts"
if [ -f "$RX_GRADLE" ]; then
    sed -i 's/versionName = "'"$CURRENT_VERSION"'"/versionName = "'"$NEW_VERSION"'"/' "$RX_GRADLE"
    NEW_VERSION_CODE=$((MAJOR * 1000 + MINOR * 100 + PATCH))
    sed -i 's/versionCode = [0-9]*/versionCode = '"$NEW_VERSION_CODE"'/' "$RX_GRADLE"
    UPDATE_LOG="${UPDATE_LOG}\n  ✓ receiver/android/build.gradle.kts (code=$NEW_VERSION_CODE)"
fi

# 3. detector/android: build.gradle.kts
DT_GRADLE="$REPO_ROOT/detector/android/app/build.gradle.kts"
if [ -f "$DT_GRADLE" ]; then
    sed -i 's/versionName = "'"$CURRENT_VERSION"'"/versionName = "'"$NEW_VERSION"'"/' "$DT_GRADLE"
    NEW_VERSION_CODE=$((MAJOR * 1000 + MINOR * 100 + PATCH))
    sed -i 's/versionCode = [0-9]*/versionCode = '"$NEW_VERSION_CODE"'/' "$DT_GRADLE"
    UPDATE_LOG="${UPDATE_LOG}\n  ✓ detector/android/build.gradle.kts (code=$NEW_VERSION_CODE)"
fi

# 4. Windows: VisionGuard.csproj
CSPROJ="$REPO_ROOT/detector/windows/VisionGuard.csproj"
if [ -f "$CSPROJ" ]; then
    sed -i 's|<ApplicationVersion>.*</ApplicationVersion>|<ApplicationVersion>'"$NEW_VERSION"'.%2a</ApplicationVersion>|' "$CSPROJ"
    sed -i 's|<ApplicationRevision>[0-9]*</ApplicationRevision>|<ApplicationRevision>0</ApplicationRevision>|' "$CSPROJ"
    UPDATE_LOG="${UPDATE_LOG}\n  ✓ windows/VisionGuard.csproj"
fi

# 5. Windows: AssemblyInfo.cs
ASMINFO="$REPO_ROOT/detector/windows/Properties/AssemblyInfo.cs"
if [ -f "$ASMINFO" ]; then
    ASM_VERSION="$NEW_VERSION.0"
    sed -i 's/AssemblyVersion("'"$CURRENT_VERSION"'\.[0-9]*")/AssemblyVersion("'"$ASM_VERSION"'")/' "$ASMINFO"
    sed -i 's/AssemblyFileVersion("'"$CURRENT_VERSION"'\.[0-9]*")/AssemblyFileVersion("'"$ASM_VERSION"'")/' "$ASMINFO"
    UPDATE_LOG="${UPDATE_LOG}\n  ✓ windows/Properties/AssemblyInfo.cs"
fi

# 6. VERSION 文件
echo "$NEW_VERSION" > "$VERSION_FILE"
UPDATE_LOG="${UPDATE_LOG}\n  ✓ VERSION"

echo ""
echo "版本号已更新: $CURRENT_VERSION → $NEW_VERSION"
echo -e "$UPDATE_LOG"
echo ""
echo "运行以下命令提交版本变更:"
echo "  git add VERSION server/package.json receiver/android/app/build.gradle.kts detector/android/app/build.gradle.kts detector/windows/VisionGuard.csproj detector/windows/Properties/AssemblyInfo.cs"
echo "  git commit -m \"chore: bump version to $NEW_VERSION [no-bump]\""
