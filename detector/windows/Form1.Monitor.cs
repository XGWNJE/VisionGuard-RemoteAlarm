// Form1.Monitor.cs — 监控控制：区域选择、启停监控、推理回调
using System;
using System.Drawing;
using System.IO;
using System.Windows.Forms;
using VisionGuard.Capture;
using VisionGuard.Models;
using VisionGuard.Services;
using VisionGuard.UI;

namespace VisionGuard
{
    public partial class Form1
    {
        // ════════════════════════════════════════════════════════════
        // 按钮事件
        // ════════════════════════════════════════════════════════════

        // ── 选择屏幕区域 ─────────────────────────────────────────────

        private void BtnSelectRegion_Click(object sender, EventArgs e)
        {
            if (_targetWindow != null)
            {
                // WindowHandle 模式：在目标窗口截图上框选子区域
                Bitmap snapshot;
                try
                {
                    snapshot = WindowCapturer.CaptureWindow(_targetWindow.Handle, Rectangle.Empty);
                }
                catch (Exception ex)
                {
                    _log.Error("无法捕获目标窗口截图：" + ex.Message);
                    return;
                }

                using (snapshot)
                using (var selector = new RegionSelectorForm(snapshot))
                {
                    selector.ShowDialog(this);
                    if (selector.SelectedRegion != Rectangle.Empty)
                    {
                        _windowSubRegion = selector.SelectedRegion;
                        _log.Info($"已选择子区域：{_windowSubRegion.Width}×{_windowSubRegion.Height} @ ({_windowSubRegion.X},{_windowSubRegion.Y})");
                    }
                    else
                    {
                        _windowSubRegion = Rectangle.Empty;
                        _log.Info("子区域已清除，将捕获整个窗口。");
                    }
                    UpdateRegionLabel();
                }
            }
            else
            {
                // ScreenRegion 模式：全屏半透明遮罩拖拽
                using (var selector = new RegionSelectorForm())
                {
                    Hide();
                    selector.ShowDialog(this);
                    Show();
                    BringToFront();

                    if (selector.SelectedRegion != Rectangle.Empty)
                    {
                        _screenRegion = selector.SelectedRegion;
                        _log.Info($"已选择区域：({_screenRegion.X}, {_screenRegion.Y})  {_screenRegion.Width}×{_screenRegion.Height}");
                        UpdateRegionLabel();
                    }
                }
            }
        }

        // ── 选择目标窗口 ─────────────────────────────────────────────

        private void BtnPickWindow_Click(object sender, EventArgs e)
        {
            using (var picker = new WindowPickerForm(Handle))
            {
                if (picker.ShowDialog(this) == DialogResult.OK && picker.SelectedWindow != null)
                {
                    _targetWindow    = picker.SelectedWindow;
                    _windowSubRegion = Rectangle.Empty;  // 重置子区域
                    _btnSelectRegion.Text = "选择子区域…";
                    UpdateRegionLabel();
                    _log.Info($"已选择目标窗口：{_targetWindow.Title}  [{_targetWindow.ClassName}]");
                }
            }
        }

        // ── 编辑遮罩区域 ─────────────────────────────────────────────

        private void BtnEditMasks_Click(object sender, EventArgs e)
        {
            // 1. 校验已选定捕获目标
            if (!IsRegionReady)
            {
                MessageBox.Show(
                    "请先选择监控区域或目标窗口。",
                    "未选择捕获目标", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }

            // 2. 抓一帧底图（与 MonitorService 实际监控帧的尺寸/裁切一致）
            Bitmap snapshot;
            try
            {
                if (_targetWindow != null)
                {
                    snapshot = WindowCapturer.CaptureWindow(_targetWindow.Handle, _windowSubRegion);
                }
                else
                {
                    snapshot = ScreenCapturer.CaptureRegion(_screenRegion);
                }
            }
            catch (Exception ex)
            {
                _log.Error("抓取底图失败：" + ex.Message);
                MessageBox.Show(
                    "无法抓取底图：" + ex.Message,
                    "捕获失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }

            if (snapshot == null || snapshot.Width <= 0 || snapshot.Height <= 0)
            {
                _log.Warn("抓取底图返回空，无法打开遮罩编辑器。");
                snapshot?.Dispose();
                return;
            }

            // 3. 打开编辑器
            using (snapshot)
            using (var editor = new MaskEditorForm(snapshot, _maskRegions))
            {
                if (editor.ShowDialog(this) == DialogResult.OK)
                {
                    _maskRegions = new System.Collections.Generic.List<RectangleF>(editor.Masks);
                    SaveSettings();
                    UpdateMaskInfoLabel();

                    // 监控运行中：热更新 MonitorService 配置（下个 Tick 即生效）
                    if (_monitorService.IsStarted)
                        _monitorService.UpdateConfig(BuildConfig());

                    _log.Info(_maskRegions.Count == 0
                        ? "已清空遮罩区域。"
                        : $"已更新遮罩区域：{_maskRegions.Count} 个。");
                }
            }
        }

        // ── 开始监控 ─────────────────────────────────────────────────

        private void BtnStart_Click(object sender, EventArgs e) => StartMonitor(remote: false);

        /// <summary>
        /// 启动监控推理。remote=true 时失败通过 command-ack 返回，不弹 MessageBox。
        /// </summary>
        private void StartMonitor(bool remote)
        {
            if (_monitorService.IsStarted)
            {
                if (remote) _serverPushService.SendCommandAck("resume", false, "监控已在运行");
                return;
            }

            if (!File.Exists(ModelPath))
            {
                if (remote)
                {
                    _serverPushService.SendCommandAck("resume", false, "模型文件不存在");
                    _log.Warn("[Server] 收到 resume，但模型文件不存在。");
                }
                else
                {
                    MessageBox.Show(
                        $"找不到模型文件：\n{ModelPath}\n\n请参阅 Assets/ASSETS_README.md。",
                        "模型缺失", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
                return;
            }

            MonitorConfig cfg = BuildConfig();

            // 验证有效捕获源
            if (cfg.CaptureMode == CaptureMode.ScreenRegion)
            {
                if (cfg.CaptureRegion.Width < 32 || cfg.CaptureRegion.Height < 32)
                {
                    if (remote)
                    {
                        _serverPushService.SendCommandAck("resume", false, "未选择捕获区域，请先在捕获页框选区域");
                        _log.Warn("[Server] 收到 resume，但捕获区域未设置。");
                    }
                    else
                    {
                        MessageBox.Show("捕获区域太小（最小 32×32），请重新选择。",
                            "区域无效", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    }
                    return;
                }
            }
            else // WindowHandle
            {
                if (cfg.TargetWindowHandle == IntPtr.Zero)
                {
                    if (remote)
                    {
                        _serverPushService.SendCommandAck("resume", false, "目标窗口句柄无效，请重新选择");
                        _log.Warn("[Server] 收到 resume，但目标窗口句柄无效。");
                    }
                    else
                    {
                        MessageBox.Show("请先点击「选择窗口…」选择目标窗口。",
                            "未选择目标窗口", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    }
                    return;
                }
            }

            try
            {
                _monitorService.Start(ModelPath, cfg);
                UpdateControlState(started: true);
                string src = cfg.CaptureMode == CaptureMode.WindowHandle
                    ? $"窗口「{cfg.TargetWindowTitle}」"
                    : $"区域 {cfg.CaptureRegion}";
                _log.Info($"监控已启动 | {src} | {cfg.TargetFps} FPS | 阈值 {cfg.ConfidenceThreshold:P0}");
                if (remote)
                {
                    _serverPushService.SendCommandAck("resume", true);
                    _log.Info("[Server] 收到 resume，监控推理已启动。");
                }

                // 状态突变后立即推送心跳，让接收端秒级感知
                _serverPushService.UpdateHeartbeatParams(
                    isMonitoring: _monitorService.IsStarted,
                    isReady: IsRegionReady,
                    cooldown: _sliderCooldown.Value,
                    confidence: _trkThreshold.Value / 100f,
                    targets: GetWatchedClassesString());
                _serverPushService.SendHeartbeatNow();

                _heartbeatTimer?.Start(); // 定时器已在 LoadSettings 创建，此处确保运行中
            }
            catch (Exception ex)
            {
                string fullMsg = BuildExceptionMessage(ex);
                _log.Error("启动失败：" + fullMsg);
                if (remote)
                    _serverPushService.SendCommandAck("resume", false, "启动异常：" + ex.Message);
                else
                    MessageBox.Show(fullMsg, "启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        // ── 停止监控 ─────────────────────────────────────────────────

        private void BtnStop_Click(object sender, EventArgs e) => StopMonitor(remote: false);

        /// <summary>
        /// 停止监控推理。remote=true 时通过 command-ack 返回结果。
        /// </summary>
        private void StopMonitor(bool remote)
        {
            if (!_monitorService.IsStarted)
            {
                if (remote) _serverPushService.SendCommandAck("pause", false, "监控未运行");
                return;
            }

            _monitorService.Stop();
            // 不停止心跳定时器：服务器依赖心跳判断在线状态，停止心跳会导致 Android 误报掉线
            // isMonitoring=false 通过下次 tick 自动传递，同时立即发一次最终状态
            _serverPushService.UpdateHeartbeatParams(isMonitoring: false, isReady: IsRegionReady,
                cooldown: _sliderCooldown.Value,
                confidence: _trkThreshold.Value / 100f,
                targets: GetWatchedClassesString());
            _serverPushService.SendHeartbeatNow();
            UpdateControlState(started: false);
            _log.Info(remote ? "[Server] 收到 pause，监控推理已停止。" : "监控已停止。");
            if (remote) _serverPushService.SendCommandAck("pause", true);
        }

        // ════════════════════════════════════════════════════════════
        // MonitorService 回调（ThreadPool 线程）
        // ════════════════════════════════════════════════════════════

        private void OnFrameProcessed(object sender, FrameResultEventArgs e)
        {
            if (e.HasError)
            {
                _log.Error(e.Error.Message);
                return;
            }

            _overlayPanel.UpdateFrame(e.Frame, e.Detections);

            string inferText = $"推理 {e.InferenceMs} ms";
            BeginInvoke(new Action(() => _tsInferMs.Text = inferText));
        }

        private void OnAlertTriggered(object sender, AlertEvent e)
        {
            string msg = string.Empty;
            foreach (var d in e.Detections)
                msg += $"[{d.Label} {d.Confidence:P0}] ";

            _log.Warn("报警：" + msg.Trim());

            BeginInvoke(new Action(() =>
                _tsLastAlert.Text = "最后报警：" + e.Timestamp.ToString("HH:mm:ss")));

            // 推送到服务器（fire-and-forget，内部克隆 PNG bytes）
            _serverPushService.PushAlert(e);

            e.Snapshot?.Dispose();
        }
    }
}
