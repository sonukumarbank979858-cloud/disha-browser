package com.disha.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.concurrent.thread

/** One browser tab. The WebView is created lazily (restored tabs load only when opened). */
class Tab(val id: String, val incognito: Boolean) {
    var web: WebView? = null
    var url: String = ""
    var title: String = ""
    var groupId: String? = null
    var lastUsed: Long = System.currentTimeMillis()
    var desktop = false
    var thumb: Bitmap? = null
    var favicon: Bitmap? = null
    var thumbLoaded = false
    var favLoaded = false

    fun currentUrl(): String = web?.url ?: url
    fun currentTitle(): String = web?.title?.takeIf { it.isNotBlank() } ?: title
}

class TabGroup(val id: String, var name: String, var color: Int, var updated: Long)

object GroupColors {
    val names = arrayOf("स्लेटी", "नीला", "लाल", "पीला", "हरा", "गुलाबी", "बैंगनी", "फ़िरोज़ी", "नारंगी")
    val values = intArrayOf(
        0xFF5F6368.toInt(), 0xFF1A73E8.toInt(), 0xFFD93025.toInt(), 0xFFF9AB00.toInt(), 0xFF1E8E3E.toInt(),
        0xFFD01884.toInt(), 0xFF9334E6.toInt(), 0xFF007B83.toInt(), 0xFFFA903E.toInt()
    )
}

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

/** Saves normal tabs, tab groups, thumbnails and favicons so they survive app restarts. */
class TabStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("disha_tabs", Context.MODE_PRIVATE)
    private val thumbDir = File(ctx.filesDir, "thumbs").apply { mkdirs() }
    private val favDir = File(ctx.filesDir, "favicons").apply { mkdirs() }

    fun hasState(): Boolean = p.contains("tabs")

    fun save(tabs: List<Tab>, groups: List<TabGroup>, currentId: String?) {
        val ta = JSONArray()
        tabs.filter { !it.incognito }.forEach { t ->
            val u = t.currentUrl()
            if (u.isBlank()) return@forEach
            ta.put(
                JSONObject().put("id", t.id).put("u", u).put("t", t.currentTitle())
                    .put("g", t.groupId ?: "").put("lu", t.lastUsed).put("dk", t.desktop)
            )
        }
        val ga = JSONArray()
        groups.forEach { ga.put(JSONObject().put("id", it.id).put("n", it.name).put("c", it.color).put("up", it.updated)) }
        p.edit().putString("tabs", ta.toString()).putString("groups", ga.toString())
            .putString("current", currentId ?: "").apply()
    }

    fun loadGroups(): MutableList<TabGroup> {
        val out = mutableListOf<TabGroup>()
        val arr = try { JSONArray(p.getString("groups", "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(TabGroup(o.optString("id", newId()), o.optString("n"), o.optInt("c", GroupColors.values[1]), o.optLong("up")))
        }
        return out
    }

    fun loadTabs(): MutableList<Tab> {
        val out = mutableListOf<Tab>()
        val arr = try { JSONArray(p.getString("tabs", "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("u")
            if (u.isBlank()) continue
            out.add(Tab(o.optString("id", newId()), false).apply {
                url = u
                title = o.optString("t")
                groupId = o.optString("g").ifBlank { null }
                lastUsed = o.optLong("lu", System.currentTimeMillis())
                desktop = o.optBoolean("dk", false)
            })
        }
        return out
    }

    fun currentId(): String? = p.getString("current", "")?.ifBlank { null }

    fun saveThumb(id: String, bmp: Bitmap) = writeAsync(File(thumbDir, "$id.jpg"), bmp, Bitmap.CompressFormat.JPEG, 70)
    fun saveFavicon(id: String, bmp: Bitmap) = writeAsync(File(favDir, "$id.png"), bmp, Bitmap.CompressFormat.PNG, 100)
    fun loadThumb(id: String): Bitmap? = read(File(thumbDir, "$id.jpg"))
    fun loadFavicon(id: String): Bitmap? = read(File(favDir, "$id.png"))

    fun deleteFiles(id: String) {
        File(thumbDir, "$id.jpg").delete()
        File(favDir, "$id.png").delete()
    }

    /** Removes saved images of tabs that no longer exist. */
    fun cleanup(valid: Set<String>) {
        thread {
            listOf(thumbDir, favDir).forEach { dir ->
                dir.listFiles()?.forEach { f -> if (f.nameWithoutExtension !in valid) f.delete() }
            }
        }
    }

    private fun read(f: File): Bitmap? = if (f.exists()) runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() else null

    private fun writeAsync(f: File, bmp: Bitmap, fmt: Bitmap.CompressFormat, q: Int) {
        thread {
            runCatching { FileOutputStream(f).use { bmp.compress(fmt, q, it) } }
        }
    }
}
