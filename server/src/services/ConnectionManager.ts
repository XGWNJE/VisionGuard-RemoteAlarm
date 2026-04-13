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
  deviceId: string;
  connectedAt: number;            // 本次连接建立时间 (Date.now())
  lastSessionEndReason: string;   // 上次是怎么断的
  lastSessionDurationMs: number;  // 上次连接持续时长
}
const androidSessions = new Map<string, AndroidSession>();

// 追踪待处理的截图请求：alertId → androidDeviceId（用于 screenshot-data 回传路由）
const pendingScreenshotRequests = new Map<string, Set<string>>();

// 追踪截图请求创建时间（用于超时诊断）
const pendingScreenshotTimestamps = new Map<string, number>();

// 截图请求超时时间（60秒）
const SCREENSHOT_REQUEST_TIMEOUT_MS = 60_000;

// 广播防抖：50ms 内多次触发合并为一次，防止 20 台心跳同步时产生广播风暴
let _broadcastTimer: NodeJS.Timeout | null = null;
function scheduleBroadcast(): void {
  if (_broadcastTimer) return;
  _broadcastTimer = setTimeout(() => {
    _broadcastTimer = null;
    broadcastDeviceList();
  }, 50);
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
  let role: 'windows' | 'android' | null = null;
  let deviceId: string | null = null;
  const ts = new Date().toISOString();
  const remoteIp = (ws as any).socket?.remoteAddress ?? 'unknown';

  console.log(`[ws][${ts}] 新连接 ← ${remoteIp} (等待认证, 超时 ${config.wsAuthTimeoutMs}ms)`);

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
        if (role === 'windows') handleHeartbeat(msg as WsHeartbeat);
        break;
      case 'heartbeat-android':
        if (role === 'android') handleHeartbeatAndroid(msg as WsHeartbeatAndroid);
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
      if (role === 'windows' && windowsClients.has(deviceId)) {
        windowsClients.delete(deviceId);
        console.log(`[ws][${ts}] Windows 断开: ${deviceId} code=${code}(${codeName}) Windows在线=${windowsClients.size}`);
        scheduleBroadcast();
      } else if (role === 'android' && androidClients.has(deviceId)) {
        // 推断 Android 断开原因（Close Code 映射）
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
        console.log(`[ws][${ts}] 关闭事件（已由 error 清理）: ${deviceId} code=${code}(${codeName})`);
      }
    } else {
      console.log(`[ws][${ts}] 未认证连接关闭 code=${code}(${codeName})`);
    }
  });

  ws.on('error', (err) => {
    const ts = new Date().toISOString();
    console.error(`[ws][${ts}] 连接错误 deviceId=${deviceId ?? 'unauthenticated'} role=${role ?? '?'} remoteIp=${remoteIp}: ${err.message}`);
    if (deviceId) {
      if (role === 'windows' && windowsClients.has(deviceId)) {
        windowsClients.delete(deviceId);
        console.log(`[ws][${ts}] Windows 因错误移除: ${deviceId}`);
        scheduleBroadcast();
      } else if (role === 'android' && androidClients.has(deviceId)) {
        // 更新 session reason（与 close 中一致），防止 error→close 顺序时 reason 被跳过
        const session = androidSessions.get(deviceId);
        if (session) {
          session.lastSessionEndReason = 'app-killed'; // error 通常意味着进程异常终止
          session.lastSessionDurationMs = Date.now() - session.connectedAt;
        }
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
  const result = broadcastToAndroid(alert, `alert:${alert.alertId}`);
  console.log(`[ws][${new Date().toISOString()}] 报警广播: alertId=${alert.alertId} 发送成功=${result.success} 失败=${result.failed}`);
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
  const ts = new Date().toISOString();

  if (!validateApiKey(msg.apiKey)) {
    console.log(`[ws][${ts}] 认证失败: API Key 无效 role=${msg.role} deviceId=${msg.deviceId}`);
    sendJson(ws, { type: 'auth-result', success: false, reason: 'invalid api key' });
    ws.close();
    return;
  }

  if (msg.role === 'windows') {
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
    console.log(`[ws][${ts}] Windows 上线: ${msg.deviceName} (${msg.deviceId}) | Windows在线: ${windowsClients.size} Android在线: ${androidClients.size}`);
  } else if (msg.role === 'android') {
    // 修复: 使用闭包外的本地变量捕获 deviceId，避免闭包陷阱
    const pingDeviceId = msg.deviceId;
    const clientData: AndroidClient = { ws, deviceId: msg.deviceId, lastSeen: new Date() };
    androidClients.set(msg.deviceId, clientData);

    // 追踪 Session：记录本次连接开始时间（用于计算断连时长）
    const prevSession = androidSessions.get(msg.deviceId);
    const now = Date.now();
    const session: AndroidSession = {
      deviceId: msg.deviceId,
      connectedAt: now,
      lastSessionEndReason: prevSession?.lastSessionEndReason ?? 'unknown',
      lastSessionDurationMs: prevSession ? now - prevSession.connectedAt : -1,
    };
    androidSessions.set(msg.deviceId, session);

    // 打印重连诊断信息
    if (prevSession) {
      const durationSec = Math.round((now - prevSession.connectedAt) / 1000);
      const endReason: Record<string, string> = {
        'user-close': '用户主动关闭',
        'network-lost': '网络中断（被系统杀后台/锁屏休眠）',
        'server-kick': '服务器主动断开',
        'app-killed': '应用被强制停止',
        'unknown': '未知原因',
      };
      const reasonDesc = endReason[prevSession.lastSessionEndReason] ?? `code=${prevSession.lastSessionEndReason}`;
      console.log(`[ws][${ts}] Android 重连诊断: deviceId=${msg.deviceId} 上次持续${durationSec}s | 结束原因: ${reasonDesc} | Android在线: ${androidClients.size}`);
    } else {
      console.log(`[ws][${ts}] Android 首次连接: ${msg.deviceId} | Android在线: ${androidClients.size}`);
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
    console.log(`[ws][${ts}] Android 上线: ${msg.deviceId} | Android在线: ${androidClients.size}`);
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
  client.lastSeen = new Date();

  // 每 60 次心跳（约 5 分钟）打一次日志，避免刷屏
  const count = (_heartbeatCounter.get(msg.deviceId) ?? 0) + 1;
  _heartbeatCounter.set(msg.deviceId, count % 60 === 0 ? 0 : count);
  if (count === 1 || count % 60 === 0) {
    console.log(`[ws][${new Date().toISOString()}] Windows 心跳: ${client.deviceName} (${msg.deviceId}) monitoring=${msg.isMonitoring} alarming=${msg.isAlarming} 静默${Math.round((Date.now() - client.lastSeen.getTime()) / 1000)}s`);
  }

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
  const reasonNames: Record<string, string> = {
    'user-close': '用户主动关闭',
    'network-lost': '网络中断（被系统杀后台/锁屏休眠）',
    'server-kick': '服务器主动断开',
    'app-killed': '应用被强制停止',
    'unknown': '未知原因',
  };
  const durationSec = msg.lastSessionDurationMs >= 0 ? `${Math.round(msg.lastSessionDurationMs / 1000)}s` : '未知';
  const reasonDesc = reasonNames[msg.lastSessionEndReason] ?? msg.lastSessionEndReason;
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

  // 转发给目标 Windows（Windows 端会自行发回带 reason 的 command-ack）
  const relay: WsCommandRelay = { type: 'command', command: msg.command };
  const sent = sendJson(target.ws, relay, `command->${msg.targetDeviceId}`);
  console.log(`[ws][${new Date().toISOString()}] 命令转发: command=${msg.command} target=${target.deviceName}(${msg.targetDeviceId}) success=${sent}`);

  // 注意：这里只发"已转发"的临时 ack；
  // Windows 端执行后会再发一个带 success/reason 的 command-ack，
  // 由 handleWindowsCommandAck 转发给 Android。
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

  // 转发 set-config 给目标 Windows
  const relay: WsSetConfigRelay = { type: 'set-config', key: msg.key, value: msg.value };
  const sent = sendJson(target.ws, relay, `set-config->${msg.targetDeviceId}`);
  console.log(`[ws][${new Date().toISOString()}] 配置更新转发: key=${msg.key} value=${msg.value} target=${target.deviceName}(${msg.targetDeviceId}) success=${sent}`);

  // 同样只发"已转发"，Windows 端执行后回 command-ack
  ack.success = true;
  ack.reason = 'relayed';
  sendJson(senderWs, ack, 'set-config-ack->sender');
}

/** Windows 端主动回传的 command-ack（含具体执行结果），广播给所有 Android */
function handleWindowsCommandAck(ack: WsCommandAck, windowsDeviceId: string): void {
  // 补充 targetDeviceId（Windows 自己就是 target，让 Android 知道是哪台设备的回执）
  const enriched = { ...ack, targetDeviceId: windowsDeviceId };
  const result = broadcastToAndroid(enriched, `command-ack:${ack.command}`);
  console.log(`[ws][${new Date().toISOString()}] 命令结果广播: device=${windowsDeviceId} command=${ack.command} success=${ack.success} reason=${ack.reason} 发送成功=${result.success} 失败=${result.failed}`);
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
// 幽灵清理: 75s 内无消息视为离线（心跳 5s × 3 = 15s，加上网络波动余量）
// Android 端依赖 OkHttp ping 帧(25s) 维持，Windows 端依赖心跳(5s) 维持
setInterval(() => {
  const now = Date.now();
  const ts = new Date().toISOString();
  const ghostDeadline = now - 75_000;

  // Android 幽灵清理
  for (const [id, client] of androidClients) {
    if (client.lastSeen.getTime() < ghostDeadline) {
      const silentSec = Math.round((now - client.lastSeen.getTime()) / 1000);
      console.log(`[ws][${ts}] Android 幽灵清理: ${id} (静默 ${silentSec}s 阈值 75s)`);
      client.ws.terminate();
      androidClients.delete(id);
      _heartbeatCounter.delete(id);
    }
  }

  // Windows 幽灵清理（新增，与 Android 一样检测）
  for (const [id, client] of windowsClients) {
    if (client.lastSeen.getTime() < ghostDeadline) {
      const silentSec = Math.round((now - client.lastSeen.getTime()) / 1000);
      console.log(`[ws][${ts}] Windows 幽灵清理: ${client.deviceName} (${id}) 静默 ${silentSec}s 阈值 75s`);
      client.ws.terminate();
      windowsClients.delete(id);
      _heartbeatCounter.delete(id);
    }
  }

  if (androidClients.size > 0 || windowsClients.size > 0) {
    broadcastDeviceList();
    console.log(`[ws][${ts}] 定时推送 → ${androidClients.size} Android / ${windowsClients.size} Windows`);
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
