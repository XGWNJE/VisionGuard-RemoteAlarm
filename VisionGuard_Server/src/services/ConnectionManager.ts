// ┌─────────────────────────────────────────────────────────┐
// │ ConnectionManager.ts                                    │
// │ 角色：WebSocket 连接管理 (按 deviceId/role 跟踪)         │
// │ 职责：认证、心跳、设备列表广播、报警广播、命令中继        │
// │ 对外 API：handleConnection(), broadcastAlert()           │
// └─────────────────────────────────────────────────────────┘

import WebSocket from 'ws';
import { config } from '../config';
import { validateApiKey } from '../middleware/auth';
import type {
  WsAuthMessage, WsHeartbeat, WsCommand,
  WindowsClient, AndroidClient, WsAlertPush,
  DeviceStatus, WsCommandRelay, WsCommandAck,
} from '../models/types';

const windowsClients = new Map<string, WindowsClient>();
const androidClients = new Map<string, AndroidClient>();

// ── 公开 API ──────────────────────────────────────────────

/**
 * 处理新 WS 连接：设置认证超时 → 监听消息 → 路由到对应处理器
 */
export function handleConnection(ws: WebSocket): void {
  let authenticated = false;
  let role: 'windows' | 'android' | null = null;
  let deviceId: string | null = null;

  // 5 秒内未认证则断开
  const authTimer = setTimeout(() => {
    if (!authenticated) {
      sendJson(ws, { type: 'auth-result', success: false, reason: 'auth timeout' });
      ws.close();
    }
  }, config.wsAuthTimeoutMs);

  ws.on('message', (raw) => {
    let msg: any;
    try { msg = JSON.parse(raw.toString()); } catch { return; }

    if (!authenticated) {
      if (msg.type === 'auth') {
        handleAuth(ws, msg as WsAuthMessage, authTimer, (r, d) => {
          authenticated = true;
          role = r;
          deviceId = d;
        });
      }
      return;
    }

    // 已认证后的消息路由
    switch (msg.type) {
      case 'heartbeat':
        if (role === 'windows') handleHeartbeat(msg as WsHeartbeat);
        break;
      case 'command':
        if (role === 'android') handleCommand(ws, msg as WsCommand);
        break;
    }
  });

  ws.on('close', () => {
    clearTimeout(authTimer);
    if (deviceId) {
      if (role === 'windows') {
        windowsClients.delete(deviceId);
        console.log(`[ws] Windows 设备断开: ${deviceId}`);
      } else if (role === 'android') {
        androidClients.delete(deviceId);
        console.log(`[ws] Android 设备断开: ${deviceId}`);
      }
      broadcastDeviceList();
    }
  });

  ws.on('error', (err) => {
    console.error(`[ws] 连接错误:`, err.message);
  });
}

/**
 * 向所有 Android 客户端广播报警
 */
export function broadcastAlert(alert: WsAlertPush): void {
  const data = JSON.stringify(alert);
  for (const client of androidClients.values()) {
    if (client.ws.readyState === WebSocket.OPEN) {
      client.ws.send(data);
    }
  }
}

/**
 * 获取当前设备列表快照 (供 HTTP 接口使用)
 */
export function getDeviceList(): DeviceStatus[] {
  return buildDeviceList();
}

// ── 内部处理 ──────────────────────────────────────────────

function handleAuth(
  ws: WebSocket,
  msg: WsAuthMessage,
  authTimer: NodeJS.Timeout,
  onSuccess: (role: 'windows' | 'android', deviceId: string) => void,
): void {
  clearTimeout(authTimer);

  if (!validateApiKey(msg.apiKey)) {
    sendJson(ws, { type: 'auth-result', success: false, reason: 'invalid api key' });
    ws.close();
    return;
  }

  if (msg.role === 'windows') {
    windowsClients.set(msg.deviceId, {
      ws,
      deviceId: msg.deviceId,
      deviceName: msg.deviceName,
      isMonitoring: false,
      isAlarming: false,
      lastSeen: new Date(),
    });
    console.log(`[ws] Windows 设备上线: ${msg.deviceName} (${msg.deviceId})`);
  } else if (msg.role === 'android') {
    androidClients.set(msg.deviceId, { ws, deviceId: msg.deviceId });
    console.log(`[ws] Android 设备上线: ${msg.deviceId}`);
  } else {
    sendJson(ws, { type: 'auth-result', success: false, reason: 'invalid role' });
    ws.close();
    return;
  }

  sendJson(ws, { type: 'auth-result', success: true });
  onSuccess(msg.role, msg.deviceId);
  broadcastDeviceList();
}

function handleHeartbeat(msg: WsHeartbeat): void {
  const client = windowsClients.get(msg.deviceId);
  if (!client) return;

  const changed =
    client.isMonitoring !== msg.isMonitoring ||
    client.isAlarming !== msg.isAlarming;

  client.isMonitoring = msg.isMonitoring;
  client.isAlarming = msg.isAlarming;
  client.lastSeen = new Date();

  if (changed) broadcastDeviceList();
}

function handleCommand(senderWs: WebSocket, msg: WsCommand): void {
  const target = windowsClients.get(msg.targetDeviceId);

  const ack: WsCommandAck = {
    type: 'command-ack',
    targetDeviceId: msg.targetDeviceId,
    command: msg.command,
    success: false,
    reason: '',
  };

  if (!target || target.ws.readyState !== WebSocket.OPEN) {
    ack.reason = 'device offline';
    sendJson(senderWs, ack);
    return;
  }

  // 转发给目标 Windows (不含 targetDeviceId)
  const relay: WsCommandRelay = { type: 'command', command: msg.command };
  target.ws.send(JSON.stringify(relay));

  ack.success = true;
  sendJson(senderWs, ack);
}

function broadcastDeviceList(): void {
  const list = buildDeviceList();
  const data = JSON.stringify({ type: 'device-list', devices: list });
  for (const client of androidClients.values()) {
    if (client.ws.readyState === WebSocket.OPEN) {
      client.ws.send(data);
    }
  }
}

function buildDeviceList(): DeviceStatus[] {
  const now = Date.now();
  const devices: DeviceStatus[] = [];
  for (const c of windowsClients.values()) {
    devices.push({
      deviceId: c.deviceId,
      deviceName: c.deviceName,
      online: (now - c.lastSeen.getTime()) < config.deviceOfflineMs,
      isMonitoring: c.isMonitoring,
      isAlarming: c.isAlarming,
      lastSeen: c.lastSeen.toISOString(),
    });
  }
  return devices;
}

function sendJson(ws: WebSocket, obj: object): void {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}
