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

  /** 设备离线判定时间 (毫秒)，默认 90 秒 (心跳间隔 30s × 3) */
  deviceOfflineMs: 90000,

  /** 每设备最大报警记录数 (循环缓冲) */
  maxAlertsPerDevice: 200,
} as const;

// 启动时检查 API_KEY 是否配置
export function validateConfig(): void {
  if (!config.apiKey) {
    console.warn('[config] ⚠ API_KEY 未设置，所有请求将被拒绝。请在 .env 中配置 API_KEY');
  }
}
