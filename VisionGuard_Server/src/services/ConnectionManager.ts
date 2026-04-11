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
  WsAuthMessage, WsHeartbeat, WsCommand, WsSetConfig,
  WindowsClient, AndroidClient, WsAlertPush,
  DeviceStatus, WsCommandRelay, WsSetConfigRelay, WsCommandAck,
  WsRequestScreenshot, WsRequestScreenshotRelay, WsScreenshotData,
} from '../models/types';

const windowsClients = new Map<string, WindowsClient>();
const androidClients = new Map<string, AndroidClient>();

// 追踪待处理的截图请求：alertId → androidDeviceId（用于 screenshot-data 回传路由）
const pendingScreenshotRequests = new Map<string, Set<string>>();

// 广播防抖：50ms 内多次触发合并为一次，防止 20 台心跳同步时产生广播风暴
let _broadcastTimer: NodeJS.Timeout | null = null;
function scheduleBroadcast(): void {
  if (_broadcastTimer) return;
  _broadcastTimer = setTimeout(() => {
    _broadcastTimer = null;
    broadcastDeviceList();
  }, 50);
}

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
      case 'alert':
        if (role === 'windows') broadcastAlert(msg as WsAlertPush);
        break;
      case 'command':
        if (role === 'android') handleCommand(ws, msg as WsCommand);
        break;
      case 'set-config':
        if (role === 'android') handleSetConfig(ws, msg as WsSetConfig);
        break;
      case 'command-ack':
        // Windows 端主动发回的 ack（含前置校验错误原因），转发给所有 Android
        if (role === 'windows') handleWindowsCommandAck(msg as WsCommandAck, deviceId!);
        break;
      case 'request-screenshot':
        if (role === 'android') handleRequestScreenshot(ws, msg as WsRequestScreenshot, deviceId!);
        break;
      case 'screenshot-data':
        if (role === 'windows') handleScreenshotData(msg as WsScreenshotData);
        break;
    }
  });

  ws.on('close', (code) => {
    clearTimeout(authTimer);
    const ts = new Date().toISOString();
    if (deviceId) {
      if (role === 'windows' && windowsClients.has(deviceId)) {
        windowsClients.delete(deviceId);
        console.log(`[ws][${ts}] Windows 断开: ${deviceId} code=${code} Windows在线=${windowsClients.size}`);
        scheduleBroadcast();
      } else if (role === 'android' && androidClients.has(deviceId)) {
        androidClients.delete(deviceId);
        console.log(`[ws][${ts}] Android 断开: ${deviceId} code=${code} Android在线=${androidClients.size}`);
        scheduleBroadcast();
      } else {
        console.log(`[ws][${ts}] 关闭事件（已由 error 清理）: ${deviceId} code=${code}`);
      }
    } else {
      console.log(`[ws][${ts}] 未认证连接关闭 code=${code}`);
    }
  });

  ws.on('error', (err) => {
    const ts = new Date().toISOString();
    console.error(`[ws][${ts}] 连接错误 deviceId=${deviceId ?? 'unauthenticated'} role=${role ?? '?'}: ${err.message}`);
    if (deviceId) {
      if (role === 'windows' && windowsClients.has(deviceId)) {
        windowsClients.delete(deviceId);
        console.log(`[ws][${ts}] Windows 因错误移除: ${deviceId}`);
        scheduleBroadcast();
      } else if (role === 'android' && androidClients.has(deviceId)) {
        androidClients.delete(deviceId);
        console.log(`[ws][${ts}] Android 因错误移除: ${deviceId}`);
        scheduleBroadcast();
      }
    }
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
      clientType: msg.role,
      isMonitoring: false,
      isAlarming: false,
      isReady: false,
      lastSeen: new Date(),
      cooldown: 5,
      confidence: 0.45,
      targets: '',
    });
    console.log(`[ws][${new Date().toISOString()}] Windows 上线: ${msg.deviceName} (${msg.deviceId}) | Windows在线: ${windowsClients.size} Android在线: ${androidClients.size}`);
  } else if (msg.role === 'android') {
    androidClients.set(msg.deviceId, { ws, deviceId: msg.deviceId, lastSeen: new Date() });
    // 监听 OkHttp ping 帧（每 25s 一次），更新存活时间
    ws.on('ping', () => {
      const client = androidClients.get(msg.deviceId);
      if (client) client.lastSeen = new Date();
    });
    console.log(`[ws][${new Date().toISOString()}] Android 上线: ${msg.deviceId} | Android在线: ${androidClients.size}`);
  } else {
    sendJson(ws, { type: 'auth-result', success: false, reason: 'invalid role' });
    ws.close();
    return;
  }

  sendJson(ws, { type: 'auth-result', success: true });
  onSuccess(msg.role, msg.deviceId);

  // 认证成功后，立即向本客户端发送完整设备列表（解决客户端就绪时序问题）
  sendJson(ws, {
    type: 'device-list',
    devices: buildDeviceList(),
  });

  scheduleBroadcast();
}

function handleHeartbeat(msg: WsHeartbeat): void {
  const client = windowsClients.get(msg.deviceId);
  if (!client) return;

  const changed =
    client.isMonitoring !== msg.isMonitoring ||
    client.isAlarming !== msg.isAlarming ||
    client.isReady !== msg.isReady ||
    client.cooldown !== (msg.cooldown ?? client.cooldown) ||
    client.confidence !== (msg.confidence ?? client.confidence) ||
    client.targets !== (msg.targets ?? client.targets);

  client.isMonitoring = msg.isMonitoring;
  client.isAlarming = msg.isAlarming;
  client.isReady = msg.isReady ?? false;
  if (msg.cooldown !== undefined) client.cooldown = msg.cooldown;
  if (msg.confidence !== undefined) client.confidence = msg.confidence;
  if (msg.targets !== undefined) client.targets = msg.targets;
  client.lastSeen = new Date();

  if (changed) scheduleBroadcast();
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

  // 转发给目标 Windows（Windows 端会自行发回带 reason 的 command-ack）
  const relay: WsCommandRelay = { type: 'command', command: msg.command };
  target.ws.send(JSON.stringify(relay));

  // 注意：这里只发"已转发"的临时 ack；
  // Windows 端执行后会再发一个带 success/reason 的 command-ack，
  // 由 handleWindowsCommandAck 转发给 Android。
  ack.success = true;
  ack.reason = 'relayed';
  sendJson(senderWs, ack);
}

function handleSetConfig(senderWs: WebSocket, msg: WsSetConfig): void {
  const target = windowsClients.get(msg.targetDeviceId);

  const ack: WsCommandAck = {
    type: 'command-ack',
    targetDeviceId: msg.targetDeviceId,
    command: `set-config:${msg.key}`,
    success: false,
    reason: '',
  };

  if (!target || target.ws.readyState !== WebSocket.OPEN) {
    ack.reason = 'device offline';
    sendJson(senderWs, ack);
    return;
  }

  // 转发 set-config 给目标 Windows
  const relay: WsSetConfigRelay = { type: 'set-config', key: msg.key, value: msg.value };
  target.ws.send(JSON.stringify(relay));

  // 同样只发"已转发"，Windows 端执行后回 command-ack
  ack.success = true;
  ack.reason = 'relayed';
  sendJson(senderWs, ack);
}

/** Windows 端主动回传的 command-ack（含具体执行结果），广播给所有 Android */
function handleWindowsCommandAck(ack: WsCommandAck, windowsDeviceId: string): void {
  // 补充 targetDeviceId（Windows 自己就是 target，让 Android 知道是哪台设备的回执）
  const enriched = { ...ack, targetDeviceId: windowsDeviceId };
  const data = JSON.stringify(enriched);
  for (const client of androidClients.values()) {
    if (client.ws.readyState === WebSocket.OPEN) {
      client.ws.send(data);
    }
  }
}

/** Android 请求截图：转发给目标 Windows，登记待回传路由 */
function handleRequestScreenshot(senderWs: WebSocket, msg: WsRequestScreenshot, senderDeviceId: string): void {
  const target = windowsClients.get(msg.targetDeviceId);
  if (!target || target.ws.readyState !== WebSocket.OPEN) {
    // 目标不在线，直接告知 Android
    sendJson(senderWs, {
      type: 'screenshot-data',
      alertId: msg.alertId,
      imageBase64: '',
      width: 0,
      height: 0,
    });
    return;
  }
  // 登记路由：alertId → 发起请求的 Android deviceId（用于 screenshot-data 回传）
  const existing = pendingScreenshotRequests.get(msg.alertId) ?? new Set<string>();
  existing.add(senderDeviceId);
  pendingScreenshotRequests.set(msg.alertId, existing);
  const relay: WsRequestScreenshotRelay = { type: 'request-screenshot', alertId: msg.alertId };
  target.ws.send(JSON.stringify(relay));
}

/** Windows 回传截图数据：路由给发起请求的 Android */
function handleScreenshotData(msg: WsScreenshotData): void {
  const targetDeviceIds = pendingScreenshotRequests.get(msg.alertId);
  if (!targetDeviceIds) return;
  pendingScreenshotRequests.delete(msg.alertId);
  const data = JSON.stringify(msg);
  for (const targetDeviceId of targetDeviceIds) {
    const targetClient = androidClients.get(targetDeviceId);
    if (targetClient?.ws.readyState === WebSocket.OPEN) {
      targetClient.ws.send(data);
    }
  }
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
      isReady: c.isReady,
      lastSeen: c.lastSeen.toISOString(),
      cooldown: c.cooldown,
      confidence: c.confidence,
      targets: c.targets,
      clientType: c.clientType ?? 'windows',
    });
  }
  return devices;
}

function sendJson(ws: WebSocket, obj: object): void {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

// ── 定时维护：每 30s ─────────────────────────────────────────────────────────
// BUG 2/3 FIX: 定时推送 device-list，即使无状态变化也刷新 Android _lastMessageAt
// BUG 4 FIX:   幽灵清理阈值从 90s 降至 75s，周期从 60s 降至 30s（最坏情况 105s）
setInterval(() => {
  const now = Date.now();
  const ts = new Date().toISOString();
  const ghostDeadline = now - 75_000;

  for (const [id, client] of androidClients) {
    if (client.lastSeen.getTime() < ghostDeadline) {
      const silentSec = Math.round((now - client.lastSeen.getTime()) / 1000);
      console.log(`[ws][${ts}] Android 幽灵清理: ${id} (静默 ${silentSec}s)`);
      client.ws.terminate();
      androidClients.delete(id);
    }
  }

  if (androidClients.size > 0) {
    broadcastDeviceList();
    console.log(`[ws][${ts}] 定时推送 → ${androidClients.size} Android / ${windowsClients.size} Windows`);
  }
}, 30_000);
