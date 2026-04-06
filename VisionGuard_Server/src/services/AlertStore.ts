// ┌─────────────────────────────────────────────────────────┐
// │ AlertStore.ts                                           │
// │ 角色：内存循环缓冲，按设备存储最近 N 条报警记录           │
// │ 对外 API：addAlert(), getAlerts(), getAlert()            │
// └─────────────────────────────────────────────────────────┘

import { config } from '../config';
import type { AlertRecord } from '../models/types';

/** deviceId → AlertRecord[] (最多 maxAlertsPerDevice 条) */
const store = new Map<string, AlertRecord[]>();

/**
 * 添加报警记录，超出上限时淘汰最旧的
 */
export function addAlert(record: AlertRecord): void {
  let list = store.get(record.deviceId);
  if (!list) {
    list = [];
    store.set(record.deviceId, list);
  }
  list.push(record);
  if (list.length > config.maxAlertsPerDevice) {
    list.shift();
  }
}

/**
 * 获取某设备的所有报警记录
 */
export function getAlerts(deviceId: string): AlertRecord[] {
  return store.get(deviceId) || [];
}

/**
 * 按 alertId 查找单条记录
 */
export function getAlert(alertId: string): AlertRecord | undefined {
  for (const list of store.values()) {
    const found = list.find(r => r.alertId === alertId);
    if (found) return found;
  }
  return undefined;
}
