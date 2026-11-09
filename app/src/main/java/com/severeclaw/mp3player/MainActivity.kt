package com.severeclaw.mp3player

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BG = Color(0xFF0B1020)
private val PANEL = Color(0xFF151C33)
private val TEXT = Color(0xFFE6ECEA)
private val DIM = Color(0xFF8A97A8)

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private var future: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    // состояние для экрана
    private var folders by mutableStateOf(listOf<Folder>())
    private var folderUri by mutableStateOf<String?>(null)
    private var scan by mutableStateOf<ScanResult?>(null)
    private var scanning by mutableStateOf(false)
    private var playing by mutableStateOf(false)
    private var currentId by mutableStateOf<String?>(null)
    private var tagTitle by mutableStateOf<String?>(null)
    private var tagArtist by mutableStateOf<String?>(null)
    private var duration by mutableLongStateOf(0L)
    private var shuffle by mutableStateOf(false)
    private var repeat by mutableIntStateOf(2)
    private var lyrics by mutableStateOf<Lyrics?>(null)
    private var flash by mutableStateOf<String?>(null)
    private var flashUntil = 0L
    private var skinId by mutableStateOf("flash")
    private var themeName by mutableStateOf("")
    private var lyricsOn by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        folders = prefs.folders
        folderUri = prefs.currentFolder ?: folders.firstOrNull()?.uri
        skinId = prefs.skin
        themeName = prefs.theme
        shuffle = prefs.shuffle
        repeat = prefs.repeat
        lyricsOn = prefs.lyricsOn
        if (intent?.getBooleanExtra("test_tone", false) == true) {
            // Проверка на эмуляторе: создать тестовый трек с текстом в своей папке и открыть её
            val dir = java.io.File(filesDir, "test-music").apply { mkdirs() }
            writeTestTone(java.io.File(dir, "01 - Тест - Синус.wav"))
            java.io.File(dir, "01 - Тест - Синус.lrc").writeText(
                "[00:00.50]Первая строчка песни\n[00:03.00]Вторая строчка, которая подлиннее остальных\n[00:30.00]Третья\n")
            val uri = Uri.fromFile(dir).toString()
            folders = listOf(Folder(uri, "Тест"))
            prefs.folders = folders
            folderUri = uri
            prefs.currentFolder = uri
        }
        setContent { App() }
    }

    private fun writeTestTone(f: java.io.File) {
        if (f.exists()) return
        val sr = 44100
        val n = sr * 40
        val data = java.nio.ByteBuffer.allocate(44 + n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(2).putInt(sr).putInt(sr * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(n * 4)
        for (i in 0 until n) {
            val v = (6000 * kotlin.math.sin(2 * Math.PI * 440 * i / sr)).toInt().toShort()
            data.putShort(v).putShort(v)
        }
        f.writeBytes(data.array())
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = try { f.get() } catch (_: Exception) { null } ?: return@addListener
            controller = c
            c.addListener(listener)
            syncFromPlayer(c)
            if (c.mediaItemCount == 0) folderUri?.let { openFolder(it, autoplay = false) }
            else folderUri?.let { rescan(it) }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        saveState()
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer(player)
        }
    }

    private fun syncFromPlayer(p: Player) {
        playing = p.isPlaying
        val id = p.currentMediaItem?.mediaId
        if (id != currentId) {
            currentId = id
            loadLyrics()
            saveState()
        }
        // Нет тегов — название из имени файла (для уведомления и экрана блокировки).
        val item = p.currentMediaItem
        if (item != null && p.playbackState == Player.STATE_READY && p.mediaMetadata.title.isNullOrBlank()
            && item.mediaMetadata.title.isNullOrBlank()) {
            scan?.tracks?.firstOrNull { it.uri == item.mediaId }?.let { t ->
                val meta = MediaMetadata.Builder().setTitle(t.fileTitle)
                    .setArtist(t.fileArtist.ifEmpty { null }).build()
                p.replaceMediaItem(p.currentMediaItemIndex, item.buildUpon().setMediaMetadata(meta).build())
            }
        }
        tagTitle = p.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
        tagArtist = (p.mediaMetadata.artist ?: p.mediaMetadata.albumArtist)?.toString()?.takeIf { it.isNotBlank() }
        duration = p.duration.takeIf { it > 0 } ?: 0L
        shuffle = p.shuffleModeEnabled
        repeat = when (p.repeatMode) {
            Player.REPEAT_MODE_ONE -> 1
            Player.REPEAT_MODE_ALL -> 2
            else -> 0
        }
    }

    private fun saveState() {
        val c = controller ?: return
        val f = folderUri ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        prefs.saveFolderState(f, id, c.currentPosition)
    }

    private fun showFlash(text: String, ms: Long = 1800) {
        flash = text
        flashUntil = System.currentTimeMillis() + ms
    }

    // ---------------------------------------------------------------- папки
    private fun addFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val name = DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/')
            .ifEmpty { "Музыка" }
        if (folders.none { it.uri == uri.toString() }) {
            folders = folders + Folder(uri.toString(), name)
            prefs.folders = folders
        }
        openFolder(uri.toString(), autoplay = true)
    }

    private fun removeFolder(f: Folder) {
        folders = folders.filter { it.uri != f.uri }
        prefs.folders = folders
        try {
            contentResolver.releasePersistableUriPermission(Uri.parse(f.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        if (folderUri == f.uri) {
            folderUri = folders.firstOrNull()?.uri
            prefs.currentFolder = folderUri
            folderUri?.let { openFolder(it, false) } ?: run { scan = null; controller?.clearMediaItems() }
        }
    }

    private fun nextFolder() {
        if (folders.size < 2) {
            showFlash("ОДНА ПАПКА")
            return
        }
        val i = folders.indexOfFirst { it.uri == folderUri }
        val f = folders[(i + 1) % folders.size]
        openFolder(f.uri, autoplay = playing)
        showFlash("📁 " + f.name.uppercase())
    }

    private fun rescan(uri: String) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { Library.scan(this@MainActivity, Uri.parse(uri)) }
            if (folderUri == uri) {
                scan = r
                loadLyrics()
            }
        }
    }

    private fun openFolder(uri: String, autoplay: Boolean) {
        saveState()
        folderUri = uri
        prefs.currentFolder = uri
        scanning = true
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { Library.scan(this@MainActivity, Uri.parse(uri)) }
            scanning = false
            if (folderUri != uri) return@launch
            scan = r
            val c = controller ?: return@launch
            if (r.tracks.isEmpty()) {
                c.clearMediaItems()
                showFlash("НЕТ МУЗЫКИ")
                return@launch
            }
            val items = r.tracks.map { MediaItem.Builder().setMediaId(it.uri).setUri(it.uri).build() }
            val (lastTrack, lastPos) = prefs.folderState(uri)
            val idx = r.tracks.indexOfFirst { it.uri == lastTrack }.coerceAtLeast(0)
            c.setMediaItems(items, idx, if (idx >= 0 && lastTrack != null) lastPos else 0L)
            c.shuffleModeEnabled = prefs.shuffle
            c.repeatMode = listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)[prefs.repeat]
            c.prepare()
            if (autoplay) c.play()
        }
    }

    private fun currentTrack(): Track? = scan?.tracks?.firstOrNull { it.uri == currentId }

    private fun loadLyrics() {
        val t = currentTrack()
        val lrc = t?.let { scan?.lrcFor(it) }
        if (lrc == null) {
            lyrics = null
            return
        }
        lifecycleScope.launch {
            val ly = withContext(Dispatchers.IO) { Library.readText(this@MainActivity, lrc)?.let { Lyrics.parse(it) } }
            if (currentTrack() == t) lyrics = ly
        }
    }

    // ---------------------------------------------------------------- кнопки
    private fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            folderUri?.let { openFolder(it, true) }
            return
        }
        if (c.isPlaying) c.pause() else c.play()
    }

    private fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        prefs.shuffle = c.shuffleModeEnabled
        showFlash(if (c.shuffleModeEnabled) "ПЕРЕМЕШИВАНИЕ" else "ПО ПОРЯДКУ")
    }

    private fun cycleRepeat() {
        val c = controller ?: return
        val next = (repeat + 1) % 3
        c.repeatMode = listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)[next]
        prefs.repeat = next
        showFlash(listOf("ПОВТОР ВЫКЛ", "ПОВТОР ТРЕКА", "ПОВТОР ПАПКИ")[next])
    }

    // ---------------------------------------------------------------- экран
    @Composable
    fun App() {
        val skin = remember(skinId) { Skin.load(this, skinId) }
        val theme = THEMES.firstOrNull { it.name == themeName } ?: THEMES.firstOrNull { it.name == skin.theme } ?: THEMES[0]
        var showFolders by remember { mutableStateOf(false) }
        var showSkins by remember { mutableStateOf(false) }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) addFolder(uri)
        }
        val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // позиция трека — каждый кадр (плавные полоса и текст песни)
        var pos by remember { mutableLongStateOf(0L) }
        var clock by remember { mutableStateOf(0.0) }
        LaunchedEffect(Unit) {
            val t0 = System.nanoTime()
            while (true) {
                withFrameNanos { now ->
                    pos = controller?.currentPosition ?: 0L
                    clock = (now - t0) / 1e9
                    if (flash != null && System.currentTimeMillis() > flashUntil) flash = null
                }
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(5000)
                saveState()
            }
        }

        Column(Modifier.fillMaxSize().background(BG).systemBarsPadding()) {
            // верхняя строка
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val fname = folders.firstOrNull { it.uri == folderUri }?.name ?: "Выбери папку"
                Text("📁 $fname", color = TEXT, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { showFolders = true })
                Chip(if (shuffle) "🔀" else "➡") { toggleShuffle() }
                Chip(listOf("⏹", "🔂", "🔁")[repeat]) { cycleRepeat() }
                Chip(if (lyricsOn) "🎤" else "🔇") {
                    lyricsOn = !lyricsOn
                    prefs.lyricsOn = lyricsOn
                    showFlash(if (lyricsOn) "ТЕКСТ ВКЛ" else "ТЕКСТ ВЫКЛ")
                }
                Chip("🎨") { showSkins = true }
            }

            val lcd = lcdState(pos, clock)
            val list: @Composable (Modifier) -> Unit = { m ->
                if (folders.isEmpty()) {
                    Box(m, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                            Text("Выбери папку с музыкой на телефоне", color = TEXT, fontSize = 15.sp)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { pick.launch(null) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(theme.lit), contentColor = Color(theme.ink))) {
                                Text("📁 Выбрать папку", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    TrackList(theme, m)
                }
            }
            if (skin.vertical) {
                Row(Modifier.fillMaxSize()) {
                    SkinView(skin, theme, lcd, Modifier.fillMaxHeight().aspectRatio(skin.w / skin.h, matchHeightConstraintsFirst = true)
                        .padding(4.dp)) { showFolders = true }
                    list(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    SkinView(skin, theme, lcd, Modifier.fillMaxWidth().aspectRatio(skin.w / skin.h).padding(4.dp)) { showFolders = true }
                    list(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }

        if (showFolders) FoldersDialog(onAdd = { showFolders = false; pick.launch(null) }) { showFolders = false }
        if (showSkins) SkinDialog { showSkins = false }
    }

    private fun lcdState(pos: Long, clock: Double): LcdState {
        val t = currentTrack()
        val c = controller
        val n = scan?.tracks?.size ?: 0
        val i = (scan?.tracks?.indexOfFirst { it.uri == currentId } ?: -1) + 1
        val ext = t?.name?.substringAfterLast('.', "")?.uppercase()?.take(4) ?: ""
        val tag = (if (playing) "> " else "|| ") + (if (n > 0) "%02d/%02d ".format(i, n) else "") + ext +
            (if (shuffle) " RND" else "") + (if (repeat == 1) " RPT1" else "")
        var line: String? = null
        var fill = 0f
        val ly = lyrics
        if (lyricsOn && ly != null) {
            val k = ly.indexAt(pos)
            if (k >= 0) {
                line = ly.lines[k].ifBlank { "♪" }
                val start = ly.times[k]
                val end = if (k + 1 < ly.times.size) ly.times[k + 1] else start + 4000
                fill = ((pos - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
            }
        }
        return LcdState(
            playing = playing, tag = tag,
            title = tagTitle ?: t?.fileTitle ?: (if (folders.isEmpty()) "ВЫБЕРИ ПАПКУ" else if (c?.mediaItemCount == 0) "НЕТ МУЗЫКИ" else "…"),
            artist = tagArtist ?: t?.fileArtist ?: "",
            posMs = pos, durMs = duration, lyric = line, lyricFill = fill,
            flash = flash ?: if (scanning) "ИЩУ МУЗЫКУ…" else null, timeSec = clock,
        )
    }

    @Composable
    fun Chip(text: String, onClick: () -> Unit) {
        Box(Modifier.padding(start = 6.dp).background(PANEL, RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(text, fontSize = 16.sp)
        }
    }

    @Composable
    fun SkinView(skin: Skin, theme: LcdTheme, lcd: LcdState, modifier: Modifier, onMenu: () -> Unit) {
        val haptic = LocalHapticFeedback.current
        Canvas(modifier.pointerInput(skin) {
            detectTapGestures(onTap = { o ->
                val s = minOf(size.width / skin.w, size.height / skin.h)
                val ox = (size.width - skin.w * s) / 2
                val oy = (size.height - skin.h * s) / 2
                val x = (o.x - ox) / s
                val y = (o.y - oy) / s
                val hit = skin.buttons.entries.firstOrNull { it.value.contains(x, y) }?.key
                when {
                    hit == "prev" -> controller?.seekToPrevious()
                    hit == "next" -> controller?.seekToNext()
                    hit == "play" -> togglePlay()
                    hit == "shuffle" -> toggleShuffle()
                    hit == "menu" -> onMenu()
                    skin.screen.contains(x, y) -> togglePlay()
                    skin.folderZone?.contains(x, y) == true -> nextFolder()
                    else -> return@detectTapGestures
                }
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }, onLongPress = { onMenu() })
        }) {
            val s = minOf(size.width / skin.w, size.height / skin.h)
            val ox = (size.width - skin.w * s) / 2
            val oy = (size.height - skin.h * s) / 2
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas
                nc.save()
                nc.translate(ox, oy)
                nc.scale(s, s)
                nc.drawBitmap(skin.body, 0f, 0f, null)
                if (skin.cap != null && skin.capRect != null) nc.drawBitmap(skin.cap, null, skin.capRect, null)
                LcdPainter.draw(nc, skin, theme, lcd)
                nc.restore()
            }
        }
    }

    @Composable
    fun TrackList(theme: LcdTheme, modifier: Modifier) {
        var query by remember { mutableStateOf("") }
        val tracks = scan?.tracks ?: emptyList()
        val shown = remember(tracks, query) {
            val q = query.trim().lowercase()
            tracks.withIndex().filter { q.isEmpty() || q in it.value.name.lowercase() }
        }
        val state = rememberLazyListState()
        val cur = currentId
        LaunchedEffect(cur, shown.size) {
            val k = shown.indexOfFirst { it.value.uri == cur }
            if (k >= 0) state.animateScrollToItem((k - 3).coerceAtLeast(0))
        }
        Column(modifier.padding(horizontal = 6.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Поиск · ${tracks.size} треков", color = DIM, fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                textStyle = LocalTextStyle.current.copy(color = TEXT, fontSize = 14.sp),
            )
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(shown, key = { _, it -> it.value.uri }) { _, iv ->
                    val t = iv.value
                    val isCur = t.uri == cur
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (isCur) Color(theme.lit) else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable {
                                val c = controller ?: return@clickable
                                if (c.mediaItemCount != tracks.size) {         // список ещё не загружен в плеер
                                    folderUri?.let { openFolder(it, true) }
                                } else {
                                    c.seekTo(iv.index, 0)
                                    c.play()
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val fg = if (isCur) Color(theme.ink) else TEXT
                        Text("%02d".format(iv.index + 1), color = if (isCur) fg else DIM, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.width(30.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.fileTitle, color = fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal)
                            if (t.fileArtist.isNotEmpty()) {
                                Text(t.fileArtist, color = if (isCur) fg else DIM, fontSize = 11.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (scan?.lrcFor(t) != null) Text("♪", color = if (isCur) fg else DIM, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    @Composable
    fun FoldersDialog(onAdd: () -> Unit, onClose: () -> Unit) {
        AlertDialog(
            onDismissRequest = onClose,
            containerColor = PANEL,
            title = { Text("Мои папки", color = TEXT) },
            text = {
                Column {
                    if (folders.isEmpty()) Text("Папок пока нет", color = DIM)
                    folders.forEach { f ->
                        Row(Modifier.fillMaxWidth().clickable {
                            onClose()
                            if (f.uri != folderUri) openFolder(f.uri, autoplay = true)
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((if (f.uri == folderUri) "▶ " else "    ") + f.name, color = TEXT, fontSize = 15.sp,
                                modifier = Modifier.weight(1f))
                            Text("✕", color = DIM, fontSize = 16.sp, modifier = Modifier.clickable { removeFolder(f) }.padding(8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onAdd) { Text("➕ Добавить папку") } },
            dismissButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
        )
    }

    @Composable
    fun SkinDialog(onClose: () -> Unit) {
        AlertDialog(
            onDismissRequest = onClose,
            containerColor = PANEL,
            title = { Text("Вид плеера", color = TEXT) },
            text = {
                Column {
                    Text("Скин", color = DIM, fontSize = 12.sp)
                    SKINS.forEach { (id, name) ->
                        Text((if (id == skinId) "◆ " else "◇ ") + name, color = TEXT, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clickable {
                                skinId = id
                                prefs.skin = id
                                themeName = ""
                                prefs.theme = ""
                            }.padding(vertical = 7.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Подсветка экрана", color = DIM, fontSize = 12.sp)
                    Row(Modifier.padding(top = 6.dp)) {
                        THEMES.forEach { t ->
                            Box(Modifier.padding(end = 8.dp).size(34.dp).background(Color(t.lit), RoundedCornerShape(8.dp))
                                .clickable { themeName = t.name; prefs.theme = t.name },
                                contentAlignment = Alignment.Center) {
                                if (t.name == themeName) Text("✓", color = Color(t.ink), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("Готово") } },
        )
    }
}
