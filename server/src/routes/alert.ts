// ┌─────────────────────────────────────────────────────────┐
// │ alert.ts                                                │
// │ 角色：POST /api/alert 路由 — 接收报警上传并广播          │
// │ 流程：multer 接收 → 解析 meta → 可选存截图 → 存记录 → 广播│
// │ 模式：ENABLE_HTTP_SCREENSHOT_UPLOAD=true 时接收截图；    │
// │       false 时仅接收 meta，截图由检测端本地缓存+按需推送 │
// └─────────────────────────────────────────────────────────┘

import { Router, Request, Response } from 'express';
import multer from 'multer';
import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { config } from '../config';
import { httpAuth } from '../middleware/auth';
import { addAlert } from '../services/AlertStore';
import { broadcastAlert } from '../services/ConnectionManager';
import type { AlertMeta, WsAlertPush } from '../models/types';

const router = Router();

// multer 配置：截图存到 data/screenshots/<alertId>.png
const storage = multer.diskStorage({
  destination: (_req, _file, cb) => {
    fs.mkdirSync(config.screenshotDir, { recursive: true });
    cb(null, config.screenshotDir);
  },
  filename: (_req, _file, cb) => {
    const tempName = `tmp_${Date.now()}_${Math.random().toString(36).slice(2)}${path.extname(_file.originalname) || '.png'}`;
    cb(null, tempName);
  },
});

const upload = multer({
  storage,
  limits: { fileSize: config.maxUploadBytes },
  fileFilter: (_req, file, cb) => {
    if (file.mimetype.startsWith('image/')) {
      cb(null, true);
    } else {
      cb(new Error('只接受图片文件'));
    }
  },
});

/**
 * POST /api/alert
 * Body: multipart/form-data
 *   - "meta" (application/json): AlertMeta
 *   - "screenshot" (image/jpeg|png): 可选，二进制图片
 *
 * 当 ENABLE_HTTP_SCREENSHOT_UPLOAD=false 时，screenshot 字段可省略，
 * 服务器只存储报警 meta 并广播轻量 alert（无 screenshotUrl）。
 */
router.post(
  '/api/alert',
  httpAuth,
  upload.single('screenshot'),
  (req: Request, res: Response) => {
    try {
      const metaRaw = req.body?.meta;
      if (!metaRaw) {
        res.status(400).json({ ok: false, error: 'missing meta field' });
        return;
      }

      let meta: AlertMeta;
      try {
        meta = typeof metaRaw === 'string' ? JSON.parse(metaRaw) : metaRaw;
      } catch {
        res.status(400).json({ ok: false, error: 'invalid meta JSON' });
        return;
      }

      const alertId = crypto.randomUUID();

      // 截图处理：仅在开启上传开关且收到文件时保存
      let screenshotPath: string | undefined;
      if (config.enableHttpScreenshotUpload && req.file) {
        const finalName = `${alertId}.png`;
        const finalPath = path.join(config.screenshotDir, finalName);
        fs.renameSync(req.file.path, finalPath);
        screenshotPath = finalPath;
      } else if (req.file) {
        // 开关关闭但收到了文件，清理临时文件
        try { fs.unlinkSync(req.file.path); } catch { /* ignore */ }
      }

      addAlert({
        alertId,
        deviceId: meta.deviceId,
        deviceName: meta.deviceName,
        timestamp: meta.timestamp,
        detections: meta.detections,
        screenshotPath,
        createdAt: Date.now(),
      });

      // 广播给所有 Android 客户端
      const push: WsAlertPush = {
        type: 'alert',
        alertId,
        deviceId: meta.deviceId,
        deviceName: meta.deviceName,
        timestamp: meta.timestamp,
        detections: meta.detections,
        screenshotUrl: screenshotPath ? `/screenshots/${alertId}.png` : '',
        ...(meta as any).timings ? { timings: (meta as any).timings } : {},
      };
      broadcastAlert(push);

      console.log(`[alert] 报警已接收: ${meta.deviceName} → ${meta.detections.length} 个目标 (${alertId}) screenshot=${screenshotPath ? 'saved' : 'detector-local'}`);
      res.json({ ok: true, alertId });
    } catch (err: any) {
      console.error('[alert] 处理失败:', err.message);
      res.status(500).json({ ok: false, error: 'internal error' });
    }
  },
);

export default router;
