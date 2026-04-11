// ┌─────────────────────────────────────────────────────────┐
// │ WindowPickerForm.cs                                     │
// │ 角色：暗色风格窗口选择对话框                            │
// │ 用途：Form1 "捕获" 页面选择目标窗口                     │
// │ 对外 API：SelectedWindow (ShowDialog 后读取)            │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Threading;
using System.Windows.Forms;
using VisionGuard.Capture;

namespace VisionGuard.UI
{
    /// <summary>
    /// 暗色风格窗口选择对话框。
    /// 用法：<c>using (var f = new WindowPickerForm(excludeHwnd)) { f.ShowDialog(); var w = f.SelectedWindow; }</c>
    /// </summary>
    internal class WindowPickerForm : Form
    {
        public WindowInfo SelectedWindow { get; private set; }

        private readonly IntPtr   _excludeHwnd;
        private ListBox           _lstWindows;
        private FlatRoundButton   _btnRefresh;
        private FlatRoundButton   _btnOk;
        private FlatRoundButton   _btnCancel;
        private Label             _lblHint;
        private List<WindowInfo>  _windowList = new List<WindowInfo>();

        public WindowPickerForm(IntPtr excludeHwnd)
        {
            _excludeHwnd = excludeHwnd;

            AutoScaleMode       = AutoScaleMode.Dpi;
            AutoScaleDimensions = new SizeF(96F, 96F);
            Text                = "选择目标窗口";
            Size                = new Size(520, 420);
            MinimumSize         = new Size(400, 320);
            StartPosition       = FormStartPosition.CenterParent;
            BackColor           = Color.FromArgb(28, 28, 28);
            ForeColor           = Color.LightGray;
            FormBorderStyle     = FormBorderStyle.Sizable;

            BuildUI();
            Shown += (s, e) => LoadWindowsAsync();
        }

        // ── 构建 UI ──────────────────────────────────────────────────

        private void BuildUI()
        {
            // 提示标签
            _lblHint = new Label
            {
                Dock      = DockStyle.Top,
                Height    = 28,
                Text      = "双击或选中后点击「确定」以选择目标窗口",
                TextAlign = ContentAlignment.MiddleLeft,
                Padding   = new Padding(8, 0, 0, 0),
                ForeColor = Color.Gray
            };

            // 窗口列表
            _lstWindows = new ListBox
            {
                Dock            = DockStyle.Fill,
                BackColor       = Color.FromArgb(38, 38, 38),
                ForeColor       = Color.LightGray,
                BorderStyle     = BorderStyle.None,
                IntegralHeight  = false,
                Font            = new Font("Segoe UI", 9.5f)
            };
            _lstWindows.DoubleClick += (s, e) => AcceptSelection();

            // 底部按钮条
            var btnPanel = new Panel
            {
                Dock      = DockStyle.Bottom,
                Height    = 46,
                BackColor = Color.FromArgb(35, 35, 35),
                Padding   = new Padding(8, 6, 8, 6)
            };

            _btnRefresh = MakeButton("↻ 刷新", Color.FromArgb(55, 55, 75), Color.FromArgb(70, 70, 95));
            _btnOk      = MakeButton("确定",   Color.FromArgb(0, 100, 180), Color.FromArgb(0, 120, 210));
            _btnCancel  = MakeButton("取消",   Color.FromArgb(55, 55, 55), Color.FromArgb(70, 70, 70));

            _btnRefresh.Click += (s, e) => LoadWindowsAsync();
            _btnOk.Click      += (s, e) => AcceptSelection();
            _btnCancel.Click  += (s, e) => Close();

            _btnCancel.Bounds  = new Rectangle(btnPanel.Width  - 100, 6, 90, 30);
            _btnOk.Bounds      = new Rectangle(btnPanel.Width  - 200, 6, 90, 30);
            _btnRefresh.Bounds = new Rectangle(8, 6, 90, 30);

            _btnCancel.Anchor  = AnchorStyles.Right | AnchorStyles.Top;
            _btnOk.Anchor      = AnchorStyles.Right | AnchorStyles.Top;

            btnPanel.Controls.AddRange(new Control[] { _btnRefresh, _btnOk, _btnCancel });

            Controls.Add(_lstWindows);
            Controls.Add(_lblHint);
            Controls.Add(btnPanel);
        }

        // ── 加载窗口列表 ─────────────────────────────────────────────

        private void LoadWindowsAsync()
        {
            _lblHint.Text      = "正在枚举窗口…";
            _lstWindows.Enabled = false;

            ThreadPool.QueueUserWorkItem(_ =>
            {
                List<WindowInfo> list;
                try
                {
                    list = WindowEnumerator.GetWindows(_excludeHwnd);
                }
                catch
                {
                    list = new List<WindowInfo>();
                }

                BeginInvoke(new Action(() =>
                {
                    _windowList = list;
                    _lstWindows.BeginUpdate();
                    _lstWindows.Items.Clear();
                    foreach (var w in list)
                        _lstWindows.Items.Add(w.ToString());
                    _lstWindows.EndUpdate();
                    _lstWindows.Enabled = true;
                    _lblHint.Text = $"找到 {list.Count} 个窗口，双击或选中后点击「确定」";
                    _lblHint.ForeColor = Color.Gray;
                }));
            });
        }

        // ── 确认选择 ─────────────────────────────────────────────────

        private void AcceptSelection()
        {
            int idx = _lstWindows.SelectedIndex;
            if (idx < 0 || idx >= _windowList.Count) return;
            SelectedWindow = _windowList[idx];
            DialogResult   = DialogResult.OK;
            Close();
        }

        // ── 辅助 ─────────────────────────────────────────────────────

        private static FlatRoundButton MakeButton(string text, Color normal, Color hover)
        {
            return new FlatRoundButton
            {
                Text        = text,
                NormalColor = normal,
                HoverColor  = hover,
                ForeColor   = Color.White
            };
        }
    }
}
