package com.severeclaw.mp3player

import android.content.Context
import android.net.Uri

object Library {
    /** Текст файла (.lrc): UTF-8, а если не похоже — Windows-1251. */
    fun readText(ctx: Context, uri: String): String? = try {
        ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { s ->
            val bytes = s.readBytes()
            val utf = String(bytes, Charsets.UTF_8)
            if ('�' in utf) String(bytes, charset("windows-1251")) else utf.removePrefix("﻿")
        }
    } catch (_: Exception) {
        null
    }
}

/** Настройки и состояние — в SharedPreferences. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("mp3player", Context.MODE_PRIVATE)

    var skin: String
        get() = p.getString("skin", "flash") ?: "flash"
        set(v) = p.edit().putString("skin", v).apply()

    var theme: String
        get() = p.getString("theme", "") ?: ""
        set(v) = p.edit().putString("theme", v).apply()

    var shuffle: Boolean
        get() = p.getBoolean("shuffle", false)
        set(v) = p.edit().putBoolean("shuffle", v).apply()

    var repeat: Int
        get() = p.getInt("repeat", 2)          // 0 нет, 1 один трек, 2 весь список
        set(v) = p.edit().putInt("repeat", v).apply()

    var lyricsOn: Boolean
        get() = p.getBoolean("lyrics", true)
        set(v) = p.edit().putBoolean("lyrics", v).apply()

    var sfxVolume: Float
        get() = p.getFloat("sfx", 0.5f)
        set(v) = p.edit().putFloat("sfx", v).apply()

    var lyricsTree: String?
        get() = p.getString("lyrics_tree", null)
        set(v) = p.edit().putString("lyrics_tree", v).apply()

    var tab: Int
        get() = p.getInt("tab", 0)
        set(v) = p.edit().putInt("tab", v).apply()

    /** Что играло: список (id через запятую), номер и позиция — чтобы продолжить после перезапуска. */
    fun saveQueue(ids: List<Long>, index: Int, pos: Long) {
        p.edit().putString("queue", ids.joinToString(",")).putInt("queue_index", index).putLong("queue_pos", pos).apply()
    }

    fun queue(): Triple<List<Long>, Int, Long> {
        val ids = (p.getString("queue", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
        return Triple(ids, p.getInt("queue_index", 0), p.getLong("queue_pos", 0))
    }
}
