// ┌─────────────────────────────────────────────────────────┐
// │ DarkSlider.cs                                           │
// │ 角色：Win11 Fluent 风格滑块（替代原生 TrackBar）        │
// │ 用途：置信度阈值调节                                    │
// │ 对外 API：Value, Minimum, Maximum, ValueChanged         │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace VisionGuard.UI
{
    /// <summary>
    /// Win11 Fluent 风格滑块，完全自绘，替代原生 TrackBar。
    /// 暴露与 TrackBar 兼容的 Value/Minimum/Maximum/Enabled/ValueChanged，
    /// Form1.cs 中除字段声明和构造外无需改动其他引用。
    /// </summary>
    public class DarkSlider : Control
    {
        // ── 值 ──────────────────────────────────────────────────────
        private int _value   = 45;
        private int _minimum = 10;
        private int _maximum = 90;

        public int Value
        {
            get { return _value; }
            set
            {
                int clamped = Math.Max(_minimum, Math.Min(_maximum, value));
                if (clamped == _value) return;
                _value = clamped;
                Invalidate();
                ValueChanged?.Invoke(this, EventArgs.Empty);
            }
        }

        public int Minimum
        {
            get { return _minimum; }
            set { _minimum = value; Value = _value; Invalidate(); }
        }

        public int Maximum
        {
            get { return _maximum; }
            set { _maximum = value; Value = _value; Invalidate(); }
        }

        public event EventHandler ValueChanged;

        // ── 拖拽状态 ─────────────────────────────────────────────────
        private bool  _dragging;
        private bool  _hovered;

        // ── 颜色常量 ─────────────────────────────────────────────────
        private static readonly Color TrackBg      = Color.FromArgb(58,  58,  58);
        private static readonly Color TrackFill    = Color.FromArgb( 0, 120, 212);
        private static readonly Color TrackDisable = Color.FromArgb(85,  85,  85);
        private static readonly Color ThumbColor   = Color.White;
        private static readonly Color ThumbDisable = Color.FromArgb(102, 102, 102);

        private const int TrackH  = 4;
        private const int ThumbR  = 6;   // 半径
        private const int TrackPad = 8;   // 轨道左右留白

        public DarkSlider()
        {
            SetStyle(
                ControlStyles.UserPaint |
                ControlStyles.AllPaintingInWmPaint |
                ControlStyles.OptimizedDoubleBuffer, true);

            Height  = 28;
            Cursor  = Cursors.Hand;
        }

        // ── Enabled (new，Control.Enabled 未标记 virtual）────────────
        public new bool Enabled
        {
            get { return base.Enabled; }
            set { base.Enabled = value; Invalidate(); }
        }

        // ── 拇指 X 坐标 ──────────────────────────────────────────────
        private float ThumbX()
        {
            if (_maximum == _minimum) return TrackPad;
            return TrackPad + (float)(_value - _minimum) / (_maximum - _minimum) * (Width - TrackPad * 2);
        }

        // ── 绘制 ─────────────────────────────────────────────────────
        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;

            int cy   = Height / 2;
            float tx = ThumbX();

            bool disabled = !Enabled;

            // 1. 轨道背景
            int trackY = cy - TrackH / 2;
            using (GraphicsPath bg = TrackPath(TrackPad, trackY, Width - TrackPad * 2, TrackH, 2))
            using (SolidBrush brush = new SolidBrush(TrackBg))
                g.FillPath(brush, bg);

            // 2. 填充段
            float fillW = tx - TrackPad;
            if (fillW > 0)
            {
                Rectangle fillRect = new Rectangle(TrackPad, trackY, (int)fillW, TrackH);
                using (GraphicsPath fp = TrackPath(fillRect.X, fillRect.Y, fillRect.Width, fillRect.Height, 2))
                using (SolidBrush brush = new SolidBrush(disabled ? TrackDisable : TrackFill))
                    g.FillPath(brush, fp);
            }

            // 3. 拇指圆
            int d = ThumbR * 2;
            RectangleF thumbRect = new RectangleF(tx - ThumbR, cy - ThumbR, d, d);

            // 阴影（半透明圆，稍大）
            if (!disabled)
            {
                RectangleF shadow = new RectangleF(tx - ThumbR - 1, cy - ThumbR - 1, d + 2, d + 2);
                using (SolidBrush sh = new SolidBrush(Color.FromArgb(60, 0, 0, 0)))
                    g.FillEllipse(sh, shadow);
            }

            using (SolidBrush brush = new SolidBrush(disabled ? ThumbDisable : ThumbColor))
                g.FillEllipse(brush, thumbRect);
        }

        private static GraphicsPath TrackPath(int x, int y, int w, int h, int r)
        {
            var path = new GraphicsPath();
            int d = r * 2;
            if (w < d) { path.AddEllipse(x, y, w, h); return path; }
            path.AddArc(x,         y,     d, h, 180, 180);
            path.AddArc(x + w - d, y,     d, h,   0, 180);
            path.CloseFigure();
            return path;
        }

        // ── 鼠标交互 ─────────────────────────────────────────────────
        protected override void OnMouseEnter(EventArgs e) { _hovered = true;  Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { _hovered = false; Invalidate(); base.OnMouseLeave(e); }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            if (!Enabled) return;
            if (e.Button == MouseButtons.Left)
            {
                _dragging = true;
                UpdateValueFromX(e.X);
            }
            base.OnMouseDown(e);
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            if (_dragging) UpdateValueFromX(e.X);
            base.OnMouseMove(e);
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            _dragging = false;
            base.OnMouseUp(e);
        }

        protected override void OnMouseWheel(MouseEventArgs e)
        {
            if (!Enabled) return;
            Value += e.Delta > 0 ? 1 : -1;
            base.OnMouseWheel(e);
        }

        private void UpdateValueFromX(int mouseX)
        {
            int range = Width - TrackPad * 2;
            if (range <= 0) return;
            float ratio = (float)(mouseX - TrackPad) / range;
            ratio = Math.Max(0f, Math.Min(1f, ratio));
            Value = _minimum + (int)Math.Round(ratio * (_maximum - _minimum));
        }
    }
}
