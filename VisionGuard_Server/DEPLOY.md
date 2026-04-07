# VisionGuard Server — 部署记录

## 服务器信息

| 项目         | 值                          |
|--------------|-----------------------------|
| IP           | 66.154.112.91               |
| 主机名       | server.xgwnje.com           |
| 系统         | Debian 12 x86_64            |
| 登录方式     | `ssh root@66.154.112.91`    |

## 服务器目录结构

```
/opt/visionguard/VisionGuard_Server/
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

| 项目         | 值                                                       |
|--------------|----------------------------------------------------------|
| 服务名       | `visionguard`                                            |
| 服务文件     | `/etc/systemd/system/visionguard.service`                |
| 运行命令     | `/usr/bin/node /opt/visionguard/VisionGuard_Server/dist/index.js` |
| 开机自启     | 已启用 (enabled)                                         |

## 快速更新流程

### 1. 从 Windows 上传修改的文件（在本地 PowerShell 执行）

```powershell
# 上传 ConnectionManager.ts
scp "d:\Object code\VisionGuard\VisionGuard_Server\src\services\ConnectionManager.ts" root@66.154.112.91:/opt/visionguard/VisionGuard_Server/src/services/ConnectionManager.ts

# 上传 types.ts
scp "d:\Object code\VisionGuard\VisionGuard_Server\src\models\types.ts" root@66.154.112.91:/opt/visionguard/VisionGuard_Server/src/models/types.ts
```

### 2. SSH 登录服务器，编译并重启

```bash
ssh root@66.154.112.91
cd /opt/visionguard/VisionGuard_Server && npm run build && systemctl restart visionguard && systemctl status visionguard --no-pager
```

### 3. 查看实时日志

```bash
journalctl -u visionguard -f
```

### 4. 仅查看最近日志

```bash
journalctl -u visionguard -n 50 --no-pager
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

| 端口 | 用途                          |
|------|-------------------------------|
| 3000 | HTTP API + WebSocket (ws://)  |

> 如需 HTTPS/WSS，需在前置 Nginx 配置 SSL 反代到 3000

## 最后更新

- 日期：2026-04-08
- 内容：新增 `set-config` WS 消息类型（Android 远程调参）；新增 `command-ack` Windows→Android 回传；`handleCommand` 改为 relayed 模式
