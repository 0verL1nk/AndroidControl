package dev.ling.agent.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator

/**
 * 被协助时的屏幕边缘荧光提示。
 *
 * 参考 Google 的「Android Halo」——AI 代理在后台工作时，用屏幕边缘的低调动效告诉用户
 * "它正在做事"。我们的场景更敏感（有人正在操作这台手机），所以做得更醒目：
 * 四周一圈呼吸的荧光 + 顶部状态条。
 *
 * 三条硬要求：
 * 1. **不能挡住操作**：窗口带 FLAG_NOT_TOUCHABLE，触摸直接穿透到下面的应用；
 * 2. **不能被无视**：整屏范围常驻，且状态条同时用「图标 + 文字 + 颜色」表达，不靠颜色单打独斗；
 * 3. **能被随时关掉**：由服务控制生命周期，会话结束立即消失。
 *
 * 需要「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW），属于一次性部署项。
 */
class ControlIndicator(private val context: Context) {

    private var view: GlowBorderView? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = view != null

    fun show() {
        if (view != null) return
        if (!canDrawOverlays()) {
            Log.w(TAG, "没有悬浮窗权限，无法显示被协助提示")
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val glow = GlowBorderView(context)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不可触摸、不可聚焦：只做提示，绝不干扰使用
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 让荧光延伸到刘海/挖孔区域，看起来才是"贴边的整圈"
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        runCatching {
            wm.addView(glow, layoutParams)
            view = glow
            params = layoutParams
            glow.start()
            Log.i(TAG, "被协助提示已显示")
        }.onFailure { Log.e(TAG, "显示被协助提示失败", it) }
    }

    fun hide() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        view?.let { v ->
            runCatching { v.stop() }
            runCatching { wm.removeView(v) }
        }
        view = null
        params = null
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    /** 画一圈呼吸的荧光边框，外加顶部状态条。 */
    private inner class GlowBorderView(context: Context) : View(context) {

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
            color = GLOW_COLOR
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(14f)
            color = GLOW_COLOR
        }
        private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = CHIP_BACKGROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = GLOW_COLOR
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(15f)
            isFakeBoldText = true
        }

        private var phase = 0f
        private var animator: ValueAnimator? = null

        fun start() {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2400
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stop() {
            runCatching { animator?.cancel() }
            animator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // 呼吸：整圈亮度在 0.45 ~ 1.0 之间缓慢起伏
            val alpha = (110 + 145 * phase).toInt().coerceIn(0, 255)
            val inset = dp(4f)
            val rect = RectF(inset, inset, width - inset, height - inset)
            val radius = dp(28f)

            glowPaint.alpha = alpha / 3
            canvas.drawRoundRect(rect, radius, radius, glowPaint)

            borderPaint.alpha = alpha
            canvas.drawRoundRect(rect, radius, radius, borderPaint)

            drawStatusChip(canvas, alpha)
        }

        /** 顶部状态条：图标（圆点）+ 文字，避免只靠颜色表达状态。 */
        private fun drawStatusChip(canvas: Canvas, alpha: Int) {
            val label = "正在被协助"
            val textWidth = textPaint.measureText(label)
            val paddingH = dp(14f)
            val chipHeight = dp(30f)
            val dotSize = dp(8f)
            val gap = dp(8f)

            val chipWidth = paddingH * 2 + dotSize + gap + textWidth
            val left = (width - chipWidth) / 2f
            val top = dp(16f)
            val rect = RectF(left, top, left + chipWidth, top + chipHeight)

            chipPaint.alpha = (alpha * 0.92f).toInt()
            canvas.drawRoundRect(rect, chipHeight / 2f, chipHeight / 2f, chipPaint)

            dotPaint.alpha = alpha
            val cy = top + chipHeight / 2f
            canvas.drawCircle(left + paddingH + dotSize / 2f, cy, dotSize / 2f, dotPaint)

            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, left + paddingH + dotSize + gap, baseline, textPaint)
        }

        private fun dp(value: Float): Float = value * resources.displayMetrics.density
    }

    private companion object {
        const val TAG = "ControlIndicator"

        /** 用设计令牌里的 info 色（被协助 = 蓝），与"就绪=绿""出错=红"区分开。 */
        val GLOW_COLOR = Color.parseColor("#4C8DFF")

        val CHIP_BACKGROUND = Color.parseColor("#CC0B57D0")
    }
}
