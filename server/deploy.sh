#!/usr/bin/env bash
set -euo pipefail

# VisionGuard Server — 一键同步部署脚本
# 用法: bash server/deploy.sh [--full]
#   默认:  仅同步 src/ 并重建重启
#   --full: 同时同步 package.json 并 npm install

VPS_HOST="root@66.154.112.91"
VPS_PATH="/opt/visionguard/VisionGuard_Server"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

FULL=false
[[ "${1:-}" == "--full" ]] && FULL=true

echo "=== VisionGuard Server 部署 ==="
echo "本地: $SCRIPT_DIR"
echo "远程: $VPS_HOST:$VPS_PATH"
echo ""

# 1. 类型检查
echo "[1/4] 本地类型检查..."
cd "$SCRIPT_DIR" && npx tsc --noEmit
echo "  OK"
echo ""

# 2. 同步 src/
echo "[2/4] 同步 src/ ..."
ssh "$VPS_HOST" "rm -rf ${VPS_PATH}/src_new && mkdir -p ${VPS_PATH}/src_new"
scp -r "$SCRIPT_DIR/src/" "$VPS_HOST:${VPS_PATH}/src_new/"
ssh "$VPS_HOST" "cd ${VPS_PATH} && rm -rf src && mv src_new/src src && rm -rf src_new"
echo "  OK"
echo ""

# 3. 同步 package.json (--full)
if $FULL; then
    echo "[3/4] 同步 package.json + npm install ..."
    scp "$SCRIPT_DIR/package.json" "$VPS_HOST:${VPS_PATH}/package.json"
    ssh "$VPS_HOST" "cd ${VPS_PATH} && npm install"
    echo "  OK"
    echo ""
else
    echo "[3/4] 跳过 (仅 --full 模式同步依赖)"
    echo ""
fi

# 4. 远程编译
echo "[4/4] 远程编译..."
ssh "$VPS_HOST" "cd ${VPS_PATH} && npm run build"
echo "  OK"
echo ""

# 5. 重启服务
echo "[5/5] 重启 visionguard 服务..."
ssh "$VPS_HOST" "systemctl restart visionguard && sleep 2 && systemctl status visionguard --no-pager -l | head -15"
echo ""

REMOTE_VER=$(ssh "$VPS_HOST" "node -e \"console.log(require('${VPS_PATH}/package.json').version)\"" 2>/dev/null || echo "unknown")
echo "=== 部署完成 — 服务器版本: ${REMOTE_VER} ==="
