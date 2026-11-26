package com.disha.browser

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class Entry(val title: String, val url: String, val time: Long)

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("disha", Context.MODE_PRIVATE)

    var engine: String
        get() = p.getString("engine", "google") ?: "google"
        set(v) { p.edit().putString("engine", v).apply() }

    var home: String
        get() = p.getString("home", "disha") ?: "disha"
        set(v) { p.edit().putString("home", v).apply() }

    var javascript: Boolean
        get() = p.getBoolean("js", true)
        set(v) { p.edit().putBoolean("js", v).apply() }

    var adBlock: Boolean
        get() = p.getBoolean("adblock", true)
        set(v) { p.edit().putBoolean("adblock", v).apply() }

    var fakeLoc: Boolean
        get() = p.getBoolean("fake_loc", false)
        set(v) { p.edit().putBoolean("fake_loc", v).apply() }

    var notifications: Boolean
        get() = p.getBoolean("notify", true)
        set(v) { p.edit().putBoolean("notify", v).apply() }

    var night: Int
        get() = p.getInt("night", 0)
        set(v) { p.edit().putInt("night", v).apply() }

    var textZoom: Int
        get() = p.getInt("zoom", 100)
        set(v) { p.edit().putInt("zoom", v).apply() }

    var homeCustom: String
        get() = p.getString("home_custom", "") ?: ""
        set(v) { p.edit().putString("home_custom", v).apply() }

    fun homeUrl(): String = when (home) {
        "google" -> "https://www.google.com/"
        "blank" -> "about:blank"
        "custom" -> homeCustom.ifBlank { HOME }
        else -> HOME
    }

    fun applyNightMode() {
        AppCompatDelegate.setDefaultNightMode(
            when (night) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun list(key: String): MutableList<Entry> {
        val out = mutableListOf<Entry>()
        val arr = try { JSONArray(p.getString(key, "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Entry(o.optString("t"), o.optString("u"), o.optLong("d")))
        }
        return out
    }

    fun saveList(key: String, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url).put("d", it.time)) }
        p.edit().putString(key, arr.toString()).apply()
    }

    fun addEntry(key: String, title: String, url: String, max: Int) {
        val l = list(key)
        l.removeAll { it.url == url }
        l.add(0, Entry(title.ifBlank { url }, url, System.currentTimeMillis()))
        while (l.size > max) l.removeAt(l.size - 1)
        saveList(key, l)
    }

    /** Removes entries saved at or after [cutoff] (0 = everything). */
    fun removeSince(key: String, cutoff: Long) {
        if (cutoff <= 0L) { saveList(key, emptyList()); return }
        saveList(key, list(key).filter { it.time < cutoff })
    }

    fun savedTabs(): List<String> {
        val arr = try { JSONArray(p.getString("tabs", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    fun saveTabs(urls: List<String>) {
        val arr = JSONArray()
        urls.forEach { arr.put(it) }
        p.edit().putString("tabs", arr.toString()).apply()
    }

    companion object {
        const val HOME = "file:///android_asset/home.html"
    }
}

object Engines {
    val list = linkedMapOf(
        "google" to "Google",
        "disha" to "दिशा सर्च",
        "bing" to "Bing",
        "duckduckgo" to "DuckDuckGo",
        "yahoo" to "Yahoo",
        "youtube" to "YouTube"
    )

    fun url(engine: String, q: String): String {
        val e = URLEncoder.encode(q, "UTF-8")
        return when (engine) {
            "disha" -> "file:///android_asset/search.html?q=$e"
            "bing" -> "https://www.bing.com/search?q=$e"
            "duckduckgo" -> "https://duckduckgo.com/?q=$e"
            "yahoo" -> "https://search.yahoo.com/search?p=$e"
            "youtube" -> "https://www.youtube.com/results?search_query=$e"
            else -> "https://www.google.com/search?q=$e"
        }
    }
}

object AdBlock {
    private val hosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "adnxs.com", "taboola.com", "outbrain.com", "popads.net", "propellerads.com", "adsterra.com",
        "zedo.com", "criteo.com", "criteo.net", "amazon-adsystem.com", "moatads.com", "scorecardresearch.com",
        "ads.yahoo.com", "adcolony.com", "unityads.unity3d.com", "applovin.com", "inmobi.com", "mgid.com",
        "revcontent.com", "popcash.net", "exoclick.com", "juicyads.com", "onclickads.net", "adform.net"
    )

    fun isAd(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return hosts.any { h == it || h.endsWith(".$it") }
    }
}
