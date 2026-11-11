package com.severeclaw.mp3player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.widget.RemoteViews
import androidx.media3.common.Player

/**
 * Виджет на рабочий стол: тот же скин, что в приложении (флешка, «Полночь» — лёжа, как
 * настоящая флешка), с живым экранчиком. Кнопки на картинке нажимаются там же, где нарисованы.
 */
class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx)

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) = refresh(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: ""
        if (!action.startsWith(ACT)) return super.onReceive(ctx, intent)
        val p = PlaybackService.instance?.player
        if (p == null || p.mediaItemCount == 0) {
            // Плеер не запущен — откроем приложение, оно продолжит с того же места.
            try {
                ctx.startActivity(openIntent(ctx, play = action == ACT_PLAY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
            refresh(ctx)
            return
        }
        when (action) {
            ACT_PLAY -> if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
            ACT_PREV -> p.seekToPrevious()
            ACT_NEXT -> p.seekToNext()
            ACT_SHUFFLE -> {
                p.shuffleModeEnabled = !p.shuffleModeEnabled
                Prefs(ctx).shuffle = p.shuffleModeEnabled
            }
        }
        refresh(ctx)
    }

    companion object {
        private const val ACT = "com.severeclaw.mp3player.widget."
        const val ACT_PLAY = ACT + "play"
        const val ACT_PREV = ACT + "prev"
        const val ACT_NEXT = ACT + "next"
        const val ACT_SHUFFLE = ACT + "shuffle"
        private const val ROWS = 6
        private const val COLS = 24

        fun openIntent(ctx: Context, play: Boolean) = Intent(ctx, MainActivity::class.java)
            .putExtra("widget_play", play)

        /** Перерисовать все виджеты (вызывать можно из любого потока). */
        fun refresh(ctx: Context) {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {   // плеер — только из главного потока
                android.os.Handler(android.os.Looper.getMainLooper()).post { refresh(ctx) }
                return
            }
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = try {
                mgr.getAppWidgetIds(ComponentName(ctx, PlayerWidget::class.java))
            } catch (_: Exception) {
                return
            }
            if (ids.isEmpty()) return
            val st = WidgetData.current(ctx)
            for (id in ids) {
                try {
                    mgr.updateAppWidget(id, build(ctx, mgr, id, st))
                } catch (_: Exception) {
                }
            }
        }

        private fun pending(ctx: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, code, Intent(ctx, PlayerWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun build(ctx: Context, mgr: AppWidgetManager, id: Int, st: WidgetData): RemoteViews {
            val opts = mgr.getAppWidgetOptions(id)
            val dens = ctx.resources.displayMetrics.density
            val portrait = ctx.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val wDp = opts.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250)
            val hDp = opts.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 70)
            var w = (wDp.coerceAtLeast(60) * dens)
            var h = (hDp.coerceAtLeast(30) * dens)
            val k = minOf(1f, 900f / maxOf(w, h))              // не раздувать картинку
            w *= k
            h *= k
            val drawing = WidgetRenderer.render(ctx, st, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))

            val rv = RemoteViews(ctx.packageName, R.layout.widget_player)
            rv.setImageViewBitmap(R.id.w_img, drawing.bitmap)
            val open = PendingIntent.getActivity(
                ctx, 10, openIntent(ctx, play = false),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val intents = mapOf(
                "play" to pending(ctx, ACT_PLAY, 1), "prev" to pending(ctx, ACT_PREV, 2),
                "next" to pending(ctx, ACT_NEXT, 3), "shuffle" to pending(ctx, ACT_SHUFFLE, 4),
            )
            for (r in 0 until ROWS) for (c in 0 until COLS) {
                val x = (c + 0.5f) / COLS * drawing.bitmap.width
                val y = (r + 0.5f) / ROWS * drawing.bitmap.height
                val what = drawing.hit(x, y)
                val cell = ctx.resources.getIdentifier("c${r}_$c", "id", ctx.packageName)
                if (cell != 0) rv.setOnClickPendingIntent(cell, intents[what] ?: open)
            }
            return rv
        }
    }
}

/** Что показать на виджете — из играющего плеера или из сохранённого (если плеер закрыт). */
data class WidgetData(
    val title: String, val artist: String, val ext: String, val playing: Boolean,
    val index: Int, val count: Int, val shuffle: Boolean, val repeatOne: Boolean,
    val posMs: Long, val durMs: Long,
) {
    companion object {
        fun current(ctx: Context): WidgetData {
            val prefs = Prefs(ctx)
            val p = PlaybackService.instance?.player
            if (p != null && p.mediaItemCount > 0) {
                val md = p.mediaMetadata
                val d = WidgetData(
                    title = md.title?.toString() ?: "", artist = md.artist?.toString() ?: "",
                    ext = md.extras?.getString("ext") ?: "", playing = p.isPlaying,
                    index = p.currentMediaItemIndex, count = p.mediaItemCount,
                    shuffle = p.shuffleModeEnabled, repeatOne = p.repeatMode == Player.REPEAT_MODE_ONE,
                    posMs = p.currentPosition.coerceAtLeast(0), durMs = p.duration.coerceAtLeast(0),
                )
                prefs.saveWidget(d)
                return d
            }
            return prefs.loadWidget()
        }
    }
}

/** Картинка виджета и «что под пальцем» в каждой её точке. */
class WidgetDrawing(val bitmap: Bitmap, private val inverse: Matrix, private val skin: Skin) {
    fun hit(x: Float, y: Float): String {
        val pt = floatArrayOf(x, y)
        inverse.mapPoints(pt)
        val (sx, sy) = pt[0] to pt[1]
        // кнопка под пальцем, а если чуть промахнулся — ближайшая (в пределах ~40 px скина)
        var best: String? = null
        var bestD = 40f
        for ((name, r) in skin.buttons) {
            if (name !in setOf("play", "prev", "next", "shuffle")) continue
            val dx = maxOf(r.left - sx, 0f, sx - r.right)
            val dy = maxOf(r.top - sy, 0f, sy - r.bottom)
            val d = kotlin.math.hypot(dx, dy)
            if (d < bestD) {
                bestD = d
                best = name
            }
        }
        return best ?: "open"
    }
}

object WidgetRenderer {
    private var cached: Triple<String, Skin, RectF>? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Скин и рамка того, что на нём нарисовано (без прозрачных краёв), вместе с колпачком. */
    private fun skin(ctx: Context, id: String): Pair<Skin, RectF> {
        cached?.let { if (it.first == id) return it.second to it.third }
        val s = try { Skin.load(ctx, id) } catch (_: Exception) { Skin.load(ctx, "flash") }
        val r = opaqueBounds(s.body)
        s.capRect?.let { r.union(it) }
        cached = Triple(id, s, r)
        return s to r
    }

    private fun opaqueBounds(b: Bitmap): RectF {
        val w = b.width
        val h = b.height
        val row = IntArray(w)
        var top = h
        var bottom = -1
        var left = w
        var right = -1
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if ((row[x] ushr 24) > 16) {
                    if (y < top) top = y
                    bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        return if (bottom < 0) RectF(0f, 0f, w.toFloat(), h.toFloat())
        else RectF(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
    }

    fun render(ctx: Context, st: WidgetData, w: Int, h: Int, skinId: String? = null): WidgetDrawing {
        val prefs = Prefs(ctx)
        val (skin, bb) = skin(ctx, skinId ?: prefs.skin)
        val theme = THEMES.firstOrNull { it.name == prefs.theme && skinId == null } ?: THEMES.firstOrNull { it.name == skin.theme } ?: THEMES[0]

        // Вертикальные скины (флешка, «Полночь») кладём набок — текст на экране становится ровным.
        val m = Matrix()
        m.postTranslate(-bb.left, -bb.top)
        var cw = bb.width()
        var ch = bb.height()
        when (skin.rotation) {
            90 -> { m.postRotate(-90f); m.postTranslate(0f, bb.width()); cw = bb.height(); ch = bb.width() }
            270 -> { m.postRotate(90f); m.postTranslate(bb.height(), 0f); cw = bb.height(); ch = bb.width() }
        }
        val scale = minOf(w / cw, h / ch)
        m.postScale(scale, scale)
        m.postTranslate((w - cw * scale) / 2f, (h - ch * scale) / 2f)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.concat(m)
        c.drawBitmap(skin.body, 0f, 0f, paint)
        if (skin.rotation == 90 || skin.rotation == 270) {
            // круглые кнопки поворачиваем обратно — значки ⏮ ▶ ⏭ стоят ровно, а не набок
            for ((name, r) in skin.buttons) {
                if (name == "menu" || kotlin.math.abs(r.width() - r.height()) > r.width() * 0.1f) continue
                val src = android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                c.save()
                c.rotate(skin.rotation.toFloat(), r.centerX(), r.centerY())
                c.drawBitmap(skin.body, src, r, paint)
                c.restore()
            }
        }
        val tag = (if (st.playing) "> " else "|| ") +
            (if (st.count > 0) "%02d/%02d ".format(st.index + 1, st.count) else "") + st.ext.take(4) +
            (if (st.shuffle) " RND" else "") + (if (st.repeatOne) " RPT1" else "")
        LcdPainter.draw(
            c, skin, theme,
            LcdState(
                playing = st.playing, tag = tag, title = st.title.ifEmpty { "MP3 ПЛЕЕР" }, artist = st.artist,
                posMs = st.posMs, durMs = st.durMs, lyric = null, lyricFill = 0f, flash = null, timeSec = 0.0,
            ),
        )
        skin.cap?.let { cap -> skin.capRect?.let { c.drawBitmap(cap, null, it, paint) } }   // колпачок надет
        val inv = Matrix()
        m.invert(inv)
        return WidgetDrawing(bmp, inv, skin)
    }
}
