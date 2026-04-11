// ┌─────────────────────────────────────────────────────────┐
// │ CocoClassPickerControl.cs                               │
// │ 角色：COCO 80类中英文搜索勾选控件 (UserControl)         │
// │ 用途：Form1 "目标" 页面，选择要监控的目标类别           │
// │ 对外 API：SelectedClasses, SetSelection(), SelectionChanged│
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;
using VisionGuard.Capture;
using VisionGuard.Data;

namespace VisionGuard.UI
{
    /// <summary>
    /// COCO 80类中英文搜索勾选控件（UserControl）。
    /// 对外暴露 SelectedClasses（英文名集合）和 SelectionChanged 事件。
    /// </summary>
    internal class CocoClassPickerControl : UserControl
    {
        // ── 控件 ─────────────────────────────────────────────────────
        private Panel            _searchPanel;
        private TextBox          _txtSearch;
        private Label            _lblCount;
        private HiddenScrollCheckedListBox _clbClasses;

        // ── 状态 ─────────────────────────────────────────────────────
        /// <summary>当前勾选的英文类名（在过滤/重建列表时保持）</summary>
        private readonly HashSet<string> _selected =
            new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        /// <summary>当前列表中实际显示的英文类名（顺序与列表项一一对应）</summary>
        private readonly List<string> _displayed = new List<string>();

        /// <summary>勾选状态变化时触发</summary>
        public event EventHandler SelectionChanged;

        // ── 公开属性 ─────────────────────────────────────────────────
        /// <summary>当前勾选的英文类名集合（只读快照）</summary>
        public HashSet<string> SelectedClasses =>
            new HashSet<string>(_selected, StringComparer.OrdinalIgnoreCase);

        public CocoClassPickerControl()
        {
            AutoScaleMode       = AutoScaleMode.Dpi;
            AutoScaleDimensions = new SizeF(96F, 96F);
            BackColor = Color.FromArgb(35, 35, 35);

            BuildUI();
            RebuildList(string.Empty);
        }

        protected override void OnFontChanged(System.EventArgs e)
        {
            base.OnFontChanged(e);
            // 搜索栏高度跟随字体行高
            if (_searchPanel != null)
                _searchPanel.Height = Font.Height + 10;
        }

        // ── 公开方法 ─────────────────────────────────────────────────

        /// <summary>从外部（LoadSettings）批量设置勾选状态</summary>
        public void SetSelection(HashSet<string> selected)
        {
            _selected.Clear();
            if (selected != null)
                foreach (var s in selected) _selected.Add(s);

            RebuildList(_txtSearch?.Text ?? string.Empty);
        }

        // ── 构建 UI ──────────────────────────────────────────────────

        private void BuildUI()
        {
            // 顶部搜索条：高度跟随字体
            _searchPanel = new Panel
            {
                Dock      = DockStyle.Top,
                Height    = Font.Height + 10,
                BackColor = Color.FromArgb(35, 35, 35)
            };

            _txtSearch = new TextBox
            {
                Dock      = DockStyle.Fill,
                BackColor = Color.FromArgb(50, 50, 50),
                ForeColor = Color.White,
                BorderStyle = BorderStyle.FixedSingle
            };
            _txtSearch.HandleCreated += (s, e) =>
                NativeMethods.SendMessage(_txtSearch.Handle,
                    NativeMethods.EM_SETCUEBANNER, IntPtr.Zero, "搜索类别…");
            _txtSearch.TextChanged += (s, e) => RebuildList(_txtSearch.Text);

            _lblCount = new Label
            {
                Dock      = DockStyle.Right,
                Width     = 36,
                TextAlign = ContentAlignment.MiddleCenter,
                ForeColor = Color.Gray,
                BackColor = Color.FromArgb(35, 35, 35)
                // 字体继承自父控件，不硬编码
            };

            _searchPanel.Controls.Add(_txtSearch);
            _searchPanel.Controls.Add(_lblCount);

            // 勾选列表
            _clbClasses = new HiddenScrollCheckedListBox
            {
                Dock           = DockStyle.Fill,
                BackColor      = Color.FromArgb(40, 40, 40),
                ForeColor      = Color.LightGray,
                BorderStyle    = BorderStyle.None,
                CheckOnClick   = true,
                IntegralHeight = false
            };
            _clbClasses.ItemCheck += OnItemCheck;

            Controls.Add(_clbClasses);
            Controls.Add(_searchPanel);
        }

        // ── 列表重建 ─────────────────────────────────────────────────

        private void RebuildList(string filter)
        {
            filter = filter?.Trim() ?? string.Empty;

            // 暂时解除事件，避免 SetItemChecked 触发 ItemCheck 造成循环
            _clbClasses.ItemCheck -= OnItemCheck;

            _clbClasses.BeginUpdate();
            _clbClasses.Items.Clear();
            _displayed.Clear();

            foreach (string en in CocoClassMap.EnglishNames)
            {
                string zh = CocoClassMap.EnZh.TryGetValue(en, out string z) ? z : string.Empty;

                bool match = string.IsNullOrEmpty(filter)
                    || en.IndexOf(filter, StringComparison.OrdinalIgnoreCase) >= 0
                    || zh.IndexOf(filter, StringComparison.OrdinalIgnoreCase) >= 0;

                if (!match) continue;

                string display = string.IsNullOrEmpty(zh) ? en : $"{en}  {zh}";
                _clbClasses.Items.Add(display, _selected.Contains(en));
                _displayed.Add(en);
            }

            _clbClasses.EndUpdate();
            _clbClasses.ItemCheck += OnItemCheck;

            UpdateCountLabel();
        }

        // ── 勾选事件 ─────────────────────────────────────────────────

        private void OnItemCheck(object sender, ItemCheckEventArgs e)
        {
            // ItemCheck 在状态实际改变前触发，e.NewValue 是新状态
            string en = _displayed[e.Index];
            if (e.NewValue == CheckState.Checked)
                _selected.Add(en);
            else
                _selected.Remove(en);

            // 延迟更新计数（状态提交后）
            BeginInvoke(new Action(UpdateCountLabel));
            SelectionChanged?.Invoke(this, EventArgs.Empty);
        }

        // ── 辅助 ─────────────────────────────────────────────────────

        private void UpdateCountLabel()
        {
            _lblCount.Text = _selected.Count == 0
                ? "全部"
                : _selected.Count.ToString();
        }
    }
}
