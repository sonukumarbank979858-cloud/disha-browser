package com.disha.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Xml
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        try {
            val items = News.fetch()
            if (items.isNotEmpty()) {
                Notify.show(ctx, 100, Notify.CH_NEWS, "आज की ताज़ा ख़बरें", items.map { it.title }, News.PAGE)
            }
        } catch (_: Exception) { }
        try {
            val r = Updates.latest()
            if (r != null && r.first > BuildConfig.VERSION_CODE) {
                Notify.show(ctx, 200, Notify.CH_UPDATE, "दिशा ब्राउज़र का नया वर्ज़न आया है",
                    listOf("v1.0.${r.first} उपलब्ध है — टैप करके डाउनलोड करें"), r.second)
            }
        } catch (_: Exception) { }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context, on: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!on) { wm.cancelUniqueWork("daily"); return }
            val req = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(2, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork("daily", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

object News {
    const val PAGE = "https://news.google.com/home?hl=hi&gl=IN&ceid=IN:hi"
    data class Item(val title: String, val link: String)

    fun fetch(): List<Item> {
        val c = URL("https://news.google.com/rss?hl=hi&gl=IN&ceid=IN:hi").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 DishaBrowser")
        val out = mutableListOf<Item>()
        c.inputStream.use { ins ->
            val p = Xml.newPullParser()
            p.setInput(ins, null)
            var inItem = false
            var title = ""
            var link = ""
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT && out.size < 5) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "item" -> { inItem = true; title = ""; link = "" }
                        "title" -> { if (inItem) title = p.nextText() }
                        "link" -> { if (inItem) link = p.nextText() }
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "item") {
                    inItem = false
                    if (title.isNotBlank()) out.add(Item(title, link))
                }
                ev = p.next()
            }
        }
        return out
    }
}

object Updates {
    const val REPO = "sonukumarbank979858-cloud/disha-browser"

    /** Returns (latest versionCode, release page url) or null. */
    fun latest(): Pair<Int, String>? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "DishaBrowser")
        if (c.responseCode != 200) return null
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val o = JSONObject(body)
        val code = o.optString("tag_name").removePrefix("v").toIntOrNull() ?: return null
        var url = o.optString("html_url")
        val assets = o.optJSONArray("assets")
        if (assets != null && assets.length() > 0) {
            url = assets.getJSONObject(0).optString("browser_download_url", url)
        }
        return Pair(code, url)
    }
}

object Notify {
    const val CH_NEWS = "news"
    const val CH_UPDATE = "update"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_NEWS, "रोज़ाना ख़बरें", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_UPDATE, "ऐप अपडेट", NotificationManager.IMPORTANCE_HIGH))
    }

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, id: Int, channel: String, title: String, lines: List<String>, url: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        channels(ctx)
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra("open_url", url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val style = NotificationCompat.InboxStyle()
        lines.forEach { style.addLine(it) }
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "")
            .setStyle(style)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}
