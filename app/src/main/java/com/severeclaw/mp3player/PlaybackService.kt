package com.severeclaw.mp3player

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Музыка играет здесь — в фоне, с уведомлением и кнопками на экране блокировки.
 * Наушники и Bluetooth-кнопки работают сами (MediaSession), при отключении наушников — пауза.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    val player: Player? get() = session?.player
    private val ui = Handler(Looper.getMainLooper())
    private val redraw = Runnable { PlayerWidget.refresh(this) }
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
            if (p.isPlaying && screenOn) PlayerWidget.refresh(this@PlaybackService)   // время и полоска
            ui.postDelayed(this, 10_000)
        }
    }

    /** Виджет перерисовать (несколько событий подряд — одна перерисовка). */
    private fun widgetSoon() {
        ui.removeCallbacks(redraw)
        ui.postDelayed(redraw, 250)
    }

    /** Очередь — в настройки, чтобы виджет и приложение продолжили с того же места. */
    private fun saveQueue(p: Player) {
        if (p.mediaItemCount == 0) return
        val ids = (0 until p.mediaItemCount).mapNotNull {
            try { ContentUris.parseId(Uri.parse(p.getMediaItemAt(it).mediaId)) } catch (_: Exception) { null }
        }
        if (ids.size == p.mediaItemCount) Prefs(this).saveQueue(ids, p.currentMediaItemIndex, p.currentPosition)
    }

    companion object {
        /** Работающий плеер (виджет управляет им напрямую). */
        @Volatile var instance: PlaybackService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)          // выдернул наушники — пауза
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                widgetSoon()
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && !p.isPlaying)) saveQueue(p)
            }
        })
        instance = this
        ui.postDelayed(tick, 10_000)
        widgetSoon()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Смахнул приложение: если музыка не играет — закрыться, если играет — играть дальше.
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        instance = null
        ui.removeCallbacksAndMessages(null)
        session?.player?.let { saveQueue(it) }
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
        PlayerWidget.refresh(this)            // кнопки виджета — снова через приложение
    }
}
