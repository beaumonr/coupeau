package com.coupeau.voicebubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/** The round floating button. It draws its own icon so the app needs no image files. */
class BubbleView(context: Context) : View(context) {

    enum class Mode { IDLE, LISTENING, READY_TO_SEND }

    var mode = Mode.IDLE
        set(value) {
            field = value
            if (value == Mode.LISTENING) pulse.start() else pulse.cancel()
            level = 0f
            invalidate()
        }

    /** Fades the bubble while it is dragged over the "hide" zone. */
    var dropTarget = false
        set(value) {
            field = value
            alpha = if (value) 0.35f else 1f
        }

    /** Moved out of the way so you can type: drawn a little smaller. */
    var parked = false
        set(value) {
            field = value
            updateScale()
        }

    /** Held and ready to drag: drawn a little bigger. */
    var pickedUp = false
        set(value) {
            field = value
            updateScale()
        }

    private fun updateScale() {
        val sc = when {
            pickedUp -> 1.12f
            parked -> 0.78f
            else -> 1f
        }
        scaleX = sc
        scaleY = sc
        if (!dropTarget) alpha = if (parked && !pickedUp) 0.85f else 1f
    }

    private var level = 0f
    private var pulsePhase = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulsePhase = it.animatedValue as Float
            invalidate()
        }
    }

    /** Speech loudness from the recogniser, roughly -2..10 dB. */
    fun setLevel(rmsDb: Float) {
        level = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val full = minOf(width, height) / 2f
        val r = full * 0.86f

        fill.color = when (mode) {
            Mode.IDLE -> Color.rgb(0x3D, 0x5A, 0xFE)        // blue: clearly not WhatsApp's own button
            Mode.LISTENING -> Color.rgb(0xE5, 0x39, 0x35)   // red: recording
            Mode.READY_TO_SEND -> Color.rgb(0x1E, 0xA9, 0x52) // green: send
        }

        if (mode == Mode.LISTENING) {
            // An expanding ring, pushed out further when you speak louder.
            ring.color = fill.color
            ring.alpha = ((1f - pulsePhase) * 160).toInt()
            ring.strokeWidth = full * 0.08f
            val rr = r + (full - r) * pulsePhase + full * 0.04f * level
            canvas.drawCircle(cx, cy, rr.coerceAtMost(full - ring.strokeWidth / 2), ring)
        }

        fill.setShadowLayer(full * 0.08f, 0f, full * 0.03f, Color.argb(90, 0, 0, 0))
        canvas.drawCircle(cx, cy, r, fill)

        val s = r * 0.9f // icon box half-size
        when (mode) {
            Mode.IDLE -> drawMic(canvas, cx, cy, s)
            Mode.LISTENING -> {
                val h = s * (0.38f + 0.08f * level)
                rect.set(cx - h, cy - h, cx + h, cy + h)
                canvas.drawRoundRect(rect, h * 0.25f, h * 0.25f, icon)
            }
            Mode.READY_TO_SEND -> drawSend(canvas, cx, cy, s)
        }
    }

    private fun drawMic(c: Canvas, cx: Float, cy: Float, s: Float) {
        val w = s * 0.22f
        rect.set(cx - w, cy - s * 0.55f, cx + w, cy + s * 0.12f)
        c.drawRoundRect(rect, w, w, icon)
        iconStroke.strokeWidth = s * 0.09f
        rect.set(cx - s * 0.38f, cy - s * 0.32f, cx + s * 0.38f, cy + s * 0.3f)
        c.drawArc(rect, 20f, 140f, false, iconStroke)
        c.drawLine(cx, cy + s * 0.3f, cx, cy + s * 0.5f, iconStroke)
    }

    private fun drawSend(c: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx - s * 0.42f, cy - s * 0.45f)
        path.lineTo(cx + s * 0.5f, cy)
        path.lineTo(cx - s * 0.42f, cy + s * 0.45f)
        path.lineTo(cx - s * 0.3f, cy + s * 0.05f)
        path.lineTo(cx + s * 0.1f, cy)
        path.lineTo(cx - s * 0.3f, cy - s * 0.05f)
        path.close()
        c.drawPath(path, icon)
    }
}
