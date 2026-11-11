package com.severeclaw.mp3player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Автообновление из релизов GitHub (SevereClaw/MP3-Player-Android).
 * Номер сборки = versionCode (его вписывает автосборка). Новая сборка ставится через
 * системный установщик; с Android 12, после первого обновления из приложения, —
 * без лишних вопросов.
 */
object Updater {
    private const val API = "https://api.github.com/repos/SevereClaw/MP3-Player-Android/releases"
    private const val ASSET = "MP3Player.apk"

    data class Release(val build: Int, val apkUrl: String, val size: Long, val notes: String)

    fun currentBuild(ctx: Context): Int {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "MP3Player-Android")
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        try {
            if (c.responseCode == 403 || c.responseCode == 429) {
                throw java.io.IOException("GitHub временно ограничил проверки (часто бывает через VPN), попробуй позже")
            }
            if (c.responseCode != 200) throw java.io.IOException("GitHub ответил ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun parse(o: JSONObject): Release? {
        val build = Regex("(\\d+)\\s*$").find(o.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == ASSET) {
                return Release(build, a.getString("browser_download_url"), a.optLong("size"), cleanNotes(o.optString("body")))
            }
        }
        return null
    }

    private const val SITE = "https://github.com/SevereClaw/MP3-Player-Android"

    // api.github.com без входа — 60 запросов в час на IP, а у мобильного оператора или VPN
    // один IP на тысячи людей («rate limit exceeded»). Поэтому номер сборки — из переадресации
    // github.com/…/releases/latest, описания — из ленты releases.atom, файл — по прямой ссылке.
    private fun latestTag(): String? {
        val c = URL("$SITE/releases/latest").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.requestMethod = "HEAD"
        c.setRequestProperty("User-Agent", "MP3Player-Android")
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        try {
            c.responseCode
            val loc = c.getHeaderField("Location") ?: return null
            return Regex("/releases/tag/([^/?#]+)").find(loc)?.groupValues?.get(1)
        } finally {
            c.disconnect()
        }
    }

    private fun htmlText(h: String): String = android.text.Html.fromHtml(
        h.replace(Regex("(?i)<li[^>]*>"), "<br>- "), android.text.Html.FROM_HTML_MODE_LEGACY,
    ).toString()

    /** Релизы из ленты: (номер сборки, текст) — от новых к старым. */
    private fun feed(): List<Pair<Int, String>> {
        val xml = get("$SITE/releases.atom")
        return Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { e ->
            val ent = e.groupValues[1]
            val tag = Regex("/releases/tag/([^\"/?#]+)").find(ent)?.groupValues?.get(1) ?: return@mapNotNull null
            val b = Regex("(\\d+)\\s*$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val content = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL).find(ent)?.groupValues?.get(1) ?: ""
            b to cleanNotes(htmlText(android.text.Html.fromHtml(content, android.text.Html.FROM_HTML_MODE_LEGACY).toString()))
        }.toList()
    }

    fun latest(): Release? {
        val tag = try { latestTag() } catch (_: Exception) { null }
        val b = tag?.let { Regex("(\\d+)\\s*$").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        if (tag != null && b != null) {
            val notes = try { feed().firstOrNull { it.first == b }?.second ?: "" } catch (_: Exception) { "" }
            return Release(b, "$SITE/releases/download/$tag/$ASSET", 0, notes)
        }
        return parse(JSONObject(get("$API/latest")))           // запасной путь
    }

    /** Описания сборок новее after (до upto включительно) — от новой к старой. */
    fun notesSince(after: Int, upto: Int): List<Pair<Int, String>> {
        try {
            val f = feed().filter { it.first in (after + 1)..upto }.sortedByDescending { it.first }
            if (f.isNotEmpty()) return f.take(10)
        } catch (_: Exception) {
        }
        val arr = JSONArray(get("$API?per_page=30"))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val b = Regex("(\\d+)\\s*$").find(o.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull()
            if (b != null && b > after && b <= upto) b to cleanNotes(o.optString("body")) else null
        }.sortedByDescending { it.first }.take(10)
    }

    private fun cleanNotes(body: String): String = body.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("Скачай", true) }
        .joinToString("\n") { if (it.startsWith("- ") || it.startsWith("* ")) "• " + it.drop(2) else it }

    /** Скачать APK (с прогрессом 0..1). */
    fun download(ctx: Context, r: Release, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "MP3Player-${r.build}.apk")
        val c = URL(r.apkUrl).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "MP3Player-Android")
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        var total = 0L
        try {
            total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
            c.inputStream.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        got += n
                        if (total > 0) onProgress((got.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val expect = if (r.size > 0) r.size else total
        if (expect > 0 && out.length() != expect) throw java.io.IOException("скачалось не полностью")
        return out
    }

    /** Отдать APK системному установщику. */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("MP3Player.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            s.commit(pending.intentSender)
        }
    }
}

/** Ответ системного установщика: попросить подтверждение или сообщить об ошибке. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "ошибка установки"
                Toast.makeText(ctx, "Обновление не установилось: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
