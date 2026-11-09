package com.severeclaw.mp3player

import android.Manifest
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
private val PANEL2 = Color(0xFF1E2744)
private val TEXT = Color(0xFFE6ECEA)
private val DIM = Color(0xFF8A97A8)

private val TABS = listOf("Плеер" to "▶", "Треки" to "♪", "Альбомы" to "◉", "Артисты" to "♫", "Папки" to "▤")

/** Открытый альбом / исполнитель / папка. */
data class Detail(val kind: Int, val key: String, val title: String)

private fun fmtDur(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var sounds: Sounds
    private var future: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    private var hasPerm by mutableStateOf(false)
    private var songs by mutableStateOf(listOf<Song>())
    private var byUri by mutableStateOf(mapOf<String, Song>())
    private var loading by mutableStateOf(false)
    private var lyricsIndex by mutableStateOf(LyricsIndex(emptyMap()))

    private var playing by mutableStateOf(false)
    private var currentId by mutableStateOf<String?>(null)
    private var queueIndex by mutableIntStateOf(0)
    private var queueSize by mutableIntStateOf(0)
    private var duration by mutableLongStateOf(0L)
    private var shuffle by mutableStateOf(false)
    private var repeat by mutableIntStateOf(2)
    private var lyrics by mutableStateOf<Lyrics?>(null)
    private var flash by mutableStateOf<String?>(null)
    private var flashUntil = 0L

    private var skinId by mutableStateOf("flash")
    private var themeName by mutableStateOf("")
    private var lyricsOn by mutableStateOf(true)
    private var sfx by mutableFloatStateOf(0.5f)
    private var tab by mutableIntStateOf(0)
    private var detail by mutableStateOf<Detail?>(null)
    private var testAutoplay = false

    private val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        sounds = Sounds(this)
        skinId = prefs.skin
        themeName = prefs.theme
        shuffle = prefs.shuffle
        repeat = prefs.repeat
        lyricsOn = prefs.lyricsOn
        sfx = prefs.sfxVolume
        sounds.volume = sfx
        tab = prefs.tab
        hasPerm = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (intent?.getBooleanExtra("test_tone", false) == true) {
            testAutoplay = true
            lifecycleScope.launch(Dispatchers.IO) { makeTestTrack() }
        }
        if (savedInstanceState == null) sounds.powerOn()         // звук включения, как на ПК
        setContent { App() }
    }

    override fun onStart() {
        super.onStart()
        if (hasPerm) reload()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = try { f.get() } catch (_: Exception) { null } ?: return@addListener
            controller = c
            c.addListener(listener)
            sync(c)
            restoreQueueIfEmpty()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        saveQueue()
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        sounds.release()
        super.onDestroy()
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    private fun sync(p: Player) {
        playing = p.isPlaying
        val id = p.currentMediaItem?.mediaId
        if (id != currentId) {
            currentId = id
            loadLyrics()
            saveQueue()
        }
        queueIndex = p.currentMediaItemIndex
        queueSize = p.mediaItemCount
        duration = p.duration.takeIf { it > 0 } ?: (byUri[id]?.durationMs ?: 0L)
        shuffle = p.shuffleModeEnabled
        repeat = when (p.repeatMode) {
            Player.REPEAT_MODE_ONE -> 1
            Player.REPEAT_MODE_ALL -> 2
            else -> 0
        }
    }

    // ---------------------------------------------------------------- музыка
    private fun reload() {
        if (loading) return
        loading = true
        lifecycleScope.launch {
            val (list, idx) = withContext(Dispatchers.IO) {
                MusicStore.load(this@MainActivity) to LyricsIndex.build(this@MainActivity, prefs.lyricsTree)
            }
            songs = list
            byUri = list.associateBy { it.uri }
            lyricsIndex = idx
            loading = false
            loadLyrics()
            restoreQueueIfEmpty()
            if (testAutoplay && list.isNotEmpty()) {
                testAutoplay = false
                play(list, 0)
            }
        }
    }

    private fun restoreQueueIfEmpty() {
        val c = controller ?: return
        if (c.mediaItemCount > 0 || songs.isEmpty()) return
        val (ids, idx, pos) = prefs.queue()
        val byId = songs.associateBy { it.id }
        val list = ids.mapNotNull { byId[it] }.ifEmpty { songs }
        val i = if (list.size == ids.size) idx.coerceIn(0, list.size - 1) else 0
        setQueue(list, i, if (list.size == ids.size) pos else 0L, autoplay = false)
    }

    private fun item(s: Song) = MediaItem.Builder().setMediaId(s.uri).setUri(s.uri).setMediaMetadata(
        MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist.ifEmpty { null })
            .setAlbumTitle(s.album.ifEmpty { null }).build(),
    ).build()

    private fun setQueue(list: List<Song>, index: Int, pos: Long, autoplay: Boolean) {
        val c = controller ?: return
        c.setMediaItems(list.map { item(it) }, index, pos)
        c.shuffleModeEnabled = prefs.shuffle
        c.repeatMode = listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)[prefs.repeat]
        c.prepare()
        if (autoplay) c.play()
        prefs.saveQueue(list.map { it.id }, index, pos)
    }

    /** Играть список с трека index (из любой вкладки). */
    private fun play(list: List<Song>, index: Int) {
        val c = controller ?: return
        val same = c.mediaItemCount == list.size &&
            (0 until c.mediaItemCount).all { c.getMediaItemAt(it).mediaId == list[it].uri }
        if (same) {
            c.seekTo(index, 0)
            c.play()
        } else {
            setQueue(list, index, 0, autoplay = true)
        }
    }

    private fun saveQueue() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        val ids = (0 until c.mediaItemCount).mapNotNull { byUri[c.getMediaItemAt(it).mediaId]?.id }
        if (ids.size == c.mediaItemCount) prefs.saveQueue(ids, c.currentMediaItemIndex, c.currentPosition)
    }

    private fun current(): Song? = currentId?.let { byUri[it] }

    private fun loadLyrics() {
        val s = current()
        val lrc = s?.let { lyricsIndex.find(it) }
        if (lrc == null) {
            lyrics = null
            return
        }
        lifecycleScope.launch {
            val ly = withContext(Dispatchers.IO) { Library.readText(this@MainActivity, lrc)?.let { Lyrics.parse(it) } }
            if (current() == s) lyrics = ly
        }
    }

    private fun showFlash(text: String, ms: Long = 1800) {
        flash = text
        flashUntil = System.currentTimeMillis() + ms
    }

    // ---------------------------------------------------------------- кнопки
    private fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            if (songs.isNotEmpty()) play(songs, 0)
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
        showFlash(listOf("ПОВТОР ВЫКЛ", "ПОВТОР ТРЕКА", "ПОВТОР СПИСКА")[next])
    }

    private fun toggleLyrics() {
        lyricsOn = !lyricsOn
        prefs.lyricsOn = lyricsOn
        showFlash(if (lyricsOn) "ТЕКСТ ВКЛ" else "ТЕКСТ ВЫКЛ")
    }

    /** Нажатие кнопки плеера: звук + действие. */
    private fun press(action: () -> Unit) {
        sounds.click()
        action()
    }

    // ---------------------------------------------------------------- экран
    @Composable
    fun App() {
        val skin = remember(skinId) { Skin.load(this, skinId) }
        val theme = THEMES.firstOrNull { it.name == themeName } ?: THEMES.firstOrNull { it.name == skin.theme } ?: THEMES[0]
        val lit = Color(theme.lit)
        var showSettings by remember { mutableStateOf(false) }
        val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            hasPerm = ok
            if (ok) reload()
        }
        val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val pickLyrics = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
                prefs.lyricsTree = uri.toString()
                lifecycleScope.launch {
                    lyricsIndex = withContext(Dispatchers.IO) { LyricsIndex.build(this@MainActivity, uri.toString()) }
                    loadLyrics()
                }
            }
        }
        LaunchedEffect(Unit) {
            if (!hasPerm) askPerm.launch(permission)
            if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
                saveQueue()
            }
        }
        BackHandler(enabled = detail != null || tab != 0) {
            if (detail != null) detail = null else goTab(0)
        }

        Column(Modifier.fillMaxSize().background(BG).systemBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !hasPerm -> NoPermission { askPerm.launch(permission) }
                    tab == 0 -> PlayerScreen(skin, theme, pos, clock) { showSettings = true }
                    detail != null -> DetailScreen(detail!!, lit)
                    tab == 1 -> SongsScreen("Все треки", songs, lit)
                    tab == 2 -> AlbumsScreen()
                    tab == 3 -> GroupScreen(1, songs.groupBy { it.artist.ifEmpty { "Неизвестный исполнитель" } })
                    else -> GroupScreen(3, songs.groupBy { it.folder.ifEmpty { "Память телефона" } })
                }
            }
            if (tab != 0 && current() != null) MiniPlayer(pos, lit)
            NavigationBar(containerColor = PANEL, tonalElevation = 0.dp) {
                TABS.forEachIndexed { i, (name, glyph) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { goTab(i); detail = null },
                        icon = { Text(glyph, fontSize = 18.sp, color = if (tab == i) Color(theme.ink) else TEXT) },
                        label = { Text(name, fontSize = 10.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = lit, selectedTextColor = TEXT, unselectedTextColor = DIM,
                        ),
                    )
                }
            }
        }
        if (showSettings) SettingsDialog(theme, onLyricsFolder = { pickLyrics.launch(null) }) { showSettings = false }
    }

    private fun goTab(i: Int) {
        tab = i
        prefs.tab = i
    }

    @Composable
    fun NoPermission(onAsk: () -> Unit) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Нужен доступ к музыке", color = TEXT, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Плеер сам найдёт все mp3, flac, wav и другие треки на телефоне.", color = DIM, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAsk) { Text("Разрешить") }
            TextButton(onClick = {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }) { Text("Открыть настройки приложения", color = DIM) }
        }
    }

    // ---------------------------------------------------------------- вкладка «Плеер»
    private fun lcdState(pos: Long, clock: Double): LcdState {
        val s = current()
        val tag = (if (playing) "> " else "|| ") +
            (if (queueSize > 0) "%02d/%02d ".format(queueIndex + 1, queueSize) else "") + (s?.ext?.take(4) ?: "") +
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
            title = s?.title ?: if (loading) "ИЩУ МУЗЫКУ…" else if (songs.isEmpty()) "НЕТ МУЗЫКИ" else "ВЫБЕРИ ТРЕК",
            artist = s?.artist ?: "", posMs = pos, durMs = duration, lyric = line, lyricFill = fill,
            flash = flash, timeSec = clock,
        )
    }

    @Composable
    fun PlayerScreen(skin: Skin, theme: LcdTheme, pos: Long, clock: Double, onSettings: () -> Unit) {
        val lit = Color(theme.lit)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val s = current()
                Column(Modifier.weight(1f)) {
                    Text(s?.title ?: "MP3 Плеер", color = TEXT, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(s?.artist?.ifEmpty { null }, s?.album?.ifEmpty { null }).joinToString(" · ")
                        .ifEmpty { "${songs.size} треков" }, color = DIM, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Pill("⚙", false, lit, onSettings)
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                SkinView(skin, theme, lcdState(pos, clock), Modifier.fillMaxSize())
            }
            SeekBar(pos, lit)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                Pill("RND", shuffle, lit) { press { toggleShuffle() } }
                CtrlButton(52.dp, PANEL2, { press { controller?.seekToPrevious() } }) { DrawIcon("prev", TEXT) }
                CtrlButton(72.dp, lit, { press { togglePlay() } }) { DrawIcon(if (playing) "pause" else "play", Color(theme.ink)) }
                CtrlButton(52.dp, PANEL2, { press { controller?.seekToNext() } }) { DrawIcon("next", TEXT) }
                Pill(listOf("RPT·", "RPT1", "RPT")[repeat], repeat != 0, lit) { press { cycleRepeat() } }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.Center) {
                Pill(if (lyrics != null) "LRC ✓" else "LRC", lyricsOn, lit) { toggleLyrics() }
            }
        }
    }

    @Composable
    fun SeekBar(pos: Long, lit: Color) {
        var drag by remember { mutableStateOf<Float?>(null) }
        val f = if (duration > 0) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Column {
            Slider(
                value = drag ?: f,
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    drag?.let { controller?.seekTo((it * duration).toLong()) }
                    drag = null
                },
                colors = SliderDefaults.colors(thumbColor = lit, activeTrackColor = lit, inactiveTrackColor = PANEL2),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtDur(drag?.let { (it * duration).toLong() } ?: pos), color = DIM, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text(fmtDur(duration), color = DIM, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }

    @Composable
    fun Pill(text: String, on: Boolean, lit: Color, onClick: () -> Unit) {
        Box(Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) lit else PANEL2).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(text, color = if (on) Color(0xFF142018) else TEXT, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    fun CtrlButton(size: Dp, bg: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
        Box(Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) { content() }
    }

    /** Значки ⏮ ▶ ⏸ ⏭ — нарисованные, чтобы не зависеть от шрифта эмодзи. */
    @Composable
    fun DrawIcon(kind: String, color: Color) {
        Canvas(Modifier.size(24.dp)) {
            val w = size.width
            val h = size.height
            fun tri(x0: Float, x1: Float) {
                val p = Path().apply { moveTo(x0, h * 0.15f); lineTo(x1, h / 2); lineTo(x0, h * 0.85f); close() }
                drawPath(p, color)
            }
            when (kind) {
                "play" -> tri(w * 0.25f, w * 0.85f)
                "pause" -> {
                    drawRect(color, Offset(w * 0.22f, h * 0.15f), androidx.compose.ui.geometry.Size(w * 0.18f, h * 0.7f))
                    drawRect(color, Offset(w * 0.60f, h * 0.15f), androidx.compose.ui.geometry.Size(w * 0.18f, h * 0.7f))
                }
                "next" -> {
                    tri(w * 0.15f, w * 0.7f)
                    drawRect(color, Offset(w * 0.72f, h * 0.15f), androidx.compose.ui.geometry.Size(w * 0.12f, h * 0.7f))
                }
                "prev" -> {
                    val p = Path().apply { moveTo(w * 0.85f, h * 0.15f); lineTo(w * 0.3f, h / 2); lineTo(w * 0.85f, h * 0.85f); close() }
                    drawPath(p, color)
                    drawRect(color, Offset(w * 0.16f, h * 0.15f), androidx.compose.ui.geometry.Size(w * 0.12f, h * 0.7f))
                }
            }
        }
    }

    @Composable
    fun SkinView(skin: Skin, theme: LcdTheme, lcd: LcdState, modifier: Modifier) {
        Canvas(modifier.pointerInput(skin) {
            detectTapGestures(onTap = { o ->
                val s = minOf(size.width / skin.w, size.height / skin.h)
                val x = (o.x - (size.width - skin.w * s) / 2) / s
                val y = (o.y - (size.height - skin.h * s) / 2) / s
                when (skin.buttons.entries.firstOrNull { it.value.contains(x, y) }?.key) {
                    "prev" -> press { controller?.seekToPrevious() }
                    "next" -> press { controller?.seekToNext() }
                    "play" -> press { togglePlay() }
                    "shuffle" -> press { toggleShuffle() }
                    "menu" -> press { goTab(1) }
                    else -> when {
                        skin.screen.contains(x, y) -> press { togglePlay() }
                        skin.folderZone?.contains(x, y) == true -> press { goTab(4) }
                    }
                }
            })
        }) {
            val s = minOf(size.width / skin.w, size.height / skin.h)
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas
                nc.save()
                nc.translate((size.width - skin.w * s) / 2, (size.height - skin.h * s) / 2)
                nc.scale(s, s)
                nc.drawBitmap(skin.body, 0f, 0f, null)
                if (skin.cap != null && skin.capRect != null) nc.drawBitmap(skin.cap, null, skin.capRect, null)
                LcdPainter.draw(nc, skin, theme, lcd)
                nc.restore()
            }
        }
    }

    // ---------------------------------------------------------------- мини-плеер
    @Composable
    fun MiniPlayer(pos: Long, lit: Color) {
        val s = current() ?: return
        Column(Modifier.fillMaxWidth().background(PANEL2).clickable { goTab(0) }) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(PANEL)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(if (duration > 0) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f)
                    .background(lit))
            }
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(s, 42.dp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(s.title, color = TEXT, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(s.artist, color = DIM, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CtrlButton(40.dp, lit, { press { togglePlay() } }) { DrawIcon(if (playing) "pause" else "play", Color(0xFF142018)) }
                Spacer(Modifier.width(6.dp))
                CtrlButton(40.dp, PANEL, { press { controller?.seekToNext() } }) { DrawIcon("next", TEXT) }
            }
        }
    }

    // ---------------------------------------------------------------- списки
    @Composable
    fun Art(s: Song, size: Dp) {
        val ctx = LocalContext.current
        val bmp by produceState<Bitmap?>(MusicStore.cachedArt(s), s.uri) {
            if (!MusicStore.hasArtCached(s)) value = withContext(Dispatchers.IO) { MusicStore.loadArt(ctx, s) }
        }
        Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(PANEL2), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text("♪", color = DIM, fontSize = (size.value * 0.4f).sp)
        }
    }

    @Composable
    fun SearchField(query: String, hint: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = query, onValueChange = onChange, singleLine = true,
            placeholder = { Text(hint, color = DIM, fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            textStyle = LocalTextStyle.current.copy(color = TEXT, fontSize = 15.sp),
            shape = RoundedCornerShape(12.dp),
        )
    }

    @Composable
    fun SongsScreen(title: String, list: List<Song>, lit: Color, header: (@Composable () -> Unit)? = null) {
        var query by remember(title) { mutableStateOf("") }
        val shown = remember(list, query) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) list else list.filter { q in "${it.title} ${it.artist} ${it.album} ${it.fileName}".lowercase() }
        }
        Column(Modifier.fillMaxSize()) {
            header?.invoke()
            SearchField(query, if (loading) "Ищу музыку…" else "Поиск · ${list.size} треков") { query = it }
            if (list.isEmpty() && !loading) {
                Text("На телефоне не нашлось музыки (mp3, flac, wav…)", color = DIM, modifier = Modifier.padding(16.dp))
            }
            val state = rememberLazyListState()
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(shown, key = { _, s -> s.uri }) { i, s ->
                    SongRow(s, s.uri == currentId, lit) { play(shown, i) }
                }
            }
        }
    }

    @Composable
    fun SongRow(s: Song, isCur: Boolean, lit: Color, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (isCur) lit.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(s, 46.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(s.title, color = if (isCur) lit else TEXT, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal)
                Text(listOf(s.artist.ifEmpty { "—" }, fmtDur(s.durationMs)).joinToString(" · "), color = DIM,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(s.ext, color = DIM, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    fun AlbumsScreen() {
        var query by remember { mutableStateOf("") }
        val albums = remember(songs) {
            songs.groupBy { it.albumId }.values.map { it.sortedBy { s -> s.fileName.lowercase() } }
                .sortedBy { it[0].album.lowercase() }
        }
        val shown = remember(albums, query) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) albums else albums.filter { q in "${it[0].album} ${it[0].artist}".lowercase() }
        }
        Column(Modifier.fillMaxSize()) {
            SearchField(query, "Поиск · ${albums.size} альбомов") { query = it }
            LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shown, key = { it[0].albumId }) { al ->
                    val first = al[0]
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                        detail = Detail(2, first.albumId.toString(), first.album.ifEmpty { "Без альбома" })
                    }) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) { Art(first, maxWidth) }
                        Text(first.album.ifEmpty { "Без альбома" }, color = TEXT, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        Text("${first.artist.ifEmpty { "—" }} · ${al.size}", color = DIM, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    /** Исполнители (kind=1) или папки (kind=3). */
    @Composable
    fun GroupScreen(kind: Int, groups: Map<String, List<Song>>) {
        var query by remember(kind) { mutableStateOf("") }
        val names = remember(groups, query) {
            val q = query.trim().lowercase()
            groups.keys.filter { q.isEmpty() || q in it.lowercase() }.sortedBy { it.lowercase() }
        }
        Column(Modifier.fillMaxSize()) {
            SearchField(query, "Поиск · ${groups.size} " + if (kind == 1) "исполнителей" else "папок") { query = it }
            LazyColumn(Modifier.fillMaxSize()) {
                items(names, key = { it }) { name ->
                    val list = groups[name] ?: emptyList()
                    Row(Modifier.fillMaxWidth().clickable { detail = Detail(kind, name, name.substringAfterLast('/')) }
                        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Art(list[0], 46.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(if (kind == 3) name.substringAfterLast('/') else name, color = TEXT, fontSize = 15.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (kind == 3) "$name · ${list.size} треков" else "${list.size} треков", color = DIM,
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("›", color = DIM, fontSize = 20.sp)
                    }
                }
            }
        }
    }

    @Composable
    fun DetailScreen(d: Detail, lit: Color) {
        val list = remember(songs, d) {
            when (d.kind) {
                1 -> songs.filter { it.artist.ifEmpty { "Неизвестный исполнитель" } == d.key }
                2 -> songs.filter { it.albumId.toString() == d.key }.sortedBy { it.fileName.lowercase() }
                else -> songs.filter { it.folder.ifEmpty { "Память телефона" } == d.key }.sortedBy { it.fileName.lowercase() }
            }
        }
        SongsScreen(d.title, list, lit) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { detail = null }) { Text("‹", color = TEXT, fontSize = 24.sp) }
                Column(Modifier.weight(1f)) {
                    Text(d.title, color = TEXT, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text("${list.size} треков · ${fmtDur(list.sumOf { it.durationMs })}", color = DIM, fontSize = 12.sp)
                }
                Pill("▶ Всё", true, lit) {
                    controller?.shuffleModeEnabled = false
                    prefs.shuffle = false
                    play(list, 0)
                }
                Spacer(Modifier.width(6.dp))
                Pill("RND", false, lit) {
                    prefs.shuffle = true
                    setQueue(list, (list.indices).random(), 0, autoplay = true)
                    controller?.shuffleModeEnabled = true
                }
            }
        }
    }

    // ---------------------------------------------------------------- настройки
    @Composable
    fun SettingsDialog(theme: LcdTheme, onLyricsFolder: () -> Unit, onClose: () -> Unit) {
        AlertDialog(
            onDismissRequest = onClose,
            containerColor = PANEL,
            title = { Text("Настройки", color = TEXT) },
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
                            }.padding(vertical = 6.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Подсветка экрана", color = DIM, fontSize = 12.sp)
                    Row(Modifier.padding(top = 6.dp)) {
                        THEMES.forEach { t ->
                            Box(Modifier.padding(end = 8.dp).size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color(t.lit))
                                .clickable { themeName = t.name; prefs.theme = t.name },
                                contentAlignment = Alignment.Center) {
                                if (t.name == theme.name) Text("✓", color = Color(t.ink), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Звуки кнопок", color = DIM, fontSize = 12.sp)
                    Row(Modifier.padding(top = 6.dp)) {
                        Sounds.LEVELS.forEach { (name, v) ->
                            Box(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (sfx == v) Color(theme.lit) else PANEL2)
                                .clickable {
                                    sfx = v
                                    prefs.sfxVolume = v
                                    sounds.volume = v
                                    sounds.click()
                                }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(name, color = if (sfx == v) Color(theme.ink) else TEXT, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Тексты песен (.lrc)", color = DIM, fontSize = 12.sp)
                    Text(
                        if (prefs.lyricsTree == null) "Android не показывает приложениям .lrc сам — укажи папку, где они лежат " +
                            "(можно всю «Музыку»)." else "Папка с текстами выбрана ✓",
                        color = TEXT, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(onClick = onLyricsFolder) {
                        Text(if (prefs.lyricsTree == null) "📁 Выбрать папку с текстами" else "📁 Выбрать другую папку")
                    }
                    TextButton(onClick = { reload(); showFlash("ИЩУ МУЗЫКУ…") }) { Text("⟳ Найти музыку заново") }
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("Готово") } },
        )
    }

    // ---------------------------------------------------------------- проверка на эмуляторе
    private fun makeTestTrack() {
        val name = "01 - Тест - Синус.wav"
        val lyricsDir = java.io.File(filesDir, "lyrics").apply { mkdirs() }
        java.io.File(lyricsDir, "01 - Тест - Синус.lrc").writeText(
            "[00:00.50]Первая строчка песни\n[00:03.00]Вторая строчка, которая подлиннее остальных\n[00:30.00]Третья\n")
        if (Build.VERSION.SDK_INT < 29) return
        val col = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        contentResolver.query(col, arrayOf(MediaStore.Audio.Media._ID), "${MediaStore.Audio.Media.DISPLAY_NAME}=?",
            arrayOf(name), null)?.use { if (it.count > 0) return }
        val v = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/MP3PlayerTest")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(col, v) ?: return
        val sr = 44100
        val n = sr * 40
        val data = java.nio.ByteBuffer.allocate(44 + n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(2).putInt(sr).putInt(sr * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(n * 4)
        for (i in 0 until n) {
            val s = (6000 * kotlin.math.sin(2 * Math.PI * 440 * i / sr)).toInt().toShort()
            data.putShort(s).putShort(s)
        }
        contentResolver.openOutputStream(uri)?.use { it.write(data.array()) }
        contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        Thread.sleep(1500)
        runOnUiThread { reload() }
    }
}
