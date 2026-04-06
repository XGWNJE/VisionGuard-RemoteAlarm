# VisionGuard 项目升级开发计划：Debian 消息中继与 Android 接收

## Context
用户希望将目前的 Windows 本地单机监控报警系统，升级为支持远程接收报警的系统。具体需求是：搭建一个运行在 Debian 上的服务器作为消息中继站；Windows 客户端检测到目标后向服务器推送消息和截图；Android 客户端接收消息、播放铃声并显示报警来源和画面。

由于不希望强依赖 FCM (Google 推送服务) 且服务器是自定义的 Debian 环境，我们采用 **HTTP (上传) + WebSocket (下发推送)** 的架构。

## Phase 1: Debian Server (消息中继中枢)
使用 Node.js 搭建轻量级中继服务器，负责接收图片并广播消息。

**服务器信息**: 
*   IP 地址: `66.154.112.91`
*   环境部署: 使用 SSH (`root` 用户及密码) 登录到该 VPS 进行服务端部署。

**目录**: `VisionGuard_Server/`



## Phase 2: Windows PC Push Client (推送端)
修改现有的 C# 项目，使其在触发报警时将数据异步上传至 Debian 服务器。

**目录**: `VisionGuard_Windows/`



## Phase 3: Android Client (接收端)
完善 Android 项目，实现长连接、后台接收、播放铃声及 UI 显示。

**目录**: `VisionGuard_Android/`

