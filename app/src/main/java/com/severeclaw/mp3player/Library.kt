package com.severeclaw.mp3player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.json.JSONArray
import org.json.JSONObject

val AUDIO_EXT = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "mka", "webm")

/** Трек в папке. dirId — папка, где лежит файл (для поиска .lrc рядом). */
data class Track(val uri: String, val name: String, val dirId: String, val relPath: String) {
    val stem: String get() = name.substringBeforeLast('.')

    /** «01 - Исполнитель - Название» из имени файла (если тегов нет). */
    private val parts: List<String>
        get() {
            val p = stem.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
            return if (p.size > 1 && p[0].all { it.isDigit() || it == '.' }) p.drop(1) else p
        }
    val fileArtist: String get() = if (parts.size > 1) parts[0] else ""
    val fileTitle: String
        get() = if (parts.size > 1) parts.drop(1).joinToString(" - ")
        else stem.replace(Regex("^\\d+[.\\s_-]+"), "").ifEmpty { stem }
}

data class Folder(val uri: String, val name: String)

class ScanResult(val tracks: List<Track>, val lrcs: Map<String, String>) {
    /** .lrc для трека: «Трек.lrc» в той же папке. */
    fun lrcFor(t: Track): String? = lrcs[t.dirId + "/" + t.stem.lowercase()]
}

/** Естественная сортировка: «2 трек» раньше «10 трек». */
private fun naturalKey(s: String): String =
    Regex("\\d+").replace(s.lowercase()) { it.value.padStart(8, '0') }

object Library {
    /** Обойти папку (выбранную через системный проводник) со всеми подпапками. */
    fun scan(ctx: Context, treeUri: Uri): ScanResult {
        if (treeUri.scheme == "file") return scanFiles(java.io.File(treeUri.path ?: "/"))
        val tracks = ArrayList<Track>()
        val lrcs = HashMap<String, String>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val stack = ArrayDeque<Pair<String, String>>()       // (id папки, путь для сортировки)
        stack.add(rootId to "")
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        while (stack.isNotEmpty()) {
            val (dirId, rel) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
            try {
                ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: ""
                        if (mime == Document.MIME_TYPE_DIR) {
                            stack.add(id to "$rel/$name")
                            continue
                        }
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString()
                        if (ext in AUDIO_EXT || mime.startsWith("audio/")) {
                            tracks.add(Track(uri, name, dirId, "$rel/$name"))
                        } else if (ext == "lrc") {
                            lrcs[dirId + "/" + name.substringBeforeLast('.').lowercase()] = uri
                        }
                    }
                }
            } catch (_: Exception) {
                // папку удалили или доступ отозван — пропустить
            }
        }
        tracks.sortBy { naturalKey(it.relPath) }
        return ScanResult(tracks, lrcs)
    }

    /** Обычная папка на диске (собственное хранилище приложения). */
    private fun scanFiles(root: java.io.File): ScanResult {
        val tracks = ArrayList<Track>()
        val lrcs = HashMap<String, String>()
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val ext = f.extension.lowercase()
            val dir = f.parentFile?.absolutePath ?: ""
            val rel = f.absolutePath.removePrefix(root.absolutePath)
            if (ext in AUDIO_EXT) tracks.add(Track(Uri.fromFile(f).toString(), f.name, dir, rel))
            else if (ext == "lrc") lrcs[dir + "/" + f.nameWithoutExtension.lowercase()] = Uri.fromFile(f).toString()
        }
        tracks.sortBy { naturalKey(it.relPath) }
        return ScanResult(tracks, lrcs)
    }

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

    var folders: List<Folder>
        get() {
            val arr = JSONArray(p.getString("folders", "[]"))
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Folder(o.getString("uri"), o.getString("name"))
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
            p.edit().putString("folders", arr.toString()).apply()
        }

    var currentFolder: String?
        get() = p.getString("current_folder", null)
        set(v) = p.edit().putString("current_folder", v).apply()

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
        get() = p.getInt("repeat", 2)          // 0 нет, 1 один трек, 2 вся папка
        set(v) = p.edit().putInt("repeat", v).apply()

    var lyricsOn: Boolean
        get() = p.getBoolean("lyrics", true)
        set(v) = p.edit().putBoolean("lyrics", v).apply()

    /** Где остановился в папке: трек и позиция. */
    fun folderState(folder: String): Pair<String?, Long> {
        val o = JSONObject(p.getString("state:$folder", "{}"))
        return (o.optString("track").ifEmpty { null }) to o.optLong("pos", 0)
    }

    fun saveFolderState(folder: String, track: String, pos: Long) {
        p.edit().putString("state:$folder", JSONObject().put("track", track).put("pos", pos).toString()).apply()
    }
}
