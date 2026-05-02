// ┌─────────────────────────────────────────────────────────┐
// │ MaskEditorForm.cs                                       │
// │ 角色：在捕获快照上拖拽绘制多个遮罩矩形                  │
// │ 输入：底图 Bitmap + 已有遮罩列表（相对坐标 [0,1]）      │
// │ 对外 API：Masks（关闭后读取，相对坐标 [0,1]）           │
// │ 与 Android MaskEditorScreen 行为对齐：                  │
// │   - 多矩形、半透明红色填充                              │
// │   - 进行中矩形黄色提示                                  │
// │   - 最小尺寸 0.02（相对）                               │
// │   - 工具栏：撤销 / 清空 / 取消 / 确定                   │
// │   - ESC 取消                                            │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace VisionGuard.UI
{
    /// <summary>
    /// 遮罩编辑器：在捕获快照上拖拽绘制多个矩形遮罩。
    /// 关闭后通过 Masks 属性读取相对坐标 [0,1] 的遮罩列表。
    /// DialogResult.OK 表示用户点击「确定」；DialogResult.Cancel 表示取消（保留原列表）。
    /// </summary>
    public class MaskEditorForm : Form
    {
        // ── 最小相对尺寸（与 Android 端 MaskEditorScreen 对齐）───────
        private const float MinRelativeSize = 0.02f;

        // ── 输入 ────────────────────────────────────────────────────
        private readonly Bitmap _background;

        // ── 状态 ────────────────────────────────────────────────────
        private readonly List<RectangleF> _masks; // 像素坐标，绘制完成时同步换算到相对坐标
        private bool      _dragging;
        private Point     _startPoint;
        private Rectangle _draggingRect;

        // ── UI ─────────────────────────────────────────────────────
        private Panel            _toolbar;
        private Panel            _canvas;
        private FlatRoundButton  _btnUndo;
        private FlatRoundButton  _btnClear;
        private FlatRoundButton  _btnCancel;
        private FlatRoundButton  _btnConfirm;
        private Label            _lblHint;

        /// <summary>
        /// 关闭后读取最终遮罩列表（相对坐标 [0,1]）。
        /// 仅当 DialogResult == OK 时调用方应使用此结果替换现有列表。
        /// </summary>
        public IReadOnlyList<RectangleF> Masks { get; private set; } = new List<RectangleF>();

        // ════════════════════════════════════════════════════════════
        // 构造
        // ════════════════════════════════════════════════════════════

        /// <param name="background">捕获目标的快照底图（窗口截图或屏幕区域截图）。</param>
        /// <param name="initialMasks">已存在的遮罩，相对坐标 [0,1]；可为空。</param>
        public MaskEditorForm(Bitmap background, IList<RectangleF> initialMasks)
        {
            _background = background ?? throw new ArgumentNullException(nameof(background));

            // 把传入的相对坐标转换为像素坐标，存入内部 _masks
            _masks = new List<RectangleF>();
            if (initialMasks != null)
            {
                int W = background.Width;
                int H = background.Height;
                foreach (var r in initialMasks)
                {
                    float x = r.X * W;
                    float y = r.Y * H;
                    float w = r.Width * W;
                    float h = r.Height * H;
                    if (w > 0 && h > 0)
                        _masks.Add(new RectangleF(x, y, w, h));
                }
            }

            BuildUI();
        }

        // ════════════════════════════════════════════════════════════
        // UI 构建
        // ════════════════════════════════════════════════════════════

        private void BuildUI()
        {
            // 工具栏高度
            const int toolbarH = 48;
            int bgW = _background.Width;
            int bgH = _background.Height;

            // 限制最大尺寸不超过工作区域；超出时按比例缩放
            Rectangle workArea = Screen.PrimaryScreen.WorkingArea;
            int maxW = workArea.Width - 40;
            int maxH = workArea.Height - 40 - toolbarH;
            float scale = 1.0f;
            if (bgW > maxW || bgH > maxH)
                scale = Math.Min((float)maxW / bgW, (float)maxH / bgH);
            int canvasW = (int)(bgW * scale);
            int canvasH = (int)(bgH * scale);

            Text            = "遮罩区域编辑器";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox     = false;
            MinimizeBox     = false;
            StartPosition   = FormStartPosition.CenterScreen;
            BackColor       = Color.FromArgb(28, 28, 28);
            ForeColor       = Color.White;
            ClientSize      = new Size(canvasW, canvasH + toolbarH);
            KeyPreview      = true;
            DoubleBuffered  = true;

            // ── 工具栏 ─────────────────────────────────────────────
            _toolbar = new Panel
            {
                Dock      = DockStyle.Top,
                Height    = toolbarH,
                BackColor = Color.FromArgb(38, 38, 38),
            };
            Controls.Add(_toolbar);

            _btnUndo    = MakeButton("撤销",   100);
            _btnClear   = MakeButton("清空",   100);
            _btnCancel  = MakeButton("取消",   100);
            _btnConfirm = MakeButton("确定",   100, accent: true);

            _btnUndo.Top    = (toolbarH - _btnUndo.Height)    / 2;
            _btnClear.Top   = (toolbarH - _btnClear.Height)   / 2;
            _btnCancel.Top  = (toolbarH - _btnCancel.Height)  / 2;
            _btnConfirm.Top = (toolbarH - _btnConfirm.Height) / 2;

            _btnUndo.Left   = 12;
            _btnClear.Left  = _btnUndo.Right + 8;
            _btnConfirm.Left = canvasW - _btnConfirm.Width - 12;
            _btnCancel.Left  = _btnConfirm.Left - _btnCancel.Width - 8;

            _toolbar.Controls.Add(_btnUndo);
            _toolbar.Controls.Add(_btnClear);
            _toolbar.Controls.Add(_btnCancel);
            _toolbar.Controls.Add(_btnConfirm);

            _lblHint = new Label
            {
                AutoSize  = true,
                ForeColor = Color.Gainsboro,
                BackColor = Color.Transparent,
                Font      = new Font("Microsoft YaHei UI", 9f),
                Text      = "拖拽鼠标绘制矩形遮罩；遮罩区域将在推理与报警截图中显示为黑色",
            };
            _toolbar.Controls.Add(_lblHint);
            _toolbar.Resize += (s, e) =>
            {
                _lblHint.Left = _btnClear.Right + 16;
                _lblHint.Top  = (toolbarH - _lblHint.Height) / 2;
            };
            _lblHint.Left = _btnClear.Right + 16;
            _lblHint.Top  = (toolbarH - _lblHint.Height) / 2;

            // ── 画布 ──────────────────────────────────────────────
            _canvas = new DoubleBufferedPanel
            {
                Dock      = DockStyle.Fill,
                BackColor = Color.Black,
                Cursor    = Cursors.Cross,
            };
            Controls.Add(_canvas);
            _canvas.BringToFront();

            _canvas.Paint     += Canvas_Paint;
            _canvas.MouseDown += Canvas_MouseDown;
            _canvas.MouseMove += Canvas_MouseMove;
            _canvas.MouseUp   += Canvas_MouseUp;

            // ── 事件 ──────────────────────────────────────────────
            _btnUndo.Click   += (s, e) => Undo();
            _btnClear.Click  += (s, e) => Clear();
            _btnCancel.Click += (s, e) => { DialogResult = DialogResult.Cancel; Close(); };
            _btnConfirm.Click += (s, e) => Confirm();

            KeyDown += (s, e) =>
            {
                if (e.KeyCode == Keys.Escape)
                {
                    DialogResult = DialogResult.Cancel;
                    Close();
                }
            };
        }

        private FlatRoundButton MakeButton(string text, int width, bool accent = false)
        {
            var b = new FlatRoundButton
            {
                Text      = text,
                Width     = width,
                Height    = 32,
                Font      = new Font("Microsoft YaHei UI", 9f),
                ForeColor = Color.White,
            };
            if (accent)
            {
                b.NormalColor = Color.FromArgb(0, 120, 212);
                b.HoverColor  = Color.FromArgb(16, 132, 224);
                b.PressColor  = Color.FromArgb(0, 100, 184);
            }
            return b;
        }

        // ════════════════════════════════════════════════════════════
        // 鼠标事件（基于画布坐标，绘制时缩放到底图坐标）
        // ════════════════════════════════════════════════════════════

        private void Canvas_MouseDown(object sender, MouseEventArgs e)
        {
            if (e.Button != MouseButtons.Left) return;
            _dragging     = true;
            _startPoint   = e.Location;
            _draggingRect = new Rectangle(e.Location, Size.Empty);
        }

        private void Canvas_MouseMove(object sender, MouseEventArgs e)
        {
            if (!_dragging) return;
            _draggingRect = NormalizeRect(_startPoint, e.Location);
            _canvas.Invalidate();
        }

        private void Canvas_MouseUp(object sender, MouseEventArgs e)
        {
            if (!_dragging || e.Button != MouseButtons.Left) return;
            _dragging = false;

            Rectangle rCanvas = NormalizeRect(_startPoint, e.Location);
            _draggingRect = Rectangle.Empty;

            // 限制在画布范围内
            rCanvas.Intersect(_canvas.ClientRectangle);

            if (rCanvas.Width > 0 && rCanvas.Height > 0)
            {
                // 画布坐标 → 底图坐标
                RectangleF rImg = CanvasToImage(rCanvas);

                // 最小尺寸校验（相对底图尺寸，0.02f）
                float relW = rImg.Width  / _background.Width;
                float relH = rImg.Height / _background.Height;
                if (relW >= MinRelativeSize && relH >= MinRelativeSize)
                {
                    _masks.Add(rImg);
                }
            }

            _canvas.Invalidate();
        }

        // ════════════════════════════════════════════════════════════
        // 绘制
        // ════════════════════════════════════════════════════════════

        private void Canvas_Paint(object sender, PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.None;
            g.InterpolationMode = InterpolationMode.HighQualityBilinear;

            // 1. 底图（按画布尺寸缩放绘制）
            g.DrawImage(_background, 0, 0, _canvas.ClientSize.Width, _canvas.ClientSize.Height);

            // 2. 已有遮罩：半透明红色填充 + 红色边框
            using (var fill = new SolidBrush(Color.FromArgb(100, Color.Red)))
            using (var pen  = new Pen(Color.Red, 2f))
            {
                foreach (var m in _masks)
                {
                    Rectangle rc = ImageToCanvas(m);
                    g.FillRectangle(fill, rc);
                    g.DrawRectangle(pen, rc);
                }
            }

            // 3. 进行中矩形：黄色虚线框 + 半透明黄色填充
            if (_dragging && _draggingRect.Width > 0 && _draggingRect.Height > 0)
            {
                using (var fill = new SolidBrush(Color.FromArgb(80, Color.Gold)))
                    g.FillRectangle(fill, _draggingRect);
                using (var pen = new Pen(Color.Gold, 2f) { DashStyle = DashStyle.Dash })
                    g.DrawRectangle(pen, _draggingRect);

                // 尺寸提示
                string hint = $"{_draggingRect.Width} × {_draggingRect.Height}";
                using (var font = new Font("Consolas", 10f))
                using (var brush = new SolidBrush(Color.Gold))
                    g.DrawString(hint, font, brush,
                        _draggingRect.Right + 4, _draggingRect.Bottom + 4);
            }

            // 4. 顶部状态栏：当前遮罩数
            string status = _masks.Count == 0 ? "未绘制遮罩" : $"已绘制 {_masks.Count} 个遮罩";
            using (var font  = new Font("Microsoft YaHei UI", 10f, FontStyle.Bold))
            using (var brush = new SolidBrush(Color.FromArgb(220, Color.White)))
            using (var bg    = new SolidBrush(Color.FromArgb(140, Color.Black)))
            {
                SizeF sz = g.MeasureString(status, font);
                var bgRect = new RectangleF(8, 8, sz.Width + 12, sz.Height + 6);
                g.FillRectangle(bg, bgRect);
                g.DrawString(status, font, brush, 14, 11);
            }
        }

        // ════════════════════════════════════════════════════════════
        // 工具栏命令
        // ════════════════════════════════════════════════════════════

        private void Undo()
        {
            if (_masks.Count == 0) return;
            _masks.RemoveAt(_masks.Count - 1);
            _canvas.Invalidate();
        }

        private void Clear()
        {
            if (_masks.Count == 0) return;
            _masks.Clear();
            _canvas.Invalidate();
        }

        private void Confirm()
        {
            // 像素坐标 → 相对坐标 [0,1]，clamp 防越界
            var result = new List<RectangleF>(_masks.Count);
            float W = _background.Width;
            float H = _background.Height;
            foreach (var r in _masks)
            {
                float x = Math.Max(0f, Math.Min(1f, r.X / W));
                float y = Math.Max(0f, Math.Min(1f, r.Y / H));
                float w = Math.Max(0f, Math.Min(1f - x, r.Width  / W));
                float h = Math.Max(0f, Math.Min(1f - y, r.Height / H));
                if (w >= MinRelativeSize && h >= MinRelativeSize)
                    result.Add(new RectangleF(x, y, w, h));
            }
            Masks = result;
            DialogResult = DialogResult.OK;
            Close();
        }

        // ════════════════════════════════════════════════════════════
        // 坐标换算
        // ════════════════════════════════════════════════════════════

        /// <summary>底图坐标 → 画布像素矩形。</summary>
        private Rectangle ImageToCanvas(RectangleF imgRect)
        {
            float sx = (float)_canvas.ClientSize.Width  / _background.Width;
            float sy = (float)_canvas.ClientSize.Height / _background.Height;
            return new Rectangle(
                (int)Math.Round(imgRect.X      * sx),
                (int)Math.Round(imgRect.Y      * sy),
                (int)Math.Round(imgRect.Width  * sx),
                (int)Math.Round(imgRect.Height * sy));
        }

        /// <summary>画布像素矩形 → 底图坐标。</summary>
        private RectangleF CanvasToImage(Rectangle canvasRect)
        {
            float sx = (float)_background.Width  / _canvas.ClientSize.Width;
            float sy = (float)_background.Height / _canvas.ClientSize.Height;
            return new RectangleF(
                canvasRect.X      * sx,
                canvasRect.Y      * sy,
                canvasRect.Width  * sx,
                canvasRect.Height * sy);
        }

        private static Rectangle NormalizeRect(Point a, Point b)
        {
            return new Rectangle(
                Math.Min(a.X, b.X),
                Math.Min(a.Y, b.Y),
                Math.Abs(b.X - a.X),
                Math.Abs(b.Y - a.Y));
        }

        // ════════════════════════════════════════════════════════════
        // 释放
        // ════════════════════════════════════════════════════════════

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            base.OnFormClosed(e);
            // 底图由调用方持有并 Dispose
        }

        // ── 内嵌：双缓冲 Panel（避免拖拽时闪烁）──────────────────────
        private sealed class DoubleBufferedPanel : Panel
        {
            public DoubleBufferedPanel()
            {
                SetStyle(
                    ControlStyles.UserPaint |
                    ControlStyles.AllPaintingInWmPaint |
                    ControlStyles.OptimizedDoubleBuffer |
                    ControlStyles.ResizeRedraw, true);
            }
        }
    }
}
