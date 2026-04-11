# VisionGuard Server — 部署指南

## 服务器信息

| 项目     | 值                              |
|----------|----------------------------------|
| IP       | `66.154.112.91`                  |
| 主机名   | `server.xgwnje.com`              |
| 系统     | Debian 12 x86_64                 |
| 登录方式 | `ssh root@66.154.112.91`        |
| 服务路径 | `/opt/visionguard/VisionGuard_Server` |

## 服务器目录结构

```
{VG_SERVER_PATH}/
├── src/
│   ├── services/
│   │   └── ConnectionManager.ts   ← WebSocket 连接管理
│   ├── models/
│   │   └── types.ts               ← 所有 TS 接口定义
│   ├── middleware/
│   ├── routes/
│   └── config.ts
├── dist/                          ← tsc 编译输出（勿手动修改）
│   └── index.js                   ← 实际运行入口
├── package.json
└── .env                           ← API Key 等敏感配置（不入 git）
```

## systemd 服务

| 项目     | 值                                                        |
|----------|-----------------------------------------------------------|
| 服务名   | `visionguard`                                             |
| 服务文件 | `/etc/systemd/system/visionguard.service`                 |
| 开机自启 | 已启用 (enabled)                                          |

## 快速更新流程

### 1. 上传修改的源文件

```bash
# 上传单个文件示例
scp /path/to/local/ConnectionManager.ts root@$(SERVER_HOST):$(VG_SERVER_PATH)/src/services/ConnectionManager.ts

# 上传所有 src 文件（完整更新）
scp -r ./src root@$(SERVER_HOST):$(VG_SERVER_PATH)/src/
```

### 2. 编译并重启

```bash
ssh root@$(SERVER_HOST)
cd $(VG_SERVER_PATH) && npm run build && systemctl restart visionguard && systemctl status visionguard --no-pager
```

### 3. 查看实时日志

```bash
journalctl -u visionguard -f
```

### 4. 仅查看最近日志

```bash
journalctl -u visionguard -n 50 --no-pager
```

### 5. 诊断过滤（结构化日志格式：`[ws][ISO时间戳]`）

```bash
# 查看所有 WebSocket 连接事件（上线/断开/错误）
journalctl -u visionguard -n 100 --no-pager | grep "\[ws\]"

# 仅看连接/断开事件
journalctl -u visionguard -f | grep -E "上线|断开|因错误移除"

# 查看幽灵连接清理记录
journalctl -u visionguard -n 200 --no-pager | grep "幽灵清理"

# 查看定时推送（每 30s 一条，确认服务正常运行）
journalctl -u visionguard -n 100 --no-pager | grep "定时推送"

# 查看连接错误
journalctl -u visionguard -n 100 --no-pager | grep "连接错误"

# 统计当前在线设备数（看最新一条含在线数的日志）
journalctl -u visionguard -n 200 --no-pager | grep "在线" | tail -5
```

## 常用运维命令

```bash
# 查看服务状态
systemctl status visionguard

# 重启服务
systemctl restart visionguard

# 停止服务
systemctl stop visionguard

# 启动服务
systemctl start visionguard

# 查看端口监听（确认 3000 端口）
ss -tlnp | grep node
```

## 端口说明

| 端口 | 用途                         |
|------|------------------------------|
| 3000 | HTTP API + WebSocket (ws://) |

> 如需 HTTPS/WSS，需在前置 Nginx 配置 SSL 反代到 3000

## 初始部署（全新机器）

```bash
# 1. 克隆/上传项目到服务器
scp -r ./VisionGuard_Server root@$(SERVER_HOST):/opt/visionguard/

# 2. 安装依赖
ssh root@$(SERVER_HOST)
cd $(VG_SERVER_PATH) && npm install

# 3. 配置 .env（API Key 等）
vim $(VG_SERVER_PATH)/.env

# 4. 编译
npm run build

# 5. 注册 systemd 服务
cp /opt/visionguard/visionguard.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now visionguard
```
