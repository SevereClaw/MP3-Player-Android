package com.severeclaw.mp3player

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size

/** Трек из общей медиатеки Android. */
data class Song(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val fileName: String,
    val folder: String,          // «Music/Persona 3»
    val dateAdded: Long,
) {
    val ext: String get() = fileName.substringAfterLast('.', "").uppercase()
    val stem: String get() = fileName.substringBeforeLast('.')
}

/** Элемент очереди плеера (одинаковый у приложения и у фонового плеера/виджета). */
fun Song.toMediaItem(): androidx.media3.common.MediaItem =
    androidx.media3.common.MediaItem.Builder().setMediaId(uri).setUri(uri).setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist(artist.ifEmpty { null })
            .setAlbumTitle(album.ifEmpty { null })
            .setExtras(android.os.Bundle().apply { putString("ext", ext) }).build(),
    ).build()

private val MUSIC_EXT = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "oga", "opus", "wma", "alac", "aiff", "aif")

object MusicStore {
    /** Вся музыка на телефоне (mp3, flac, wav и другие), без рингтонов и уведомлений. */
    fun load(ctx: Context): List<Song> {
        val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_ADDED,
        )
        val hasRel = Build.VERSION.SDK_INT >= 29
        proj += if (hasRel) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        val sel = "${MediaStore.Audio.Media.IS_RINGTONE}=0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION}=0 AND " +
            "${MediaStore.Audio.Media.IS_ALARM}=0"
        val out = ArrayList<Song>()
        try {
            ctx.contentResolver.query(col, proj.toTypedArray(), sel, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(6) ?: continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in MUSIC_EXT) continue
                    val dur = c.getLong(5)
                    if (dur in 1..9_999) continue                       // короче 10 с — не музыка
                    val id = c.getLong(0)
                    val folder = if (hasRel) (c.getString(8) ?: "").trimEnd('/')
                    else (c.getString(8) ?: "").substringBeforeLast('/').substringAfter("/0/")
                    val stem = name.substringBeforeLast('.')
                    val parts = stem.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
                        .let { if (it.size > 1 && it[0].all { ch -> ch.isDigit() || ch == '.' }) it.drop(1) else it }
                    var title = c.getString(1)?.takeIf { it.isNotBlank() } ?: stem
                    var artist = c.getString(2)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: ""
                    if (artist.isEmpty() && parts.size > 1) {               // тегов нет — из имени файла
                        artist = parts[0]
                        if (title == stem) title = parts.drop(1).joinToString(" - ")
                    }
                    out += Song(
                        id, ContentUris.withAppendedId(col, id).toString(), title, artist,
                        c.getString(3)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "",
                        c.getLong(4), dur, name, folder, c.getLong(7),
                    )
                }
            }
        } catch (_: SecurityException) {
        }
        out.sortWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.fileName.lowercase() }))
        return out
    }

    // ------------------------------------------------------------ обложки
    private val art = LruCache<String, Any>(300)          // Bitmap или NONE
    private val NONE = Any()

    fun cachedArt(song: Song): Bitmap? = art.get(song.uri) as? Bitmap
    fun hasArtCached(song: Song): Boolean = art.get(song.uri) != null

    /** Обложка трека (из самого файла), маленькая — для списков. Вызывать не из главного потока. */
    fun loadArt(ctx: Context, song: Song, size: Int = 192): Bitmap? {
        art.get(song.uri)?.let { return it as? Bitmap }
        var bmp: Bitmap? = null
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                bmp = ctx.contentResolver.loadThumbnail(Uri.parse(song.uri), Size(size, size), null)
            } else {
                MediaMetadataRetriever().apply {
                    setDataSource(ctx, Uri.parse(song.uri))
                    embeddedPicture?.let { b ->
                        val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                        bmp = BitmapFactory.decodeByteArray(b, 0, b.size, o)
                    }
                    release()
                }
            }
        } catch (_: Exception) {
        }
        art.put(song.uri, bmp ?: NONE)
        return bmp
    }
}

/** Тексты песен (.lrc) из папки, к которой дали доступ, и из своей папки приложения. */
class LyricsIndex(private val byName: Map<String, String>) {
    fun find(song: Song): String? {
        val keys = listOf(song.stem, "${song.artist} - ${song.title}", song.title)
        return keys.firstNotNullOfOrNull { byName[it.lowercase().trim()] }
    }

    companion object {
        fun build(ctx: Context, treeUri: String?): LyricsIndex {
            val map = HashMap<String, String>()
            java.io.File(ctx.filesDir, "lyrics").listFiles()?.forEach { f ->
                if (f.extension.equals("lrc", true)) map[f.nameWithoutExtension.lowercase()] = Uri.fromFile(f).toString()
            }
            if (treeUri != null) {
                try {
                    val tree = Uri.parse(treeUri)
                    val stack = ArrayDeque<String>()
                    stack.add(DocumentsContract.getTreeDocumentId(tree))
                    val cols = arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    )
                    while (stack.isNotEmpty()) {
                        val dir = stack.removeLast()
                        ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir),
                            cols, null, null, null)?.use { c ->
                            while (c.moveToNext()) {
                                val id = c.getString(0) ?: continue
                                val name = c.getString(1) ?: continue
                                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) stack.add(id)
                                else if (name.endsWith(".lrc", true)) {
                                    map.putIfAbsent(name.substringBeforeLast('.').lowercase(),
                                        DocumentsContract.buildDocumentUriUsingTree(tree, id).toString())
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            return LyricsIndex(map)
        }
    }
}
