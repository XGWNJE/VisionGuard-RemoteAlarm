// ┌─────────────────────────────────────────────────────────┐
// │ alert.ts                                                │
// │ 角色：POST /api/alert 路由 — 接收报警上传并广播          │
// │ 流程：multer 接收 → 解析 meta → 存截图 → 存记录 → 广播  │
// │ 依赖：multer, AlertStore, ConnectionManager              │
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
    // 确保目录存在
    fs.mkdirSync(config.screenshotDir, { recursive: true });
    cb(null, config.screenshotDir);
  },
  filename: (_req, file, cb) => {
    // 先用临时文件名，后面会重命名
    const tempName = `tmp_${Date.now()}_${Math.random().toString(36).slice(2)}${path.extname(file.originalname) || '.png'}`;
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
 *   - "screenshot" (image/png): 二进制 PNG
 */
router.post(
  '/api/alert',
  httpAuth,
  upload.single('screenshot'),
  (req: Request, res: Response) => {
    try {
      // 解析 meta 字段
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

      // 生成 alertId
      const alertId = crypto.randomUUID();

      // 重命名截图文件
      let screenshotPath = '';
      if (req.file) {
        const finalName = `${alertId}.png`;
        const finalPath = path.join(config.screenshotDir, finalName);
        fs.renameSync(req.file.path, finalPath);
        screenshotPath = finalPath;
      }

      // 存入内存
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
        screenshotUrl: `/screenshots/${alertId}.png`,
      };
      broadcastAlert(push);

      console.log(`[alert] 报警已接收: ${meta.deviceName} → ${meta.detections.length} 个目标 (${alertId})`);
      res.json({ ok: true, alertId });
    } catch (err: any) {
      console.error('[alert] 处理失败:', err.message);
      res.status(500).json({ ok: false, error: 'internal error' });
    }
  },
);

export default router;
