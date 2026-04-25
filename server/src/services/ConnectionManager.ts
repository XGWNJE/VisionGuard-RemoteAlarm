// ┌─────────────────────────────────────────────────────────┐
// │ ConnectionManager.ts                                    │
// │ 角色：WebSocket 连接管理 (按 deviceId/role 跟踪)         │
// │ 职责：认证、心跳、设备列表广播、报警广播、命令中继        │
// │ 对外 API：handleConnection(), broadcastAlert()           │
// └─────────────────────────────────────────────────────────┘

import WebSocket from 'ws';
import { config } from '../config';
import { validateApiKey } from '../middleware/auth';
import { addAlert } from '../services/AlertStore';
import type {
  WsAuthMessage, WsHeartbeat, WsHeartbeatAndroid, WsCommand, WsSetConfig,
  WindowsClient, AndroidClient, WsAlertPush,
  DeviceStatus, WsCommandRelay, WsSetConfigRelay, WsCommandAck,
  WsRequestScreenshot, WsRequestScreenshotRelay, WsScreenshotData,
  WsDisconnectReason, WsSessionInfo,
} from '../models/types';

const windowsClients = new Map<string, WindowsClient>();
const androidClients = new Map<string, AndroidClient>();

// ── Session 追踪：记录每个 Android 设备上次连接的信息（用于重连诊断） ──
interface AndroidSession {
  connectedAt: number;
  lastSessionEndReason: string;
  lastSessionDurationMs: number;
}
const androidSessions = new Map<string, AndroidSession>();

function meetsMinVersion(clientVersion: string | undefined, minVersion: string): boolean {
  if (!clientVersion) return false;
  const parse = (v: string) => v.split('.').map(Number);
  const client = parse(clientVersion);
  const min = parse(minVersion);
  for (let i = 0; i < min.length; i++) {
    const c = client[i] ?? 0;
    if (c > min[i]) return true;
    if (c < min[i]) return false;
  }
  return true;
}

// 追踪待处理的截图请求：alertId → androidDeviceId（用于 screenshot-data 回传路由）
const pendingScreenshotRequests = new Map<string, Set<string>>();

// 追踪截图请求创建时间（用于超时诊断）
const pendingScreenshotTimestamps = new Map<string, number>();

const SCREENSHOT_REQUEST_TIMEOUT_MS = 60_000;

const SessionEndReasonNames: Record<string, string> = {
  'user-close': '用户主动关闭',
  'network-lost': '网络中断（被系统杀后台/锁屏休眠）',
  'server-kick': '服务器主动断开',
  'app-killed': '应用被强制停止',
  'unknown': '未知原因',
};

// 广播防抖：50ms 内多次触发合并为一次，防止 20 台心跳同步时产生广播风暴
let _broadcastTimer: NodeJS.Timeout | null = null;
function scheduleBroadcast(): void {
  if (_broadcastTimer) return;
  _broadcastTimer = setTimeout(() => {
    _broadcastTimer = null;
    broadcastDeviceList();
  }, 50);
}

// ── 服务端主动 Ping：检测半开 TCP 连接 ────────────────────────
// ws 库不自动 ping，需要手动实现。每 30s 对所有已认证连接发送 ping 帧，
// 若上次 ping 未收到 pong 则判定连接已死。
const PING_INTERVAL_MS = 30_000;
const aliveClients = new WeakSet<WebSocket>();

function markAlive(ws: WebSocket): void {
  aliveClients.add(ws);
}

/**
 * 初始化服务端 Ping 定时器（在 wss 创建后调用一次）
 */
export function initPing(): void {
  setInterval(() => {
    for (const [, client] of windowsClients) {
      if (!aliveClients.has(client.ws)) {
        const roleLabel = client.clientType === 'android-detector' ? 'Android检测端' : 'Windows';
        console.log(`[ws][${new Date().toISOString()}] Ping 超时终止: ${roleLabel} ${client.deviceName} (${client.deviceId})`);
        client.ws.terminate();
        continue;
      }
      aliveClients.delete(client.ws);
      client.ws.ping();
    }
    for (const [, client] of androidClients) {
      if (!aliveClients.has(client.ws)) {
        console.log(`[ws][${new Date().toISOString()}] Ping 超时终止: Android ${client.deviceId}`);
        client.ws.terminate();
        continue;
      }
      aliveClients.delete(client.ws);
      client.ws.ping();
    }
  }, PING_INTERVAL_MS);
}

// ── WebSocket 关闭码翻译表 ─────────────────────────────────
const CloseCodeNames: Record<number, string> = {
  1000: '正常关闭',
  1001: '服务器关闭 (Going Away)',
  1002: '协议错误',
  1003: '不支持的数据类型',
  1005: '无状态码 (Never closing)',
  1006: '异常断开 (网络中断/服务器崩溃)',
  1007: '消息格式错误',
  1008: '消息内容违反策略',
  1009: '消息过大',
  1010: '必要扩展未协商成功',
  1011: '服务器内部错误',
  1015: 'TLS 握手失败',
};

function getCloseCodeName(code: number): string {
  return CloseCodeNames[code] ?? `未知错误 (code=${code})`;
}

/** 根据 WebSocket Close Code 推断 Android Session 结束原因 */
function closeCodeToSessionEndReason(code: number, deviceId: string): string {
  // 1000: 正常关闭（客户端主动调用 close）
  if (code === 1000) return 'user-close';
  // 1001: 服务器关闭（服务器主动断开）
  if (code === 1001) return 'server-kick';
  // 1006: 异常断开（网络中断、进程被杀等）
  if (code === 1006) {
    // 进一步检查是否有 session 历史：若上次持续时间很短(>5min)，且无 prev reason，可能是 app-killed
    const session = androidSessions.get(deviceId);
    if (session && session.lastSessionDurationMs > 0 && session.lastSessionDurationMs < 5 * 60 * 1000) {
      return 'app-killed';
    }
    return 'network-lost';
  }
  return 'unknown';
}

// ── 公开 API ──────────────────────────────────────────────

/**
 * 处理新 WS 连接：设置认证超时 → 监听消息 → 路由到对应处理器
 */
export function handleConnection(ws: WebSocket): void {
  let authenticated = false;
  let role: 'windows' | 'android' | 'android-detector' | null = null;
  let deviceId: string | null = null;
  const ts = new Date().toISOString();
  const remoteIp = (ws as any).socket?.remoteAddress ?? 'unknown';

  console.log(`[ws][${ts}] 新连接 ← ${remoteIp} (等待认证, 超时 ${config.wsAuthTimeoutMs}ms)`);

  // 标记新连接为存活（首次 ping 前默认存活），并监听 pong 回应
  markAlive(ws);
  ws.on('pong', () => markAlive(ws));

  // 5 秒内未认证则断开
  const authTimer = setTimeout(() => {
    if (!authenticated) {
      console.log(`[ws][${new Date().toISOString()}] 认证超时关闭 ← ${remoteIp}`);
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
        if (role === 'windows' || role === 'android-detector') handleHeartbeat(msg as WsHeartbeat);
        break;
      case 'heartbeat-android':
        if (role === 'android') handleHeartbeatAndroid(msg as WsHeartbeatAndroid);
        break;
      case 'alert':
        if (role === 'windows' || role === 'android-detector') {
          const alert = msg as WsAlertPush;
          alert.serverReceivedAt = new Date().toISOString();
          // 存入报警记录（供接收端历史查询）
          addAlert({
            alertId: alert.alertId,
            deviceId: alert.deviceId,
            deviceName: alert.deviceName,
            timestamp: alert.timestamp,
            detections: alert.detections,
            createdAt: Date.now(),
          });
          broadcastAlert(alert);
        }
        break;
      case 'command':
        if (role === 'android') handleCommand(ws, msg as WsCommand);
        break;
      case 'set-config':
        if (role === 'android') handleSetConfig(ws, msg as WsSetConfig);
        break;
      case 'command-ack':
        // 检测端主动发回的 ack（含前置校验错误原因），转发给所有 Android 接收端
        if (role === 'windows' || role === 'android-detector') handleWindowsCommandAck(msg as WsCommandAck, deviceId!);
        break;
      case 'request-screenshot':
        if (role === 'android') handleRequestScreenshot(ws, msg as WsRequestScreenshot, deviceId!);
        break;
      case 'screenshot-data':
        if (role === 'windows' || role === 'android-detector') handleScreenshotData(msg as WsScreenshotData);
        break;
      case 'disconnect-reason':
        // 客户端主动上报断开原因（帮助服务端诊断）
        handleDisconnectReason(msg as WsDisconnectReason, role, deviceId);
        break;
      case 'session-info':
        // Android 重连时上报上次 Session 信息（帮助服务端诊断）
        handleSessionInfo(msg as WsSessionInfo);
        break;
    }
  });

  ws.on('close', (code) => {
    clearTimeout(authTimer);
    const ts = new Date().toISOString();
    const codeName = getCloseCodeName(code);
    if (deviceId) {
      if (role === 'windows' || role === 'android-detector') {
        const existing = windowsClients.get(deviceId);
        if (existing && existing.ws === ws) {
          windowsClients.delete(deviceId);
          const roleLabel = role === 'android-detector' ? 'Android检测端' : 'Windows';
          console.log(`[ws][${ts}] ${roleLabel} 断开: ${deviceId} code=${code}(${codeName}) 检测端在线=${windowsClients.size}`);
          scheduleBroadcast();
        } else {
          const roleLabel = role === 'android-detector' ? 'Android检测端' : 'Windows';
          console.log(`[ws][${ts}] ${roleLabel} 旧连接关闭（已被新连接替代）: ${deviceId} code=${code}(${codeName})`);
        }
      } else if (role === 'android') {
        const existing = androidClients.get(deviceId);
        if (existing && existing.ws === ws) {
          const endReason = closeCodeToSessionEndReason(code, deviceId);
          const session = androidSessions.get(deviceId);
          if (session) {
            session.lastSessionEndReason = endReason;
            session.lastSessionDurationMs = Date.now() - session.connectedAt;
          }
          androidClients.delete(deviceId);
          console.log(`[ws][${ts}] Android 断开: ${deviceId} code=${code}(${codeName}) 推断原因=${endReason} Android在线=${androidClients.size}`);
          scheduleBroadcast();
        } else {
          console.log(`[ws][${ts}] Android 旧连接关闭（已被新连接替代）: ${deviceId} code=${code}(${codeName})`);
        }
      }
    } else {
      console.log(`[ws][${ts}] 未认证连接关闭 code=${code}(${codeName})`);
    }
  });

  ws.on('error', (err) => {
    const ts = new Date().toISOString();
    console.error(`[ws][${ts}] 连接错误 deviceId=${deviceId ?? 'unauthenticated'} role=${role ?? '?'} remoteIp=${remoteIp}: ${err.message}`);
    // 不在 error 中清理连接，ws 库保证 error 之后 close 必定触发。
    // 让 close handler 用准确的 close code 推断断开原因。
  });
}

/**
 * 向所有 Android 客户端广播报警
 */
export function broadcastAlert(alert: WsAlertPush): void {
  alert.serverRelayedAt = new Date().toISOString();
  const result = broadcastToAndroid(alert, `alert:${alert.alertId}`);
  console.log(`[ws][${new Date().toISOString()}] 报警广播: alertId=${alert.alertId} 发送成功=${result.success} 失败=${result.failed}`);
}

// ── 内部处理 ──────────────────────────────────────────────

function handleAuth(
  ws: WebSocket,
  msg: WsAuthMessage,
  authTimer: NodeJS.Timeout,
  onSuccess: (role: 'windows' | 'android' | 'android-detector', deviceId: string) => void,
): void {
  clearTimeout(authTimer);
  const ts = new Date().toISOString();

  if (!validateApiKey(msg.apiKey)) {
    console.log(`[ws][${ts}] 认证失败: API Key 无效 role=${msg.role} deviceId=${msg.deviceId}`);
    sendJson(ws, { type: 'auth-result', success: false, reason: 'invalid api key' });
    ws.close();
    return;
  }

  if (!meetsMinVersion(msg.version, config.minClientVersion)) {
    console.log(`[ws][${ts}] 认证失败: 版本过低 role=${msg.role} deviceId=${msg.deviceId} version=${msg.version ?? 'unknown'} min=${config.minClientVersion}`);
    sendJson(ws, { type: 'auth-result', success: false, reason: `version too old, require >= ${config.minClientVersion}` });
    ws.close();
    return;
  }

  if (msg.role === 'windows') {
    const existingClient = windowsClients.get(msg.deviceId);
    if (existingClient) {
      console.log(`[ws][${ts}] Windows 重复连接: ${msg.deviceName} (${msg.deviceId}) 踢掉旧连接`);
      // 先删除再 terminate，防止 close 事件误删新连接
      windowsClients.delete(msg.deviceId);
      sendJson(existingClient.ws, { type: 'kicked', reason: 'duplicate connection' });
      existingClient.ws.terminate();
    }
    const clientData: WindowsClient = {
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
    };
    windowsClients.set(msg.deviceId, clientData);
    console.log(`[ws][${ts}] Windows 上线: ${msg.deviceName} (${msg.deviceId}) | 检测端在线: ${windowsClients.size} 接收端在线: ${androidClients.size}`);
  } else if (msg.role === 'android-detector') {
    const existingClient = windowsClients.get(msg.deviceId);
    if (existingClient) {
      console.log(`[ws][${ts}] Android检测端 重复连接: ${msg.deviceName} (${msg.deviceId}) 踢掉旧连接`);
      windowsClients.delete(msg.deviceId);
      sendJson(existingClient.ws, { type: 'kicked', reason: 'duplicate connection' });
      existingClient.ws.terminate();
    }
    const clientData: WindowsClient = {
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
    };
    windowsClients.set(msg.deviceId, clientData);
    console.log(`[ws][${ts}] Android检测端 上线: ${msg.deviceName} (${msg.deviceId}) | 检测端在线: ${windowsClients.size} 接收端在线: ${androidClients.size}`);
  } else if (msg.role === 'android') {
    const existingClient = androidClients.get(msg.deviceId);
    if (existingClient) {
      console.log(`[ws][${ts}] Android 重复连接: ${msg.deviceId} 踢掉旧连接`);
      // 先删除再 terminate，防止 close 事件误删新连接
      androidClients.delete(msg.deviceId);
      sendJson(existingClient.ws, { type: 'kicked', reason: 'duplicate connection' });
      existingClient.ws.terminate();
    }
    // 使用闭包外本地变量捕获 deviceId，避免闭包陷阱
    const pingDeviceId = msg.deviceId;
    const clientData: AndroidClient = { ws, deviceId: msg.deviceId, lastSeen: new Date() };
    androidClients.set(msg.deviceId, clientData);

    // 追踪 Session：记录本次连接开始时间（用于计算断连时长）
    const prevSession = androidSessions.get(msg.deviceId);
    const now = Date.now();
    const session: AndroidSession = {
      connectedAt: now,
      lastSessionEndReason: prevSession?.lastSessionEndReason ?? 'unknown',
      lastSessionDurationMs: prevSession ? now - prevSession.connectedAt : -1,
    };
    androidSessions.set(msg.deviceId, session);

    // 打印重连诊断信息
    if (prevSession) {
      const durationSec = Math.round((now - prevSession.connectedAt) / 1000);
      const reasonDesc = SessionEndReasonNames[prevSession.lastSessionEndReason] ?? `code=${prevSession.lastSessionEndReason}`;
      console.log(`[ws][${ts}] Android 重连诊断: deviceId=${msg.deviceId} 上次持续${durationSec}s | 结束原因: ${reasonDesc} | 接收端在线: ${androidClients.size}`);
    } else {
      console.log(`[ws][${ts}] Android 首次连接: ${msg.deviceId} | 接收端在线: ${androidClients.size}`);
    }

    // 监听 OkHttp ping 帧（每 25s 一次），更新存活时间
    ws.on('ping', () => {
      const client = androidClients.get(pingDeviceId);
      if (client) {
        client.lastSeen = new Date();
      } else {
        console.log(`[ws][${new Date().toISOString()}] Android ping 但未找到客户端: ${pingDeviceId}`);
      }
    });
    console.log(`[ws][${ts}] Android 上线: ${msg.deviceId} | 接收端在线: ${androidClients.size}`);
  } else {
    console.log(`[ws][${ts}] 认证失败: 无效 role=${msg.role}`);
    sendJson(ws, { type: 'auth-result', success: false, reason: 'invalid role' });
    ws.close();
    return;
  }

  console.log(`[ws][${ts}] 认证成功: role=${msg.role} deviceId=${msg.deviceId} deviceName=${msg.deviceName ?? 'n/a'}`);
  sendJson(ws, { type: 'auth-result', success: true });
  onSuccess(msg.role, msg.deviceId);

  // 认证成功后，立即向本客户端发送完整设备列表（解决客户端就绪时序问题）
  sendJson(ws, {
    type: 'device-list',
    devices: buildDeviceList(),
  });

  scheduleBroadcast();
}

// 心跳计数（用于减少日志频率）
const _heartbeatCounter = new Map<string, number>();

function handleHeartbeat(msg: WsHeartbeat): void {
  const client = windowsClients.get(msg.deviceId);
  if (!client) {
    console.warn(`[ws][${new Date().toISOString()}] 心跳但客户端不存在: deviceId=${msg.deviceId}`);
    return;
  }

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

  const count = (_heartbeatCounter.get(msg.deviceId) ?? 0) + 1;
  _heartbeatCounter.set(msg.deviceId, count % 60 === 0 ? 0 : count);
  if (count === 1 || count % 60 === 0) {
    const silentSec = Math.round((Date.now() - client.lastSeen.getTime()) / 1000);
    const roleLabel = client.clientType === 'android-detector' ? 'Android检测端' : 'Windows';
    console.log(`[ws][${new Date().toISOString()}] ${roleLabel} 心跳: ${client.deviceName} (${msg.deviceId}) monitoring=${msg.isMonitoring} alarming=${msg.isAlarming} 静默${silentSec}s`);
  }

  client.lastSeen = new Date();

  if (changed) scheduleBroadcast();
}

/** Android 心跳处理（应用层心跳，补充 ping 帧） */
function handleHeartbeatAndroid(msg: WsHeartbeatAndroid): void {
  const client = androidClients.get(msg.deviceId);
  if (!client) {
    console.warn(`[ws][${new Date().toISOString()}] Android 心跳但客户端不存在: deviceId=${msg.deviceId}`);
    return;
  }
  client.lastSeen = new Date();
}

/** 客户端主动上报断开原因 */
function handleDisconnectReason(msg: WsDisconnectReason, role: string | null, deviceId: string | null): void {
  const ts = new Date().toISOString();
  console.log(`[ws][${ts}] 客户端断开原因报告: deviceId=${deviceId ?? '?'} role=${role ?? '?'} reason=${msg.reason} detail=${msg.detail ?? 'n/a'}`);
  // 同时更新 Session 记录
  if (deviceId && role === 'android') {
    const session = androidSessions.get(deviceId);
    if (session) {
      session.lastSessionEndReason = msg.reason;
      session.lastSessionDurationMs = Date.now() - session.connectedAt;
    }
  }
}

/** Android 重连时上报上次 Session 详细信息 */
function handleSessionInfo(msg: WsSessionInfo): void {
  const ts = new Date().toISOString();
  const durationSec = msg.lastSessionDurationMs >= 0 ? `${Math.round(msg.lastSessionDurationMs / 1000)}s` : '未知';
  const reasonDesc = SessionEndReasonNames[msg.lastSessionEndReason] ?? msg.lastSessionEndReason;
  console.log(`[ws][${ts}] Android Session 上报: deviceId=${msg.deviceId} isReconnect=${msg.isReconnect} 上次结束原因=${reasonDesc} 上次持续=${durationSec}`);

  // 采纳 Android 上报的结束原因（Android 端有更准确的上下文）
  const session = androidSessions.get(msg.deviceId);
  if (session) {
    session.lastSessionEndReason = msg.lastSessionEndReason;
    if (msg.lastSessionDurationMs >= 0) {
      session.lastSessionDurationMs = msg.lastSessionDurationMs;
    }
  }
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
    sendJson(senderWs, ack, 'command-ack->sender');
    console.warn(`[ws][${new Date().toISOString()}] 命令路由失败: target=${msg.targetDeviceId} command=${msg.command} reason=device-offline`);
    return;
  }

  // 转发给目标检测端（检测端会自行发回带 reason 的 command-ack）
  const relay: WsCommandRelay = { type: 'command', command: msg.command, targetDeviceId: msg.targetDeviceId };
  const sent = sendJson(target.ws, relay, `command->${msg.targetDeviceId}`);
  console.log(`[ws][${new Date().toISOString()}] 命令转发: command=${msg.command} target=${target.deviceName}(${msg.targetDeviceId}) success=${sent}`);

  // 注意：这里只发"已转发"的临时 ack；
  // 检测端执行后会再发一个带 success/reason 的 command-ack，
  // 由 handleWindowsCommandAck 转发给 Android 接收端。
  ack.success = true;
  ack.reason = 'relayed';
  sendJson(senderWs, ack, 'command-ack->sender');
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
    sendJson(senderWs, ack, 'set-config-ack->sender');
    console.warn(`[ws][${new Date().toISOString()}] 配置更新路由失败: target=${msg.targetDeviceId} key=${msg.key} value=${msg.value} reason=device-offline`);
    return;
  }

  // 转发 set-config 给目标检测端
  const relay: WsSetConfigRelay = { type: 'set-config', key: msg.key, value: msg.value, targetDeviceId: msg.targetDeviceId };
  const sent = sendJson(target.ws, relay, `set-config->${msg.targetDeviceId}`);
  console.log(`[ws][${new Date().toISOString()}] 配置更新转发: key=${msg.key} value=${msg.value} target=${target.deviceName}(${msg.targetDeviceId}) success=${sent}`);

  // 同样只发"已转发"，检测端执行后回 command-ack
  ack.success = true;
  ack.reason = 'relayed';
  sendJson(senderWs, ack, 'set-config-ack->sender');
}

/** 检测端主动回传的 command-ack（含具体执行结果），广播给所有 Android 接收端 */
function handleWindowsCommandAck(ack: WsCommandAck, detectorDeviceId: string): void {
  // 补充 targetDeviceId（检测端自己就是 target，让接收端知道是哪台设备的回执）
  const enriched = { ...ack, targetDeviceId: detectorDeviceId };
  const result = broadcastToAndroid(enriched, `command-ack:${ack.command}`);
  console.log(`[ws][${new Date().toISOString()}] 命令结果广播: device=${detectorDeviceId} command=${ack.command} success=${ack.success} reason=${ack.reason} 发送成功=${result.success} 失败=${result.failed}`);
}

/** Android 接收端请求截图：转发给目标检测端，登记待回传路由 */
function handleRequestScreenshot(senderWs: WebSocket, msg: WsRequestScreenshot, senderDeviceId: string): void {
  const target = windowsClients.get(msg.targetDeviceId);
  if (!target || target.ws.readyState !== WebSocket.OPEN) {
    // 目标不在线，直接告知请求方
    sendJson(senderWs, {
      type: 'screenshot-data',
      alertId: msg.alertId,
      imageBase64: '',
      width: 0,
      height: 0,
    }, 'screenshot-data->sender(target-offline)');
    console.warn(`[ws][${new Date().toISOString()}] 截图请求失败: alertId=${msg.alertId} target=${msg.targetDeviceId} reason=device-offline`);
    return;
  }
  // 登记路由：alertId → 发起请求的 Android deviceId（用于 screenshot-data 回传）
  const existing = pendingScreenshotRequests.get(msg.alertId) ?? new Set<string>();
  existing.add(senderDeviceId);
  pendingScreenshotRequests.set(msg.alertId, existing);
  pendingScreenshotTimestamps.set(msg.alertId, Date.now());
  const relay: WsRequestScreenshotRelay = { type: 'request-screenshot', alertId: msg.alertId };
  const sent = sendJson(target.ws, relay, `screenshot-request->${msg.targetDeviceId}`);
  console.log(`[ws][${new Date().toISOString()}] 截图请求转发: alertId=${msg.alertId} target=${target.deviceName}(${msg.targetDeviceId}) from=${senderDeviceId} success=${sent}`);
}

/** Windows 回传截图数据：路由给发起请求的 Android */
function handleScreenshotData(msg: WsScreenshotData): void {
  const targetDeviceIds = pendingScreenshotRequests.get(msg.alertId);
  if (!targetDeviceIds) {
    console.warn(`[ws][${new Date().toISOString()}] 截图数据无待回传路由: alertId=${msg.alertId} (可能已超时或请求不存在)`);
    return;
  }
  pendingScreenshotRequests.delete(msg.alertId);
  pendingScreenshotTimestamps.delete(msg.alertId);
  const data = JSON.stringify(msg);
  let success = 0, failed = 0;
  for (const targetDeviceId of targetDeviceIds) {
    const targetClient = androidClients.get(targetDeviceId);
    if (targetClient?.ws.readyState === WebSocket.OPEN) {
      try {
        targetClient.ws.send(data);
        success++;
      } catch (err: any) {
        console.error(`[ws][${new Date().toISOString()}] 截图数据发送失败: alertId=${msg.alertId} target=${targetDeviceId} error=${err.message}`);
        failed++;
      }
    } else {
      console.warn(`[ws][${new Date().toISOString()}] 截图数据目标不在线: alertId=${msg.alertId} target=${targetDeviceId}`);
      failed++;
    }
  }
  console.log(`[ws][${new Date().toISOString()}] 截图数据路由: alertId=${msg.alertId} 大小=${msg.imageBase64.length} bytes 发送成功=${success} 失败=${failed}`);
}

function broadcastDeviceList(): void {
  const list = buildDeviceList();
  const msg = { type: 'device-list', devices: list };
  const result = broadcastToAndroid(msg, 'device-list');
  if (result.failed > 0) {
    console.log(`[ws][${new Date().toISOString()}] 设备列表广播: ${list.length} 台 Windows 发送成功=${result.success} 失败=${result.failed}`);
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

function sendJson(ws: WebSocket, obj: object, context?: string): boolean {
  if (ws.readyState !== WebSocket.OPEN) {
    console.warn(`[ws][${new Date().toISOString()}] 消息发送失败 (连接未开放)${context ? ` [${context}]` : ''}`);
    return false;
  }
  try {
    ws.send(JSON.stringify(obj));
    return true;
  } catch (err: any) {
    console.error(`[ws][${new Date().toISOString()}] 消息发送异常${context ? ` [${context}]` : ''}: ${err.message}`);
    return false;
  }
}

/** 广播消息到所有 Android，返回成功/失败计数 */
function broadcastToAndroid(msg: object, context?: string): { success: number; failed: number } {
  let success = 0, failed = 0;
  for (const client of androidClients.values()) {
    if (sendJson(client.ws, msg, context)) {
      success++;
    } else {
      failed++;
    }
  }
  return { success, failed };
}

// ── 定时维护：每 30s ─────────────────────────────────────────────────────────
// 幽灵清理: 超过 config.deviceOfflineMs 无消息的连接视为死连接并终止。
// Windows 心跳 15s, Android 心跳 20s + OkHttp ping 20s, 75s 阈值可覆盖 3-4 轮丢失。
setInterval(() => {
  const now = Date.now();
  const ts = new Date().toISOString();
  const ghostDeadline = now - config.deviceOfflineMs;

  // Android 幽灵清理
  for (const [id, client] of androidClients) {
    if (client.lastSeen.getTime() < ghostDeadline) {
      const silentSec = Math.round((now - client.lastSeen.getTime()) / 1000);
      console.log(`[ws][${ts}] Android 幽灵清理: ${id} (静默 ${silentSec}s 阈值 ${config.deviceOfflineMs / 1000}s)`);
      client.ws.terminate();
      androidClients.delete(id);
      _heartbeatCounter.delete(id);
    }
  }

  // 检测端幽灵清理（Windows + Android检测端）
  for (const [id, client] of windowsClients) {
    if (client.lastSeen.getTime() < ghostDeadline) {
      const silentSec = Math.round((now - client.lastSeen.getTime()) / 1000);
      const roleLabel = client.clientType === 'android-detector' ? 'Android检测端' : 'Windows';
      console.log(`[ws][${ts}] ${roleLabel} 幽灵清理: ${client.deviceName} (${id}) 静默 ${silentSec}s 阈值 ${config.deviceOfflineMs / 1000}s`);
      client.ws.terminate();
      windowsClients.delete(id);
      _heartbeatCounter.delete(id);
    }
  }

  // 向检测端发送 keep-alive（防止客户端 60s 幽灵检测误判断连）
  for (const client of windowsClients.values()) {
    sendJson(client.ws, { type: 'ping' });
  }

  if (androidClients.size > 0 || windowsClients.size > 0) {
    broadcastDeviceList();
    const detectorCount = windowsClients.size;
    const androidDetectorCount = Array.from(windowsClients.values()).filter(c => c.clientType === 'android-detector').length;
    const windowsCount = detectorCount - androidDetectorCount;
    console.log(`[ws][${ts}] 定时推送 → 接收端:${androidClients.size} / Windows:${windowsCount} / Android检测端:${androidDetectorCount}`);
  }
}, 30_000);

// ── 截图请求超时检查：每 30s ─────────────────────────────────
// 超过 60 秒未收到截图数据的请求视为超时
setInterval(() => {
  const now = Date.now();
  const ts = new Date().toISOString();
  const deadline = now - SCREENSHOT_REQUEST_TIMEOUT_MS;

  for (const [alertId, timestamp] of pendingScreenshotTimestamps) {
    if (timestamp < deadline) {
      console.warn(`[ws][${ts}] 截图请求超时: alertId=${alertId} 等待${Math.round((now - timestamp) / 1000)}s`);
      pendingScreenshotRequests.delete(alertId);
      pendingScreenshotTimestamps.delete(alertId);
    }
  }
}, 30_000);
