// ┌─────────────────────────────────────────────────────────┐
// │ alerts.ts                                               │
// │ 角色：GET /api/alerts — 查询报警历史列表                 │
// │ 鉴权：X-API-Key header                                   │
// └─────────────────────────────────────────────────────────┘

import { Router, Request, Response } from 'express';
import { httpAuth } from '../middleware/auth';
import { getAlerts } from '../services/AlertStore';

const router = Router();

/**
 * GET /api/alerts?deviceId=xxx&since=timestamp&limit=50
 * 返回报警记录列表（按时间倒序）
 */
router.get('/api/alerts', httpAuth, (req: Request, res: Response) => {
  try {
    const deviceId = typeof req.query.deviceId === 'string' ? req.query.deviceId : undefined;
    const since = typeof req.query.since === 'string' ? parseInt(req.query.since, 10) : undefined;
    const limit = typeof req.query.limit === 'string' ? parseInt(req.query.limit, 10) : 50;

    const alerts = getAlerts(deviceId, since, limit);

    // 过滤掉内部字段（screenshotPath 不应暴露给客户端）
    const sanitized = alerts.map(a => ({
      alertId: a.alertId,
      deviceId: a.deviceId,
      deviceName: a.deviceName,
      timestamp: a.timestamp,
      detections: a.detections,
      createdAt: a.createdAt,
    }));

    res.json({ ok: true, alerts: sanitized });
  } catch (err: any) {
    console.error('[alerts] 查询失败:', err.message);
    res.status(500).json({ ok: false, error: 'internal error' });
  }
});

export default router;
