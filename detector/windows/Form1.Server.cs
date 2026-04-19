// Form1.Server.cs — 设置持久化 + 服务器推送事件 + 远程配置
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;
using VisionGuard.Capture;
using VisionGuard.Models;
using VisionGuard.Services;
using VisionGuard.Utils;

namespace VisionGuard
{
    public partial class Form1
    {
        // ════════════════════════════════════════════════════════════
        // 设置持久化
        // ════════════════════════════════════════════════════════════

        private void LoadSettings()
        {
            SettingsStore.Load();

            // 阈值 / 参数
            _trkThreshold.Value = Math.Max(_trkThreshold.Minimum,
                Math.Min(_trkThreshold.Maximum, SettingsStore.GetInt("ConfidenceThresholdPct", 45)));
            _txtFps.Text      = SettingsStore.GetInt("TargetFps",            2).ToString();
            _txtThreads.Text  = SettingsStore.GetInt("IntraOpNumThreads",    2).ToString();
            _txtCooldown.Text = SettingsStore.GetInt("AlertCooldownSeconds", 5).ToString();

            // 监控对象（中英文选择器）
            HashSet<string> watched = SettingsStore.GetStringList("WatchedClasses");
            _classPicker.SetSelection(watched);

            // 捕获模式
            string modeStr = SettingsStore.GetString("CaptureMode", CaptureMode.ScreenRegion.ToString());
            if (Enum.TryParse(modeStr, out CaptureMode mode) && mode == CaptureMode.WindowHandle)
            {
                string title = SettingsStore.GetString("TargetWindowTitle", string.Empty);
                if (!string.IsNullOrEmpty(title))
                {
                    var windows = WindowEnumerator.GetWindows(Handle);
                    WindowInfo found = null;
                    foreach (var w in windows)
                        if (w.Title.Equals(title, StringComparison.OrdinalIgnoreCase))
                        { found = w; break; }

                    if (found != null)
                    {
                        _targetWindow = found;
                        _btnSelectRegion.Text = "选择子区域…";
                        _log.Info($"已恢复目标窗口：{found.Title}");
                    }
                    else
                    {
                        _log.Warn($"目标窗口「{title}」未找到，已回退到屏幕区域模式。");
                    }
                }

                string subStr = SettingsStore.GetString("WindowSubRegion", string.Empty);
                if (!string.IsNullOrEmpty(subStr))
                {
                    var parts = subStr.Split(',');
                    if (parts.Length == 4
                        && int.TryParse(parts[0], out int x)
                        && int.TryParse(parts[1], out int y)
                        && int.TryParse(parts[2], out int w)
                        && int.TryParse(parts[3], out int h))
                    {
                        _windowSubRegion = new Rectangle(x, y, w, h);
                    }
                }
            }
            else
            {
                string regStr = SettingsStore.GetString("ScreenRegion", string.Empty);
                if (!string.IsNullOrEmpty(regStr))
                {
                    var parts = regStr.Split(',');
                    if (parts.Length == 4
                        && int.TryParse(parts[0], out int x)
                        && int.TryParse(parts[1], out int y)
                        && int.TryParse(parts[2], out int w)
                        && int.TryParse(parts[3], out int h))
                    {
                        _screenRegion = new Rectangle(x, y, w, h);
                    }
                }
            }

            UpdateRegionLabel();

            // 服务器页：恢复设备名
            _txtDeviceName.Text = SettingsStore.GetString("DeviceName", Environment.MachineName);

            // 启动时自动连接（服务器地址/Key 已硬编码）
            {
                string deviceId = EnsureDeviceId();
                WireServerPushEvents();
                _serverPushService.Configure(
                    ServerUrl,
                    ServerApiKey,
                    deviceId,
                    _txtDeviceName.Text.Trim());
                _log.Info("[Server] 自动连接中…");
            }

            // 启动心跳定时器（3秒）—— 连接建立后立即开始，无论监控是否运行
            _heartbeatTimer = new System.Windows.Forms.Timer { Interval = 3000 };
            _heartbeatTimer.Tick += (s, ev) =>
                _serverPushService.UpdateHeartbeatParams(
                    isMonitoring: _monitorService.IsStarted,
                    isAlarming:   _alertService.IsAlarming,
                    isReady:       IsRegionReady,
                    cooldown:      ParseInt(_txtCooldown.Text, 1, 300, 5),
                    confidence:    _trkThreshold.Value / 100f,
                    targets:       string.Join(",", _classPicker.SelectedClasses));
            _heartbeatTimer.Start();
        }

        private void SaveSettings()
        {
            SettingsStore.Set("ConfidenceThresholdPct",  _trkThreshold.Value);
            SettingsStore.Set("TargetFps",               ParseInt(_txtFps.Text,      1,  5, 2));
            SettingsStore.Set("IntraOpNumThreads",        ParseInt(_txtThreads.Text,  1,  8, 2));
            SettingsStore.Set("AlertCooldownSeconds",     ParseInt(_txtCooldown.Text, 1, 300, 5));

            SettingsStore.Set("WatchedClasses",
                string.Join(",", _classPicker.SelectedClasses));

            // 服务器设置：只保存设备名
            SettingsStore.Set("DeviceName", _txtDeviceName.Text.Trim());

            if (_targetWindow != null)
            {
                SettingsStore.Set("CaptureMode",       CaptureMode.WindowHandle.ToString());
                SettingsStore.Set("TargetWindowTitle",  _targetWindow.Title);
                SettingsStore.Set("WindowSubRegion",
                    _windowSubRegion == Rectangle.Empty
                        ? string.Empty
                        : $"{_windowSubRegion.X},{_windowSubRegion.Y},{_windowSubRegion.Width},{_windowSubRegion.Height}");
            }
            else
            {
                SettingsStore.Set("CaptureMode", CaptureMode.ScreenRegion.ToString());
                SettingsStore.Set("ScreenRegion",
                    $"{_screenRegion.X},{_screenRegion.Y},{_screenRegion.Width},{_screenRegion.Height}");
            }

            SettingsStore.Save();
        }

        // ── 服务器设置辅助 ────────────────────────────────────────────

        /// <summary>首次调用时自动生成 DeviceId 并持久化，用户不可见</summary>
        private string EnsureDeviceId()
        {
            string id = SettingsStore.GetString("DeviceId", string.Empty);
            if (string.IsNullOrEmpty(id))
            {
                id = Guid.NewGuid().ToString();
                SettingsStore.Set("DeviceId", id);
                SettingsStore.Save();
            }
            return id;
        }

        private void WireServerPushEvents()
        {
            _serverPushService.ConnectionStateChanged += (s, state) =>
            {
                BeginInvoke(new Action(() =>
                {
                    switch (state)
                    {
                        case "connected":
                            _lblConnState.Text      = "● 已连接";
                            _lblConnState.ForeColor = Color.LimeGreen;
                            _lblConnDetail.Text     = "WebSocket 已就绪，报警推送正常";
                            _lblConnDetail.ForeColor = Color.FromArgb(150, 255, 150);
                            _btnRetry.Enabled = true;
                            // 连接服务器成功 → 强制关闭本地铃声（由远程统一管控）
                            if (_alertService.IsAlarming)
                            {
                                _alertService.StopAlarm();
                                _log.Info("[Server] 已连接服务器，本地铃声已自动关闭。");
                            }
                            break;
                        case "connecting":
                            _lblConnState.Text      = "◌ 连接中…";
                            _lblConnState.ForeColor = Color.Goldenrod;
                            _lblConnDetail.Text     = "正在连接服务器，请稍候…";
                            _lblConnDetail.ForeColor = Color.Goldenrod;
                            _btnRetry.Enabled = false;
                            break;
                        default:  // disconnected
                            _lblConnState.Text      = "● 未连接";
                            _lblConnState.ForeColor = Color.Gray;
                            _lblConnDetail.Text     = "连接断开，将自动重连  ·  点击「手动重试」立即重连";
                            _lblConnDetail.ForeColor = Color.DimGray;
                            _btnRetry.Enabled = true;
                            break;
                    }
                }));
            };

            _serverPushService.CommandReceived += (s, cmd) =>
            {
                BeginInvoke(new Action(() =>
                {
                    switch (cmd)
                    {
                        case "pause":
                            StopMonitor(remote: true);
                            break;

                        case "resume":
                            StartMonitor(remote: true);
                            break;

                        case "stop-alarm":
                            if (!_alertService.IsAlarming)
                            {
                                _serverPushService.SendCommandAck(cmd, false, "当前无报警");
                            }
                            else
                            {
                                _alertService.StopAlarm();
                                _serverPushService.SendCommandAck(cmd, true);
                                _log.Info("[Server] 收到命令：停止报警。");
                            }
                            break;
                    }
                    _serverPushService.UpdateHeartbeatParams(
                        isMonitoring: _monitorService.IsStarted,
                        isAlarming:   _alertService.IsAlarming,
                        isReady:      IsRegionReady,
                        cooldown:     ParseInt(_txtCooldown.Text, 1, 300, 5),
                        confidence:   _trkThreshold.Value / 100f,
                        targets:      string.Join(",", _classPicker.SelectedClasses));
                    _serverPushService.SendHeartbeatNow();
                }));
            };

            _serverPushService.SetConfigReceived += (s, kv) =>
            {
                BeginInvoke(new Action(() =>
                {
                    ApplyRemoteConfig(kv.Key, kv.Value);
                    _serverPushService.UpdateHeartbeatParams(
                        isMonitoring: _monitorService.IsStarted,
                        isAlarming:   _alertService.IsAlarming,
                        isReady:      IsRegionReady,
                        cooldown:     ParseInt(_txtCooldown.Text, 1, 300, 5),
                        confidence:   _trkThreshold.Value / 100f,
                        targets:      string.Join(",", _classPicker.SelectedClasses));
                    _serverPushService.SendHeartbeatNow();
                }));
            };
        }


        /// <summary>
        /// 应用 Android 端下发的参数调整命令（set-config）。
        /// 支持的 key：cooldown / confidence / targets
        /// </summary>
        private void ApplyRemoteConfig(string key, string value)
        {
            switch (key)
            {
                case "cooldown":
                    if (int.TryParse(value, out int cd) && cd >= 1 && cd <= 300)
                    {
                        _txtCooldown.Text = cd.ToString();
                        // 如果正在监控，实时更新 MonitorService 的配置
                        if (_monitorService.IsStarted)
                            _monitorService.UpdateConfig(BuildConfig());
                        _serverPushService.SendCommandAck("set-config:cooldown", true);
                        _log.Info($"[Server] 远程调整冷却时间 → {cd}s");
                        SaveSettings();
                    }
                    else
                    {
                        _serverPushService.SendCommandAck("set-config:cooldown", false, "值无效（1-300）");
                    }
                    break;

                case "confidence":
                    if (float.TryParse(value,
                        System.Globalization.NumberStyles.Float,
                        System.Globalization.CultureInfo.InvariantCulture,
                        out float conf) && conf >= 0.1f && conf <= 0.99f)
                    {
                        int pct = (int)(conf * 100);
                        _trkThreshold.Value = Math.Max(_trkThreshold.Minimum,
                                              Math.Min(_trkThreshold.Maximum, pct));
                        if (_monitorService.IsStarted)
                            _monitorService.UpdateConfig(BuildConfig());
                        _serverPushService.SendCommandAck("set-config:confidence", true);
                        _log.Info($"[Server] 远程调整置信度 → {pct}%");
                        SaveSettings();
                    }
                    else
                    {
                        _serverPushService.SendCommandAck("set-config:confidence", false, "值无效（0.1-0.99）");
                    }
                    break;

                case "targets":
                    // value 为逗号分隔的类名，空字符串 = 全部
                    var classes = new System.Collections.Generic.HashSet<string>(
                        StringComparer.OrdinalIgnoreCase);
                    if (!string.IsNullOrWhiteSpace(value))
                        foreach (var cls in value.Split(','))
                        {
                            string trimmed = cls.Trim();
                            if (!string.IsNullOrEmpty(trimmed))
                                classes.Add(trimmed);
                        }
                    _classPicker.SetSelection(classes);
                    if (_monitorService.IsStarted)
                        _monitorService.UpdateConfig(BuildConfig());
                    _serverPushService.SendCommandAck("set-config:targets", true);
                    _log.Info($"[Server] 远程调整监控目标 → {(classes.Count == 0 ? "全部" : string.Join(",", classes))}");
                    SaveSettings();
                    break;

                default:
                    _serverPushService.SendCommandAck($"set-config:{key}", false, $"未知配置项: {key}");
                    break;
            }
        }
    }
}
