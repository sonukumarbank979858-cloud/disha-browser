package com.disha.browser

import android.content.Context
import android.widget.LinearLayout

/**
 * Fixed toolbar layout (v1.1.6+): the header (address bar, progress, find bar) on top and the web
 * pages below it, like a simple browser. Nothing moves or resizes while scrolling or typing.
 *
 * Why: the hide-on-scroll toolbar of v1.1.4/v1.1.5 resized the WebView on every tap (holdFull)
 * and when the soft keyboard opened (adjustResize → page scrolls the focused input into view →
 * that scroll was taken as the user scrolling → toolbar hid → WebView resized again → page
 * scrolled again …). Users saw the page jump, "refresh" and hang while typing in a text box.
 * The only size change of the WebView now is the single one the system makes for the keyboard.
 */
class ChromeFrame(ctx: Context) : LinearLayout(ctx) {

    init { orientation = VERTICAL }

    fun setup(content: android.view.View, header: android.view.View) {
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }
}
