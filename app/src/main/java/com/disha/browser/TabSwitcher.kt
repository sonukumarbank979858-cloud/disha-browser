package com.disha.browser

import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged

/**
 * Full-screen Chrome-style tab switcher: grid of tab cards, "टैब खोजें" search,
 * normal / incognito / tab-groups toggle, group cards and a big "+" button.
 */
class TabSwitcher(private val a: MainActivity) {

    companion object {
        const val NORMAL = 0
        const val INCOGNITO = 1
        const val GROUPS = 2
    }

    val view = FrameLayout(a)
    private val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val header = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val searchBox = EditText(a)
    private val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(a)
    private val fab = TextView(a)

    var mode = NORMAL
        private set
    private var openGroup: String? = null
    private var query = ""
    private var pal = Palette(false)

    val isShowing: Boolean get() = view.visibility == View.VISIBLE

    private fun dp(v: Int) = a.dp(v)

    init {
        view.visibility = View.GONE
        view.isClickable = true
        view.isFocusable = true

        header.setPadding(dp(8), dp(8), dp(4), dp(4))
        searchBox.apply {
            hint = "🔍  टैब खोजें"
            isSingleLine = true
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(16), dp(10), dp(16), dp(10))
            doAfterTextChanged { s ->
                query = s?.toString()?.trim() ?: ""
                if (isShowing) render()
            }
        }
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.setPadding(dp(6), dp(4), dp(6), dp(110))

        column.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(searchBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), dp(4), dp(12), dp(8))
        })
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        view.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fab.apply {
            text = "+"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(ContextCompat.getColor(a, R.color.brand), dp(20).toFloat())
            elevation = dp(6).toFloat()
            contentDescription = "नया टैब"
            setOnClickListener { a.newTabFromSwitcher(mode == INCOGNITO, openGroup) }
        }
        view.addView(fab, FrameLayout.LayoutParams(dp(68), dp(68), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(20), dp(20), dp(22), dp(28))
        })
    }

    fun show(m: Int) {
        mode = m
        openGroup = null
        query = ""
        view.visibility = View.VISIBLE
        searchBox.setText("")
        render()
    }

    fun hide() {
        view.visibility = View.GONE
        a.hideKeyboard(searchBox)
        searchBox.clearFocus()
    }

    /** Returns true when the back press was handled here. */
    fun onBack(): Boolean {
        if (!isShowing) return false
        if (openGroup != null) { openGroup = null; render(); return true }
        a.closeSwitcher()
        return true
    }

    fun render() {
        if (!isShowing) return
        if (openGroup != null && a.groups.none { it.id == openGroup }) openGroup = null
        pal = Palette(mode == INCOGNITO || a.isNight())
        view.setBackgroundColor(pal.bg)
        searchBox.background = rounded(pal.card, dp(24).toFloat())
        searchBox.setTextColor(pal.text)
        searchBox.setHintTextColor(pal.muted)
        searchBox.visibility = if (openGroup == null) View.VISIBLE else View.GONE
        buildHeader()
        list.removeAllViews()
        val g = openGroup?.let { id -> a.groups.firstOrNull { it.id == id } }
        when {
            g != null -> renderGroupDetail(g)
            mode == GROUPS -> renderGroups()
            else -> renderGrid()
        }
        fab.visibility = if (mode == GROUPS && g == null) View.GONE else View.VISIBLE
    }

    // ---------------- header ----------------

    private fun label(t: String, size: Float = 16f, bold: Boolean = false, color: Int = pal.text): TextView =
        TextView(a).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun headerIcon(t: String, desc: String, onClick: (View) -> Unit): TextView =
        label(t, 20f).apply {
            gravity = Gravity.CENTER
            minWidth = dp(44)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            contentDescription = desc
            setOnClickListener { onClick(it) }
        }

    private fun buildHeader() {
        header.removeAllViews()
        val g = openGroup?.let { id -> a.groups.firstOrNull { it.id == id } }
        if (g != null) {
            header.addView(headerIcon("←", "पीछे") { openGroup = null; render() })
            header.addView(View(a).apply { background = circle(g.color) }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(8) })
            val members = a.groupMembers(g)
            header.addView(label(g.name.ifBlank { "${members.size} टैब" }, 18f, true).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(label("${members.size} टैब", 14f, color = pal.muted).apply { setPadding(dp(6), 0, dp(4), 0) })
            header.addView(headerIcon("⋮", "ग्रुप मेनू") { v -> groupMenu(v, g) })
            return
        }

        header.addView(headerIcon("←", "टैब पर वापस जाएँ") { a.closeSwitcher() })
        header.addView(Space(a), LinearLayout.LayoutParams(0, 1, 1f))

        val seg = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(pal.card, dp(22).toFloat())
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val normalCount = a.tabs.count { !it.incognito }
        val countBadge = TextView(a).apply {
            text = if (normalCount > 99) ":D" else normalCount.toString()
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(pal.text)
            background = rounded(Color.TRANSPARENT, dp(4).toFloat(), dp(2), pal.text)
        }
        fun segItem(content: View, m: Int, desc: String) {
            val box = FrameLayout(a).apply {
                setPadding(dp(16), dp(6), dp(16), dp(6))
                contentDescription = desc
                if (mode == m) background = rounded(pal.selected, dp(20).toFloat())
                setOnClickListener { mode = m; openGroup = null; render() }
            }
            box.addView(content, (content.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            seg.addView(box)
        }
        segItem(countBadge.apply { layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER) }, NORMAL, "टैब ($normalCount)")
        segItem(label("🕶", 18f), INCOGNITO, "इनकॉग्निटो टैब (${a.tabs.count { it.incognito }})")
        segItem(label("▦", 20f), GROUPS, "टैब ग्रुप (${a.groups.size})")
        header.addView(seg)

        header.addView(Space(a), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(headerIcon("⋮", "मेनू") { v -> switcherMenu(v) })
    }

    private fun switcherMenu(anchor: View) {
        val pm = PopupMenu(a, anchor)
        pm.menu.add(0, 1, 1, "नया टैब")
        pm.menu.add(0, 2, 2, "नया इनकॉग्निटो टैब")
        pm.menu.add(0, 3, 3, if (mode == INCOGNITO) "सभी इनकॉग्निटो टैब बंद करें" else "सभी टैब बंद करें")
        pm.menu.add(0, 4, 4, "सेटिंग्स")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> a.newTabFromSwitcher(false, null)
                2 -> a.newTabFromSwitcher(true, null)
                3 -> a.closeAllTabs(mode == INCOGNITO) { render() }
                4 -> a.showSettings()
            }
            true
        }
        pm.show()
    }

    // ---------------- grid ----------------

    private fun empty(msg: String) {
        list.addView(label(msg, 15f, color = pal.muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(80), dp(24), dp(24))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun matches(t: Tab): Boolean {
        if (query.isEmpty()) return true
        return a.tabTitle(t).contains(query, true) || t.currentUrl().contains(query, true)
    }

    private fun grid(cards: List<View>) {
        cards.chunked(2).forEach { pair ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { c ->
                row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            }
            if (pair.size == 1) row.addView(Space(a), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            list.addView(row)
        }
    }

    private fun renderGrid() {
        val incog = mode == INCOGNITO
        val mine = a.tabs.filter { it.incognito == incog }
        if (query.isNotEmpty()) {
            val found = mine.filter { matches(it) }
            if (found.isEmpty()) { empty("“$query” से मिलता कोई टैब नहीं"); return }
            grid(found.map { tabCard(it) })
            return
        }
        if (mine.isEmpty()) {
            empty(if (incog) "कोई इनकॉग्निटो टैब खुला नहीं है\n\nइनकॉग्निटो में इतिहास और कैश सेव नहीं होते।\nनीचे + दबाकर नया इनकॉग्निटो टैब खोलें।"
                  else "कोई टैब खुला नहीं है\nनीचे + दबाकर नया टैब खोलें।")
            return
        }
        val cards = mutableListOf<View>()
        val shownGroups = mutableSetOf<String>()
        mine.forEach { t ->
            val g = t.groupId?.let { id -> a.groups.firstOrNull { it.id == id } }
            if (g == null) cards.add(tabCard(t))
            else if (shownGroups.add(g.id)) cards.add(groupCard(g))
        }
        grid(cards)
    }

    private fun renderGroupDetail(g: TabGroup) {
        val members = a.groupMembers(g)
        if (members.isEmpty()) { empty("इस ग्रुप में कोई टैब नहीं"); return }
        grid(members.map { tabCard(it) })
    }

    private fun favView(t: Tab, size: Int): View {
        val b = a.faviconOf(t)
        return if (b != null) ImageView(a).apply {
            setImageBitmap(b)
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        } else label("🌐", (size * 0.75f)).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(size + 4), dp(size + 4))
        }
    }

    private fun thumbView(t: Tab, height: Int): View {
        val bmp = a.thumbOf(t)
        val v: View = if (bmp != null) TopCropImageView(a).apply { setImageBitmap(bmp) } else {
            FrameLayout(a).apply {
                val inner = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(favView(t, 28))
                    addView(label(a.hostOf(t), 11f, color = pal.muted).apply {
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(6), dp(6), dp(6), 0)
                    })
                }
                addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }
        }
        v.background = rounded(pal.thumbBg, dp(10).toFloat())
        v.clipToOutline = true
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
        return v
    }

    private fun tabCard(t: Tab): View {
        val isCurrent = t == a.current
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(pal.card, dp(16).toFloat(), if (isCurrent) dp(3) else 0,
                ContextCompat.getColor(a, R.color.brand))
            elevation = dp(2).toFloat()
        }
        val top = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), 0, dp(4))
        }
        top.addView(favView(t, 16))
        top.addView(label(a.tabTitle(t), 13f).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(2), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(label("✕", 15f, color = pal.muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            contentDescription = "टैब बंद करें"
            setOnClickListener { a.closeTab(t); render() }
        })
        card.addView(top)
        card.addView(thumbView(t, 160))
        card.setOnClickListener { a.selectFromSwitcher(t) }
        card.setOnLongClickListener { v -> tabMenu(v, t); true }
        return card
    }

    private fun tabMenu(anchor: View, t: Tab) {
        val pm = PopupMenu(a, anchor)
        if (!t.incognito) pm.menu.add(0, 1, 1, "टैब को ग्रुप में जोड़ें")
        if (t.groupId != null) pm.menu.add(0, 2, 2, "ग्रुप से हटाएँ")
        pm.menu.add(0, 3, 3, "शेयर करें")
        pm.menu.add(0, 4, 4, "टैब बंद करें")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> a.addToGroupDialog(t) { render() }
                2 -> { a.removeFromGroup(t); render() }
                3 -> a.shareUrl(t.currentUrl(), a.tabTitle(t))
                4 -> { a.closeTab(t); render() }
            }
            true
        }
        pm.show()
    }

    private fun groupCard(g: TabGroup): View {
        val members = a.groupMembers(g)
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(pal.card, dp(16).toFloat(), dp(2), g.color)
            elevation = dp(2).toFloat()
        }
        val top = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), 0, dp(4))
        }
        top.addView(View(a).apply { background = circle(g.color) }, LinearLayout.LayoutParams(dp(10), dp(10)))
        top.addView(label(g.name.ifBlank { "${members.size} टैब" } + "  ·  ${members.size}", 13f, true).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(2), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(label("✕", 15f, color = pal.muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            contentDescription = "ग्रुप बंद करें"
            setOnClickListener { a.closeGroup(g) { render() } }
        })
        card.addView(top)

        // 2x2 preview of the group's tabs
        val previewBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0 until 2) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 2) {
                val i = r * 2 + c
                val cell: View = when {
                    i == 3 && members.size > 4 -> label("+${members.size - 3}", 15f, true).apply {
                        gravity = Gravity.CENTER
                        background = rounded(pal.thumbBg, dp(8).toFloat())
                    }
                    i < members.size -> thumbView(members[i], 78)
                    else -> View(a).apply { background = rounded(pal.thumbBg, dp(8).toFloat()); alpha = 0.5f }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, dp(78), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            previewBox.addView(row)
        }
        card.addView(previewBox)
        card.setOnClickListener { openGroup = g.id; render() }
        card.setOnLongClickListener { v -> groupMenu(v, g); true }
        return card
    }

    // ---------------- groups view ----------------

    private fun miniPreview(members: List<Tab>): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.thumbBg, dp(10).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for (r in 0 until 2) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for (c in 0 until 2) {
                val i = r * 2 + c
                val cell: View = when {
                    i == 3 && members.size > 4 -> label("+${members.size - 3}", 10f, true).apply { gravity = Gravity.CENTER }
                    i < members.size -> {
                        val b = a.faviconOf(members[i])
                        if (b != null) ImageView(a).apply { setImageBitmap(b) } else label("🌐", 12f).apply { gravity = Gravity.CENTER }
                    }
                    else -> View(a)
                }
                val cellBox = FrameLayout(a).apply { background = rounded(pal.card, dp(6).toFloat()) }
                cellBox.addView(cell, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                row.addView(cellBox, LinearLayout.LayoutParams(dp(22), dp(22)).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
            }
            box.addView(row)
        }
        return box
    }

    private fun renderGroups() {
        val gs = a.groups.filter { query.isEmpty() || it.name.contains(query, true) }
        if (gs.isEmpty()) {
            empty(if (query.isNotEmpty()) "“$query” नाम का कोई ग्रुप नहीं"
                  else "अभी कोई टैब ग्रुप नहीं है\n\nमेनू ⋮ में “टैब को ग्रुप में जोड़ें” से या किसी टैब कार्ड को देर तक दबाकर ग्रुप बनाएँ।")
            return
        }
        gs.forEach { g ->
            val members = a.groupMembers(g)
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(4), dp(10))
                background = rounded(pal.card, dp(16).toFloat())
                setOnClickListener { mode = NORMAL; openGroup = g.id; render() }
            }
            row.addView(miniPreview(members))
            val texts = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(4), 0)
            }
            val line1 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line1.addView(View(a).apply { background = circle(g.color) }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })
            line1.addView(label(g.name.ifBlank { "बिना नाम का ग्रुप" }, 16f, true).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
            texts.addView(line1)
            texts.addView(label("${members.size} टैब  ·  ${agoHindi(g.updated)}", 13f, color = pal.muted).apply {
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(headerIcon("⋮", "ग्रुप मेनू") { v -> groupMenu(v, g) })
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), dp(5), dp(8), dp(5))
            })
        }
    }

    private fun groupMenu(anchor: View, g: TabGroup) {
        val pm = PopupMenu(a, anchor)
        pm.menu.add(0, 1, 1, "ग्रुप में नया टैब")
        pm.menu.add(0, 2, 2, "नाम बदलें")
        pm.menu.add(0, 3, 3, "रंग बदलें")
        pm.menu.add(0, 4, 4, "ग्रुप हटाएँ (टैब खुले रहेंगे)")
        pm.menu.add(0, 5, 5, "ग्रुप बंद करें")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> a.newTabFromSwitcher(false, g.id)
                2 -> a.renameGroupDialog(g) { render() }
                3 -> a.colorDialog(g) { render() }
                4 -> { a.ungroup(g); render() }
                5 -> a.closeGroup(g) { render() }
            }
            true
        }
        pm.show()
    }
}
