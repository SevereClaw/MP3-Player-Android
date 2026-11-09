package com.severeclaw.mp3player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONObject

/** Цвета экранчика — как в ПК-версии: фон, фон с подсветкой, «чернила». */
data class LcdTheme(val name: String, val bg: Int, val lit: Int, val ink: Int)

val THEMES = listOf(
    LcdTheme("ЗЕЛЁНАЯ", Color.rgb(150, 171, 164), Color.rgb(176, 222, 196), Color.rgb(26, 36, 33)),
    LcdTheme("ГОЛУБАЯ", Color.rgb(136, 160, 176), Color.rgb(150, 206, 242), Color.rgb(14, 26, 46)),
    LcdTheme("ОРАНЖЕВАЯ", Color.rgb(178, 160, 128), Color.rgb(255, 184, 92), Color.rgb(42, 22, 6)),
    LcdTheme("КРАСНАЯ", Color.rgb(172, 142, 142), Color.rgb(255, 128, 116), Color.rgb(44, 10, 10)),
    LcdTheme("БЕЛАЯ", Color.rgb(166, 170, 168), Color.rgb(236, 241, 239), Color.rgb(20, 22, 24)),
)

val SKINS = listOf("flash" to "Флешка", "pocket" to "Карманный", "midnight" to "Полночь", "cassette" to "Кассетный")

class Skin(val id: String, val name: String, val body: Bitmap, val cap: Bitmap?, val capRect: RectF?,
           val screen: RectF, val radius: Float, val rotation: Int,
           val buttons: Map<String, RectF>, val folderZone: RectF?, val theme: String) {
    val w get() = body.width.toFloat()
    val h get() = body.height.toFloat()
    val vertical get() = body.height > body.width

    companion object {
        private fun rect(a: org.json.JSONArray?): RectF? {
            if (a == null || a.length() < 4) return null
            val x = a.getDouble(0).toFloat()
            val y = a.getDouble(1).toFloat()
            return RectF(x, y, x + a.getDouble(2).toFloat(), y + a.getDouble(3).toFloat())
        }

        fun load(ctx: Context, id: String): Skin {
            val dir = "skins/$id"
            val js = JSONObject(ctx.assets.open("$dir/skin.json").bufferedReader().readText())
            val body = ctx.assets.open("$dir/${js.optString("body", "body.png")}").use { BitmapFactory.decodeStream(it) }
            var cap: Bitmap? = null
            var capRect: RectF? = null
            js.optJSONObject("cap")?.let { c ->
                try {
                    cap = ctx.assets.open("$dir/${c.getString("image")}").use { BitmapFactory.decodeStream(it) }
                    val cw = cap!!.width.toFloat()
                    val ch = cap!!.height.toFloat()
                    val px = c.optDouble("pivot_x", (body.width / 2).toDouble()).toFloat()
                    val bottom = c.optDouble("bottom", ch.toDouble()).toFloat()
                    capRect = RectF(px - cw / 2, bottom - ch, px + cw / 2, bottom)
                } catch (_: Exception) {
                    cap = null
                }
            }
            val btn = HashMap<String, RectF>()
            js.optJSONObject("buttons")?.let { b -> b.keys().forEach { k -> rect(b.optJSONArray(k))?.let { btn[k] = it } } }
            return Skin(
                id, js.optString("name", id), body, cap, capRect,
                rect(js.getJSONArray("screen"))!!, js.optDouble("screen_radius", 6.0).toFloat(),
                js.optInt("text_rotation", 0), btn, rect(js.optJSONArray("folder_zone")),
                js.optString("theme", "ЗЕЛЁНАЯ"),
            )
        }
    }
}

/** Что показать на экранчике. */
data class LcdState(
    val playing: Boolean,
    val tag: String,             // «▶ 03/28 FLAC RND»
    val title: String,
    val artist: String,
    val posMs: Long,
    val durMs: Long,
    val lyric: String?,          // текущая строчка (или null — показывать исполнителя)
    val lyricFill: Float,        // сколько строчки «пропето» 0..1
    val flash: String?,          // короткая надпись поверх (например «ПЕРЕМЕШИВАНИЕ»)
    val timeSec: Double,         // для бегущей строки
)

object LcdPainter {
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    /** Рисует экранчик в координатах картинки скина (canvas уже масштабирован под неё). */
    fun draw(c: Canvas, skin: Skin, theme: LcdTheme, st: LcdState) {
        val r = skin.screen
        paint.style = Paint.Style.FILL
        paint.color = if (st.playing) theme.lit else theme.bg
        c.drawRoundRect(r, skin.radius, skin.radius, paint)

        c.save()
        // Флешка и «Полночь» — экран вертикальный, текст повёрнут, как на ПК.
        val boxW: Float
        val boxH: Float
        if (skin.rotation == 90 || skin.rotation == 270) {
            c.translate(r.centerX(), r.centerY())
            c.rotate(skin.rotation.toFloat())
            boxW = r.height()
            boxH = r.width()
            c.translate(-boxW / 2, -boxH / 2)
        } else {
            c.translate(r.left, r.top)
            boxW = r.width()
            boxH = r.height()
        }
        c.clipRect(0f, 0f, boxW, boxH)
        val pad = boxH * 0.08f
        val ink = theme.ink
        paint.color = ink

        // 1. строка состояния
        paint.textSize = boxH * 0.13f
        c.drawText(st.tag, pad, pad + paint.textSize * 0.9f, paint)
        val time = fmt(st.posMs)
        c.drawText(time, boxW - pad - paint.measureText(time), pad + paint.textSize * 0.9f, paint)

        // 2. название — бегущей строкой, если не влезает
        paint.textSize = boxH * 0.24f
        val titleY = boxH * 0.50f
        val text = st.flash ?: st.title
        val tw = paint.measureText(text)
        val avail = boxW - pad * 2
        if (tw <= avail || st.flash != null) {
            c.drawText(text, pad, titleY, paint)
        } else {
            val gap = paint.textSize * 3
            val period = (tw + gap) / 40f                     // 40 px/с
            val shift = ((st.timeSec % period) * 40f).toFloat()
            c.save()
            c.clipRect(pad, 0f, boxW - pad, boxH)
            c.drawText(text, pad - shift, titleY, paint)
            c.drawText(text, pad - shift + tw + gap, titleY, paint)
            c.restore()
        }

        // 3. исполнитель или строчка текста песни (заливается по ходу)
        paint.textSize = boxH * 0.16f
        val y3 = boxH * 0.72f
        val line = st.lyric
        if (line != null) {
            val lw = paint.measureText(line)
            val off = if (lw > avail) ((lw - avail) * st.lyricFill.coerceIn(0f, 1f)) else 0f
            c.save()
            c.clipRect(pad, 0f, boxW - pad, boxH)
            paint.alpha = 110
            c.drawText(line, pad - off, y3, paint)
            paint.alpha = 255
            c.save()
            c.clipRect(pad - off, 0f, pad - off + lw * st.lyricFill.coerceIn(0f, 1f), boxH)
            c.drawText(line, pad - off, y3, paint)
            c.restore()
            c.restore()
        } else {
            c.drawText(st.artist, pad, y3, paint)
        }

        // 4. полоса
        val by = boxH * 0.84f
        val bh = boxH * 0.06f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = bh * 0.25f
        c.drawRect(pad, by, boxW - pad, by + bh, paint)
        paint.style = Paint.Style.FILL
        if (st.durMs > 0) {
            val f = (st.posMs.toFloat() / st.durMs).coerceIn(0f, 1f)
            c.drawRect(pad, by, pad + (boxW - pad * 2) * f, by + bh, paint)
        }
        c.restore()
    }
}
