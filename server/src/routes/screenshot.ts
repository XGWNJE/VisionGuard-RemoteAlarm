// ┌─────────────────────────────────────────────────────────┐
// │ screenshot.ts                                           │
// │ 角色：GET /screenshots/:id.png — 供 Android 加载截图     │
// │ 鉴权：query param ?key=<apiKey>                         │
// │ 依赖：config                                            │
// └─────────────────────────────────────────────────────────┘

import { Router, Request, Response } from 'express';
import path from 'path';
import fs from 'fs';
import { config } from '../config';

const router = Router();

/**
 * GET /screenshots/:id.png?key=<apiKey>
 * 返回截图 PNG 文件
 */
router.get('/screenshots/:filename', (req: Request, res: Response) => {
  // 鉴权 (query param)
  const key = String(req.query.key ?? '');
  if (!key || key !== config.apiKey) {
    res.status(401).json({ ok: false, error: 'unauthorized' });
    return;
  }

  const rawName = req.params.filename;
  const filename = Array.isArray(rawName) ? rawName[0] : rawName;

  // 安全检查：防止目录遍历
  if (filename.includes('..') || filename.includes('/') || filename.includes('\\')) {
    res.status(400).json({ ok: false, error: 'invalid filename' });
    return;
  }

  const filePath = path.join(config.screenshotDir, filename);

  if (!fs.existsSync(filePath)) {
    res.status(404).json({ ok: false, error: 'screenshot not found' });
    return;
  }

  res.sendFile(filePath);
});

export default router;
