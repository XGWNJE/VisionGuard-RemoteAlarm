// ┌─────────────────────────────────────────────────────────┐
// │ ScreenshotCleanup.ts                                    │
// │ 角色：定时扫描截图目录，删除超过 TTL 的过期文件           │
// │ 对外 API：startCleanupTimer(), stopCleanupTimer()        │
// └─────────────────────────────────────────────────────────┘

import fs from 'fs';
import path from 'path';
import { config } from '../config';

let timer: NodeJS.Timeout | null = null;

/**
 * 启动定时清理 (每 cleanupIntervalMs 执行一次)
 */
export function startCleanupTimer(): void {
  // 启动时立即执行一次
  cleanup();
  timer = setInterval(cleanup, config.cleanupIntervalMs);
}

export function stopCleanupTimer(): void {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
}

function cleanup(): void {
  const dir = config.screenshotDir;
  if (!fs.existsSync(dir)) return;

  const ttlMs = config.screenshotTtlHours * 3600 * 1000;
  const now = Date.now();
  let deleted = 0;

  try {
    const files = fs.readdirSync(dir);
    for (const file of files) {
      if (!file.endsWith('.png')) continue;
      const filePath = path.join(dir, file);
      try {
        const stat = fs.statSync(filePath);
        if (now - stat.mtimeMs > ttlMs) {
          fs.unlinkSync(filePath);
          deleted++;
        }
      } catch { /* 文件可能已被删除，忽略 */ }
    }
  } catch { /* 目录不可读，忽略 */ }

  if (deleted > 0) {
    console.log(`[cleanup] 已清理 ${deleted} 张过期截图`);
  }
}
