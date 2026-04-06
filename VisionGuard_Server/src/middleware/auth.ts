// ┌─────────────────────────────────────────────────────────┐
// │ auth.ts                                                 │
// │ 角色：API Key 校验 (HTTP 中间件 + WS 认证函数)           │
// │ 对外 API：httpAuth (Express 中间件), validateApiKey()    │
// └─────────────────────────────────────────────────────────┘

import { Request, Response, NextFunction } from 'express';
import { config } from '../config';

/**
 * Express 中间件：校验 X-API-Key 请求头
 */
export function httpAuth(req: Request, res: Response, next: NextFunction): void {
  const key = req.headers['x-api-key'] as string | undefined;
  if (!key || key !== config.apiKey) {
    res.status(401).json({ ok: false, error: 'unauthorized' });
    return;
  }
  next();
}

/**
 * 校验 API Key 字符串（用于 WS 认证消息）
 */
export function validateApiKey(key: string): boolean {
  return !!config.apiKey && key === config.apiKey;
}
