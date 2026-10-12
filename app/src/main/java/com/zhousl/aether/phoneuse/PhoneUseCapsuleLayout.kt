package com.zhousl.aether.phoneuse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Continuous corners: curvature eases into the straight edges instead of joining circular arcs. */
internal class PhoneUseCapsuleLayout(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val contour = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.argb(88, 52, 56, 68)
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
        elevation = 6f * density
        outlineProvider = object : ViewOutlineProvider() {
            @Suppress("DEPRECATION")
            override fun getOutline(view: View, outline: Outline) {
                if (!contour.isEmpty) outline.setConvexPath(contour)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = density / 2f
        val left = inset
        val top = inset
        val right = w - inset
        val bottom = h - inset
        val radius = min(16f * density, min(right - left, bottom - top) / 2f).coerceAtLeast(0f)
        contour.rewind()
        // A superellipse (n = 2.5) has zero curvature at its joins with each straight side.
        fun corner(cx: Float, cy: Float, start: Double) {
            for (step in 0..40) {
                val angle = start + step * Math.PI / 80
                val x = cos(angle)
                val y = sin(angle)
                contour.lineTo(cx + (Math.copySign(abs(x).pow(.8), x) * radius).toFloat(),
                    cy + (Math.copySign(abs(y).pow(.8), y) * radius).toFloat())
            }
        }
        contour.moveTo(right - radius, top)
        corner(right - radius, top + radius, -Math.PI / 2)
        corner(right - radius, bottom - radius, 0.0)
        corner(left + radius, bottom - radius, Math.PI / 2)
        corner(left + radius, top + radius, Math.PI)
        contour.close()
        fill.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            Color.argb(176, 255, 255, 255), Color.argb(132, 232, 236, 246), Shader.TileMode.CLAMP)
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(contour, fill)
        canvas.drawPath(contour, rim)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(contour)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}
