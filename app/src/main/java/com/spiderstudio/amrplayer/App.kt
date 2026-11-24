package com.spiderstudio.amrplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ───────────────────────── صلاحيات ─────────────────────────

private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

// ───────────────────────── الجذر ─────────────────────────

@Composable
fun AppRoot(resumeTick: Int, openId: Long?, onOpenHandled: () -> Unit) {
    val context = LocalContext.current
    val store = remember { Store(context) }
    val ps = rememberPlayerState(context)

    var granted by remember { mutableStateOf(hasAudioPermission(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(resumeTick) { granted = hasAudioPermission(context) }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // إذن الإشعارات (أندرويد 13+) بعد منح إذن الصوت
    LaunchedEffect(granted) {
        if (granted && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // المكتبة
    var songs by remember { mutableStateOf(emptyList<AudioSong>()) }
    var loaded by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(granted, refreshKey) {
        if (granted) {
            songs = withContext(Dispatchers.IO) { readSongs(context) }
            loaded = true
        }
    }

    // مراقبة MediaStore: أي أغنية جديدة بتظهر لحالها
    DisposableEffect(granted) {
        if (!granted) {
            onDispose { }
        } else {
            val handler = Handler(Looper.getMainLooper())
            val reload = Runnable { refreshKey++ }
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    handler.removeCallbacks(reload)
                    handler.postDelayed(reload, 800)
                }
            }
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            onDispose {
                context.contentResolver.unregisterContentObserver(observer)
                handler.removeCallbacks(reload)
            }
        }
    }

    var favs by remember { mutableStateOf(store.favorites()) }
    var sortNewest by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showFull by rememberSaveable { mutableStateOf(false) }

    val sorted = remember(songs, sortNewest) {
        if (sortNewest) songs.sortedByDescending { it.added } else songs.sortedBy { it.title.lowercase() }
    }
    val visible = remember(sorted, query, tab, favs) {
        val base = if (tab == 1) sorted.filter { favs.contains(it.id.toString()) } else sorted
        val q = query.trim()
        if (q.isEmpty()) base else base.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }
    }

    fun play(list: List<AudioSong>, index: Int) {
        val c = ps.controller ?: return
        if (index < 0 || index >= list.size) return
        ps.error = null
        c.setMediaItems(list.map { toMediaItem(it) }, index, 0L)
        c.prepare()
        c.play()
    }

    fun toggleFav(id: Long) {
        val key = id.toString()
        val next = HashSet(favs)
        if (!next.add(key)) next.remove(key)
        favs = next
        store.saveFavorites(next)
    }

    // فتح أغنية من تطبيق خارجي
    LaunchedEffect(ps.controller, sorted, openId) {
        if (openId != null && ps.controller != null && sorted.isNotEmpty()) {
            val idx = sorted.indexOfFirst { it.id == openId }
            if (idx >= 0) play(sorted, idx)
            onOpenHandled()
        }
    }

    // تذكّر آخر أغنية وموضعها (بدون تشغيل تلقائي)
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(ps.controller, sorted) {
        val c = ps.controller ?: return@LaunchedEffect
        if (restored || sorted.isEmpty()) return@LaunchedEffect
        restored = true
        if (c.mediaItemCount == 0 && openId == null) {
            val idx = sorted.indexOfFirst { it.id == store.lastId }
            if (idx >= 0) {
                c.setMediaItems(sorted.map { toMediaItem(it) }, idx, store.lastPos)
                c.prepare()
            }
        }
    }

    // تحديث الموضع وحفظه
    LaunchedEffect(ps.controller) {
        var tick = 0
        while (true) {
            val c = ps.controller
            if (c != null) {
                ps.position = c.currentPosition.coerceAtLeast(0L)
                if (c.duration > 0) ps.duration = c.duration
                tick++
                val id = c.currentMediaItem?.mediaId?.toLongOrNull()
                if (id != null && (tick % 4 == 0 || !c.isPlaying)) {
                    store.lastId = id
                    store.lastPos = ps.position
                }
            }
            delay(500)
        }
    }

    val current = ps.mediaId?.let { id -> songs.firstOrNull { it.id.toString() == id } }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Scaffold(
            containerColor = C.Bg,
            bottomBar = {
                Column {
                    if (ps.mediaId != null) {
                        MiniPlayer(ps, current, onOpen = { showFull = true })
                    }
                    BottomBar(tab) { tab = it }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (!granted) {
                    PermissionScreen(
                        asked = asked,
                        onAllow = { audioLauncher.launch(audioPermission()) },
                        onSettings = {
                            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            try { context.startActivity(i) } catch (_: Exception) { }
                        },
                    )
                } else {
                    LibraryScreen(
                        title = if (tab == 1) "المفضلة" else "الأغاني",
                        total = visible.size,
                        query = query,
                        onQuery = { query = it },
                        sortNewest = sortNewest,
                        onSort = { sortNewest = it },
                        loaded = loaded,
                        emptyText = if (tab == 1) "ما في أغاني مفضلة بعد. اضغط القلب بجانب أي أغنية." else "ما لقينا أغاني على الجهاز.",
                        songs = visible,
                        playingId = ps.mediaId,
                        favs = favs,
                        onPlay = { idx -> play(visible, idx) },
                        onFav = { toggleFav(it) },
                        error = ps.error,
                    )
                }
            }
        }

        if (showFull && ps.mediaId != null) {
            BackHandler { showFull = false }
            FullPlayer(
                ps = ps,
                song = current,
                isFav = current != null && favs.contains(current.id.toString()),
                onFav = { current?.let { toggleFav(it.id) } },
                onClose = { showFull = false },
            )
        }
    }
}

// ───────────────────────── المكتبة ─────────────────────────

@Composable
private fun LibraryScreen(
    title: String,
    total: Int,
    query: String,
    onQuery: (String) -> Unit,
    sortNewest: Boolean,
    onSort: (Boolean) -> Unit,
    loaded: Boolean,
    emptyText: String,
    songs: List<AudioSong>,
    playingId: String?,
    favs: Set<String>,
    onPlay: (Int) -> Unit,
    onFav: (Long) -> Unit,
    error: String?,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        Text("AmrPlayer", color = C.Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(title, color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("$total أغنية", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))

        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(50),
            placeholder = { Text("ابحث عن أغنية أو فنان") },
            leadingIcon = { Icon(AppIcons.Search, null, tint = C.Muted) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = C.Text,
                unfocusedTextColor = C.Text,
                focusedBorderColor = C.Teal,
                unfocusedBorderColor = C.Border,
                cursorColor = C.Teal,
                focusedPlaceholderColor = C.Muted,
                unfocusedPlaceholderColor = C.Muted,
            ),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("الاسم", !sortNewest) { onSort(false) }
            Pill("المضافة حديثاً", sortNewest) { onSort(true) }
        }
        Spacer(Modifier.height(10.dp))

        if (error != null) {
            Text(error, color = C.Red, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        }

        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("جاري تحميل المكتبة...", color = C.Muted)
            }
        } else if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(emptyText, color = C.Muted, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(songs, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        playing = playingId == song.id.toString(),
                        fav = favs.contains(song.id.toString()),
                        onClick = { onPlay(songs.indexOf(song)) },
                        onFav = { onFav(song.id) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) C.Teal.copy(alpha = 0.15f) else C.Card,
        shape = CircleShape,
        border = BorderStroke(1.dp, if (selected) C.Teal else C.Border),
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
    ) {
        Text(
            text,
            color = if (selected) C.Teal else C.Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun SongRow(song: AudioSong, playing: Boolean, fav: Boolean, onClick: () -> Unit, onFav: () -> Unit) {
    Surface(
        color = C.Card,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (playing) C.Teal else C.Border),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(6.dp))
            Art(song.uri, song.id, 48.dp, 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    color = if (playing) C.Teal else C.Text,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(song.artist, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                if (song.duration > 0) formatTime(song.duration) else "",
                color = C.Muted,
                fontSize = 12.sp,
            )
            IconButton(onClick = onFav) {
                Icon(
                    if (fav) AppIcons.Heart else AppIcons.HeartOutline,
                    contentDescription = if (fav) "إزالة من المفضلة" else "إضافة للمفضلة",
                    tint = if (fav) C.Teal else C.Muted,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

// ───────────────────────── الصلاحية ─────────────────────────

@Composable
private fun PermissionScreen(asked: Boolean, onAllow: () -> Unit, onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Surface(
            color = C.Card,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, C.Border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(AppIcons.MusicNote, null, tint = C.Teal, modifier = Modifier.size(44.dp))
                Text("خلّينا نوصل لمكتبتك الموسيقية", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(
                    "AmrPlayer بيقرأ ملفات الصوت الموجودة على جهازك فقط. ما بيرفع أي ملفات ولا بيحتاج إنترنت.",
                    color = C.Muted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                if (asked) {
                    Text(
                        "بدون هذا الإذن ما بنقدر نعرض أغانيك. فعّله من إعدادات التطبيق.",
                        color = C.Red,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                Button(
                    onClick = onAllow,
                    colors = ButtonDefaults.buttonColors(containerColor = C.Teal, contentColor = C.OnTeal),
                    modifier = Modifier.height(48.dp),
                ) { Text("السماح بالوصول للموسيقى", fontWeight = FontWeight.Bold) }
                if (asked) {
                    OutlinedButton(onClick = onSettings, modifier = Modifier.height(48.dp)) {
                        Text("فتح إعدادات التطبيق", color = C.Text)
                    }
                }
            }
        }
    }
}

// ───────────────────────── الشريط السفلي + المصغّر ─────────────────────────

@Composable
private fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = C.Teal,
        selectedTextColor = C.Teal,
        indicatorColor = C.Teal.copy(alpha = 0.15f),
        unselectedIconColor = C.Muted,
        unselectedTextColor = C.Muted,
    )
    NavigationBar(containerColor = C.Card) {
        NavigationBarItem(
            selected = tab == 0, onClick = { onTab(0) }, colors = colors,
            icon = { Icon(AppIcons.MusicNote, contentDescription = null) },
            label = { Text("المكتبة") },
        )
        NavigationBarItem(
            selected = tab == 1, onClick = { onTab(1) }, colors = colors,
            icon = { Icon(AppIcons.Heart, contentDescription = null) },
            label = { Text("المفضلة") },
        )
    }
}

@Composable
private fun MiniPlayer(ps: PlayerState, song: AudioSong?, onOpen: () -> Unit) {
    val c = ps.controller
    val frac = if (ps.duration > 0) (ps.position.toFloat() / ps.duration).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().background(C.Card2).clickable(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(C.Border)) {
            Box(Modifier.fillMaxWidth(frac).height(2.dp).background(C.Teal))
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(song?.uri, song?.id ?: -1L, 44.dp, 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ps.title, color = C.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ps.artist, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                IconButton(onClick = { c?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                    Icon(
                        if (ps.playing) AppIcons.Pause else AppIcons.Play,
                        if (ps.playing) "إيقاف مؤقت" else "تشغيل",
                        tint = C.Teal,
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = { c?.seekToNextMediaItem() }) {
                    Icon(AppIcons.Next, "التالي", tint = C.Text)
                }
            }
        }
    }
}

// ───────────────────────── شاشة التشغيل ─────────────────────────

@Composable
private fun FullPlayer(ps: PlayerState, song: AudioSong?, isFav: Boolean, onFav: () -> Unit, onClose: () -> Unit) {
    val c = ps.controller
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = if (ps.duration > 0) (ps.position.toFloat() / ps.duration).coerceIn(0f, 1f) else 0f

    Column(
        Modifier.fillMaxSize().background(C.Bg).systemBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(AppIcons.Down, "إغلاق", tint = C.Text) }
            Spacer(Modifier.weight(1f))
            Text("قيد التشغيل", color = C.Muted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onFav) {
                Icon(
                    if (isFav) AppIcons.Heart else AppIcons.HeartOutline,
                    "المفضلة",
                    tint = if (isFav) C.Teal else C.Muted,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Art(song?.uri, song?.id ?: -1L, 280.dp, 24.dp)
        Spacer(Modifier.weight(1f))
        Text(ps.title, color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(ps.artist, color = C.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (ps.error != null) {
            Spacer(Modifier.height(6.dp))
            Text(ps.error ?: "", color = C.Red, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Slider(
                value = drag ?: frac,
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    val d = drag
                    if (d != null && ps.duration > 0) {
                        val target = (d * ps.duration).toLong()
                        c?.seekTo(target)
                        ps.position = target
                    }
                    drag = null
                },
                colors = SliderDefaults.colors(
                    thumbColor = C.Teal,
                    activeTrackColor = C.Teal,
                    inactiveTrackColor = C.Border,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                val shown = if (drag != null) (drag!! * ps.duration).toLong() else ps.position
                Text(formatTime(shown), color = C.Muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(formatTime(ps.duration), color = C.Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { c?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }, modifier = Modifier.size(52.dp)) {
                    Icon(AppIcons.Shuffle, "خلط", tint = if (ps.shuffle) C.Teal else C.Muted)
                }
                IconButton(onClick = { c?.seekToPreviousMediaItem() }, modifier = Modifier.size(56.dp)) {
                    Icon(AppIcons.Prev, "السابق", tint = C.Text, modifier = Modifier.size(34.dp))
                }
                Surface(
                    color = C.Teal,
                    shape = CircleShape,
                    modifier = Modifier.size(68.dp).clip(CircleShape).clickable {
                        c?.let { if (it.isPlaying) it.pause() else it.play() }
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (ps.playing) AppIcons.Pause else AppIcons.Play,
                            if (ps.playing) "إيقاف مؤقت" else "تشغيل",
                            tint = C.OnTeal,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                }
                IconButton(onClick = { c?.seekToNextMediaItem() }, modifier = Modifier.size(56.dp)) {
                    Icon(AppIcons.Next, "التالي", tint = C.Text, modifier = Modifier.size(34.dp))
                }
                IconButton(
                    onClick = {
                        c?.let {
                            it.repeatMode = when (it.repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                        }
                    },
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        if (ps.repeat == Player.REPEAT_MODE_ONE) AppIcons.RepeatOne else AppIcons.Repeat,
                        "تكرار",
                        tint = if (ps.repeat == Player.REPEAT_MODE_OFF) C.Muted else C.Teal,
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

// ───────────────────────── الغلاف ─────────────────────────

private val artCache = LruCache<String, ImageBitmap>(80)
private val artMissing = HashSet<String>()

private fun loadArt(ctx: Context, uri: Uri, id: Long, px: Int): ImageBitmap? {
    val k = "$id:$px"
    synchronized(artMissing) { if (artMissing.contains(k)) return null }
    return try {
        val bmp: Bitmap = ctx.contentResolver.loadThumbnail(uri, Size(px, px), null)
        val img = bmp.asImageBitmap()
        artCache.put(k, img)
        img
    } catch (_: Exception) {
        synchronized(artMissing) { artMissing.add(k) }
        null
    }
}

@Composable
private fun Art(uri: Uri?, id: Long, size: Dp, radius: Dp) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(64)
    val bmp by produceState<ImageBitmap?>(initialValue = artCache.get("$id:$px"), id, px) {
        if (uri != null && value == null) {
            value = withContext(Dispatchers.IO) { loadArt(ctx, uri, id, px) }
        }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(radius)).background(C.Card2),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(AppIcons.MusicNote, null, tint = C.Teal, modifier = Modifier.size(size / 2))
        }
    }
}
