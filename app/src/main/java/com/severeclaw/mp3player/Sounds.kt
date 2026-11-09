package com.severeclaw.mp3player

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** Звуки кнопок и включения — те же, что у плеера на ПК. */
class Sounds(ctx: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        ).build()
    private val beep = pool.load(ctx, R.raw.beep, 1)
    private val boot = pool.load(ctx, R.raw.boot, 1)
    private var bootPending = false
    var volume = 0.5f

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status == 0 && id == boot && bootPending) {
                bootPending = false
                play(boot)
            }
        }
    }

    private fun play(id: Int) {
        if (volume > 0f) pool.play(id, volume, volume, 1, 0, 1f)
    }

    fun click() = play(beep)

    fun powerOn() {
        if (volume <= 0f) return
        // при запуске звук мог ещё не загрузиться — сыграть, как только загрузится
        if (pool.play(boot, volume, volume, 1, 0, 1f) == 0) bootPending = true
    }

    fun release() = pool.release()

    companion object {
        val LEVELS = listOf("Выкл" to 0f, "Тихо" to 0.25f, "Средне" to 0.5f, "Громко" to 0.85f)
    }
}
