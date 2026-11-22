package com.disha.browser

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.max

/**
 * Chrome-style hide-on-scroll layout for the top toolbar (v1.1.5 rewrite).
 *
 * Two children: [content] (the web pages) and [header] (address bar, progress, find bar) on top.
 * The header is either shown or hidden; a scroll never drags it half way any more. Moving between
 * the two is a short animation of translationY only, so the WebView is never re-laid-out per frame.
 *
 * Why (v1.1.4 bug, "scroll up par hang / page khud uchhal raha hai"): the old version made the
 * header and the WebView follow every scroll pixel. Moving the WebView under the finger changes the
 * finger's position inside it, which changes the next scroll delta, which moves the WebView again
 * (a feedback loop = bouncing), and every start/stop of that movement resized the WebView.
 *
 * The content is resized at most twice per hide/show cycle and never during a touch:
 *  - header shown, at rest and the page idle ([holdFull] false): content is (height - header) tall;
 *  - otherwise: content is the full height (only the bottom strip is temporarily below the screen).
 * [layoutStamp] tells the activity when the content was just resized, so scroll changes the page
 * makes in reaction to that (reflow, scroll clamp) are never taken as the user scrolling.
 */
class ChromeFrame(ctx: Context) : FrameLayout(ctx) {

    lateinit var header: View
    lateinit var content: View

    var offset = 0f
        private set
    private var settled = true
    private var anim: ValueAnimator? = null
    private var hiddenTarget = false

    /** Hide-on-scroll switched on (Settings). When off the toolbar is always shown. */
    var enabledHide = true
        set(v) { field = v; if (!v) show(false) }

    /** While true the header stays fully shown (address bar typing, find in page, menu). */
    var lockShown = false
        set(v) { field = v; if (v) show(false) }

    /** While true (finger on the page / fling) the content keeps its size; resized when released. */
    var holdFull = false
        set(v) {
            if (field == v) return
            val was = contentFull()
            field = v
            if (contentFull() != was) resized()
        }

    var layoutStamp = 0L
        private set

    fun setup(content: View, header: View) {
        this.content = content
        this.header = header
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun headerH(): Int = header.measuredHeight

    private fun contentFull(): Boolean = !(settled && offset <= 0f && !holdFull)

    val isContentFull: Boolean get() = contentFull()
    val isFullyShown: Boolean get() = offset <= 0f && !hiddenTarget
    val isHidden: Boolean get() = hiddenTarget
    val isAnimating: Boolean get() = anim != null
    fun headerHeight(): Int = headerH()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        header.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
        val hh = headerH()
        if (offset > hh) offset = hh.toFloat()
        if (hiddenTarget && settled && anim == null) offset = hh.toFloat()
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

    private fun resized() {
        layoutStamp = SystemClock.uptimeMillis()
        requestLayout()
    }

    private fun setOffset(o: Float, rest: Boolean) {
        val wasFull = contentFull()
        offset = o
        settled = rest
        if (contentFull() != wasFull) resized()
        applyTranslation()
    }

    fun hide() {
        if (!enabledHide || lockShown || hiddenTarget) return
        val hh = headerH().toFloat()
        if (hh <= 0f || !isLaidOut) return
        hiddenTarget = true
        animateTo(hh)
    }

    fun show(animate: Boolean) {
        val wasHidden = hiddenTarget
        hiddenTarget = false
        if (offset <= 0f && settled && anim == null) return
        if (!wasHidden && anim != null) return          // already sliding back in
        if (animate && isLaidOut && wasHidden) animateTo(0f)
        else { anim?.cancel(); anim = null; setOffset(0f, true) }
    }

    private fun animateTo(target: Float) {
        anim?.cancel(); anim = null
        if (offset == target) { setOffset(target, true); return }
        // grow the content once, before the movement starts (never during it)
        setOffset(offset, false)
        anim = ValueAnimator.ofFloat(offset, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                offset = it.animatedValue as Float
                applyTranslation()
            }
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
