package com.disha.browser

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Chrome-style hide-on-scroll layout for the top toolbar.
 *
 * It holds exactly two children: [content] (the web pages) and [header] (address bar, progress,
 * find bar), drawn on top. [offset] is how far the header has slid up: 0 = fully shown,
 * header height = fully hidden. Both children move with translationY only, so following the
 * finger never re-lays-out the WebView.
 *
 * The content is resized only twice per hide/show cycle, never per frame:
 *  - while the header is fully shown and at rest, the content is (height - header) tall;
 *  - as soon as the header starts moving, or while it is hidden, the content is the full height,
 *    so no blank gap can ever open at the bottom (only a temporary bit of the page bottom is
 *    clipped while the header is half way).
 * [growStamp] tells the activity when the content just grew, so the scroll clamp that a resize
 * can cause near the end of a page is not mistaken for a user scroll (that caused jitter).
 */
class ChromeFrame(ctx: Context) : FrameLayout(ctx) {

    lateinit var header: View
    lateinit var content: View

    var offset = 0f
        private set
    private var settled = true
    private var lastDir = 0
    private var anim: ValueAnimator? = null
    /** While true the header stays fully shown (address bar typing, find in page, menu). */
    var lockShown = false
        set(v) { field = v; if (v) show(false) }
    var growStamp = 0L
        private set

    fun setup(content: View, header: View) {
        this.content = content
        this.header = header
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun headerH(): Int = header.measuredHeight

    private fun contentFull(): Boolean = !(settled && offset <= 0f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        header.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
        val hh = headerH()
        if (offset > hh) offset = hh.toFloat()
        val ch = if (contentFull()) h else max(0, h - hh)
        content.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val hh = headerH()
        header.layout(0, 0, w, hh)
        content.layout(0, hh, w, hh + content.measuredHeight)
        applyTranslation()
    }

    private fun applyTranslation() {
        header.translationY = -offset
        content.translationY = -offset
    }

    private fun setOffset(o: Float, rest: Boolean) {
        val wasFull = contentFull()
        offset = o
        settled = rest
        val full = contentFull()
        if (full != wasFull) {
            if (full) growStamp = SystemClock.uptimeMillis()
            requestLayout()
        }
        applyTranslation()
    }

    /** Page scrolled by [dy] pixels (positive = content moved up / user scrolled down). */
    fun scrollBy(dy: Float) {
        if (dy == 0f) return
        if (lockShown && dy > 0) return
        val hh = headerH().toFloat()
        if (hh <= 0f) return
        anim?.cancel(); anim = null
        lastDir = if (dy > 0) 1 else -1
        val n = min(hh, max(0f, offset + dy))
        if (n != offset || settled) setOffset(n, false)
    }

    /** Finish a half-way header in the direction the user last scrolled. */
    fun snap() {
        if (anim != null) return
        val hh = headerH().toFloat()
        when {
            offset <= 0f -> setOffset(0f, true)
            offset >= hh -> setOffset(hh, true)
            else -> animateTo(if (lastDir > 0 && !lockShown) hh else 0f)
        }
    }

    fun show(animate: Boolean) {
        if (offset <= 0f && settled) return
        if (animate && isLaidOut) animateTo(0f) else { anim?.cancel(); anim = null; setOffset(0f, true) }
    }

    val isFullyShown: Boolean get() = offset <= 0f

    private fun animateTo(target: Float) {
        anim?.cancel()
        if (offset == target) { setOffset(target, true); return }
        anim = ValueAnimator.ofFloat(offset, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { setOffset(it.animatedValue as Float, false) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (anim === a) anim = null
                    if (!cancelled) setOffset(target, true)
                }
            })
            start()
        }
    }
}
