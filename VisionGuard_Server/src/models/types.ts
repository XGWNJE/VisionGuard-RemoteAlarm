// ┌─────────────────────────────────────────────────────────┐
// │ types.ts                                                │
// │ 角色：所有 TypeScript 接口/类型定义                       │
// │ 覆盖：HTTP 请求/响应、WebSocket 消息、内部数据结构        │
// └─────────────────────────────────────────────────────────┘

// ── HTTP ──────────────────────────────────────────────────

/** POST /api/alert 的 meta 字段 JSON 结构 */
export interface AlertMeta {
  deviceId: string;
  deviceName: string;
  timestamp: string;          // ISO 8601
  detections: Detection[];
}

export interface Detection {
  label: string;
  confidence: number;
  bbox: { x: number; y: number; w: number; h: number };
}

/** 存储在 AlertStore 中的完整报警记录 */
export interface AlertRecord {
  alertId: string;
  deviceId: string;
  deviceName: string;
  timestamp: string;
  detections: Detection[];
  screenshotPath: string;     // 磁盘路径
  createdAt: number;          // Date.now()
}

// ── WebSocket 消息 ─────────────────────────────────────────

/** 所有 WS 消息共有的 type 字段 */
export type WsMessageType =
  | 'auth'
  | 'auth-result'
  | 'heartbeat'
  | 'alert'
  | 'device-list'
  | 'command'
  | 'command-ack';

/** 客户端 → 服务器：认证 */
export interface WsAuthMessage {
  type: 'auth';
  apiKey: string;
  role: 'windows' | 'android';
  deviceId: string;
  deviceName: string;
}

/** 服务器 → 客户端：认证结果 */
export interface WsAuthResult {
  type: 'auth-result';
  success: boolean;
  reason?: string;
}

/** Windows → 服务器：心跳 (每 30 秒) */
export interface WsHeartbeat {
  type: 'heartbeat';
  deviceId: string;
  isMonitoring: boolean;
  isAlarming: boolean;
}

/** 服务器 → Android：报警推送 */
export interface WsAlertPush {
  type: 'alert';
  alertId: string;
  deviceId: string;
  deviceName: string;
  timestamp: string;
  detections: Detection[];
  screenshotUrl: string;
}

/** 服务器 → Android：设备列表推送 */
export interface WsDeviceList {
  type: 'device-list';
  devices: DeviceStatus[];
}

export interface DeviceStatus {
  deviceId: string;
  deviceName: string;
  online: boolean;
  isMonitoring: boolean;
  isAlarming: boolean;
  lastSeen: string;           // ISO 8601
}

/** Android → 服务器：反向控制命令 */
export interface WsCommand {
  type: 'command';
  targetDeviceId: string;
  command: 'pause' | 'resume' | 'stop-alarm';
}

/** 服务器 → Windows：转发命令 (不含 targetDeviceId) */
export interface WsCommandRelay {
  type: 'command';
  command: 'pause' | 'resume' | 'stop-alarm';
}

/** 服务器 → Android：命令确认 */
export interface WsCommandAck {
  type: 'command-ack';
  targetDeviceId: string;
  command: string;
  success: boolean;
  reason: string;
}

// ── 内部连接管理 ───────────────────────────────────────────

import type WebSocket from 'ws';

export interface WindowsClient {
  ws: WebSocket;
  deviceId: string;
  deviceName: string;
  isMonitoring: boolean;
  isAlarming: boolean;
  lastSeen: Date;
}

export interface AndroidClient {
  ws: WebSocket;
  deviceId: string;
}
