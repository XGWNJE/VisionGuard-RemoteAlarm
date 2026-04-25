// ┌─────────────────────────────────────────────────────────┐
// │ index.ts                                                │
// │ 角色：服务器入口 — 组装 HTTP + WebSocket 服务器           │
// │ 职责：加载配置 → 创建 Express app → 挂载路由 →           │
// │       创建 HTTP server → 附加 WS server → 启动监听       │
// │ 对外 API：无 (入口文件)                                  │
// └─────────────────────────────────────────────────────────┘

import http from 'http';
import express from 'express';
import { WebSocketServer } from 'ws';
import { config, validateConfig } from './config';
import alertRouter from './routes/alert';
import alertsQueryRouter from './routes/alerts';
import screenshotRouter from './routes/screenshot';
import { handleConnection, initPing } from './services/ConnectionManager';
import { startCleanupTimer } from './services/ScreenshotCleanup';
import { cleanupExpiredAlerts } from './services/AlertStore';

// ── 加载 .env (简易实现，无需 dotenv 依赖) ─────────────────
import fs from 'fs';
import path from 'path';

function loadEnv(): void {
  const envPath = path.resolve(__dirname, '..', '.env');
  if (!fs.existsSync(envPath)) return;
  const lines = fs.readFileSync(envPath, 'utf-8').split('\n');
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eqIdx = trimmed.indexOf('=');
    if (eqIdx < 1) continue;
    const key = trimmed.slice(0, eqIdx).trim();
    const val = trimmed.slice(eqIdx + 1).trim();
    if (!process.env[key]) {
      process.env[key] = val;
    }
  }
}

loadEnv();

// ── Express app ────────────────────────────────────────────

const app = express();
app.use(express.json());

// 健康检查 (无需鉴权)
app.get('/health', (_req, res) => {
  res.json({ ok: true, uptime: process.uptime() });
});

// 路由
app.use(alertRouter);
app.use(alertsQueryRouter);
app.use(screenshotRouter);

// ── HTTP + WebSocket 服务器 ────────────────────────────────

const server = http.createServer(app);

const wss = new WebSocketServer({ server });
wss.on('connection', handleConnection);
initPing();

// ── 启动 ──────────────────────────────────────────────────

validateConfig();

// 确保截图目录存在
fs.mkdirSync(config.screenshotDir, { recursive: true });

// 启动截图清理定时器
startCleanupTimer();

// 启动报警记录 TTL 清理定时器（每 30 分钟）
setInterval(cleanupExpiredAlerts, 30 * 60 * 1000);
cleanupExpiredAlerts(); // 启动时立即执行一次

server.listen(config.port, () => {
  console.log(`[server] VisionGuard Server 已启动`);
  console.log(`[server] HTTP + WS 监听端口: ${config.port}`);
  console.log(`[server] HTTP 截图上传: ${config.enableHttpScreenshotUpload ? '开启' : '关闭（纯 WS 按需模型）'}`);
  console.log(`[server] 截图目录: ${config.screenshotDir}`);
  console.log(`[server] 截图 TTL: ${config.screenshotTtlHours} 小时`);
  console.log(`[server] 报警记录 TTL: ${config.alertTtlHours} 小时`);
});
