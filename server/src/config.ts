// ┌─────────────────────────────────────────────────────────┐
// │ config.ts                                               │
// │ 角色：集中读取环境变量，提供类型安全的配置对象             │
// │ 对外 API：config (单例对象)                              │
// └─────────────────────────────────────────────────────────┘

import path from 'path';

export const config = {
  /** HTTP/WS 监听端口 */
  port: parseInt(process.env.PORT || '3000', 10),

  /** 共享 API Key (所有端使用同一个) */
  apiKey: process.env.API_KEY || '',

  /** 截图存储目录 */
  screenshotDir: path.resolve(__dirname, '..', 'data', 'screenshots'),

  /** 截图过期时间 (小时)，默认 72 小时 */
  screenshotTtlHours: parseInt(process.env.SCREENSHOT_TTL_HOURS || '72', 10),

  /** 截图清理间隔 (毫秒)，默认 1 小时 */
  cleanupIntervalMs: parseInt(process.env.CLEANUP_INTERVAL_MS || '3600000', 10),

  /** 上传大小限制 (字节)，默认 2MB */
  maxUploadBytes: parseInt(process.env.MAX_UPLOAD_BYTES || '2097152', 10),

  /** WS 认证超时 (毫秒) */
  wsAuthTimeoutMs: 5000,

  /** 设备离线判定 / 幽灵清理阈值 (毫秒)。超过此时间无消息则终止连接并标记离线。
   *  Windows 心跳 15s, Android 心跳 20s + ping 20s, 取 75s 为安全阈值。 */
  deviceOfflineMs: 75_000,

  /** 每设备最大报警记录数 (循环缓冲) */
  maxAlertsPerDevice: 200,

  /** 客户端最低版本要求 (语义化版本)。低于此版本的连接将在认证时被拒绝。 */
  minClientVersion: '3.2.1',

  /** 是否接收检测端 HTTP POST 截图上传。false = 纯 WS 按需模型，截图仅存在检测端本地 */
  enableHttpScreenshotUpload: process.env.ENABLE_HTTP_SCREENSHOT_UPLOAD === 'true',

  /** 报警记录 TTL (小时)，默认 168 小时 = 7 天。与检测端本地截图缓存 TTL 对齐 */
  alertTtlHours: parseInt(process.env.ALERT_TTL_HOURS || '168', 10),
} as const;

// 启动时检查 API_KEY 是否配置
export function validateConfig(): void {
  if (!config.apiKey) {
    console.warn('[config] ⚠ API_KEY 未设置，所有请求将被拒绝。请在 .env 中配置 API_KEY');
  }
}
