package com.disha.browser

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import kotlin.math.max

class Palette(val dark: Boolean) {
    val bg = if (dark) 0xFF202124.toInt() else 0xFFF1F3F4.toInt()
    val card = if (dark) 0xFF35363A.toInt() else 0xFFFFFFFF.toInt()
    val thumbBg = if (dark) 0xFF2A2B2E.toInt() else 0xFFE8EAED.toInt()
    val text = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
    val muted = if (dark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
    val divider = if (dark) 0x33FFFFFF else 0x22000000
    val selected = if (dark) 0xFF4A4B50.toInt() else 0xFFD3E3FD.toInt()
}

fun rounded(color: Int, radius: Float, strokeW: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeW > 0) setStroke(strokeW, strokeColor)
    }

fun circle(color: Int, strokeW: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokeW > 0) setStroke(strokeW, strokeColor)
    }

/** "X मिनट पहले अपडेट हुआ" style text. */
fun agoHindi(time: Long): String {
    if (time <= 0) return "अपडेट हुआ"
    val s = (System.currentTimeMillis() - time) / 1000
    return when {
        s < 60 -> "अभी अपडेट हुआ"
        s < 3600 -> "${s / 60} मिनट पहले अपडेट हुआ"
        s < 86400 -> "${s / 3600} घंटे पहले अपडेट हुआ"
        s < 86400L * 30 -> "${s / 86400} दिन पहले अपडेट हुआ"
        else -> "${s / (86400L * 30)} महीने पहले अपडेट हुआ"
    }
}

/** Shows the top part of a page screenshot, filling the width (like Chrome's tab cards). */
class TopCropImageView(ctx: Context) : ImageView(ctx) {
    init { scaleType = ScaleType.MATRIX }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        fit()
    }

    private fun fit() {
        val d = drawable ?: return
        val vw = width - paddingLeft - paddingRight
        val vh = height - paddingTop - paddingBottom
        val dw = d.intrinsicWidth
        val dh = d.intrinsicHeight
        if (vw <= 0 || vh <= 0 || dw <= 0 || dh <= 0) return
        val s = max(vw.toFloat() / dw, vh.toFloat() / dh)
        val m = Matrix()
        m.setScale(s, s)
        m.postTranslate((vw - dw * s) / 2f, 0f)
        imageMatrix = m
    }
}
