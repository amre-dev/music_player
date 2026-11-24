package com.spiderstudio.amrplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import java.util.concurrent.Executor

data class AudioSong(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: android.net.Uri
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { AmrPlayerTheme { PlayerApp() } }
    }
}

private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

private fun readSongs(context: Context): List<AudioSong> {
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION
    )
    val songs = mutableListOf<AudioSong>()
    try {
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "مقطع صوتي"
                val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "فنان غير معروف"
                val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "ألبوم غير معروف"
                val duration = cursor.getLong(durationCol)
                val uri = ContentUris.withAppendedId(collection, id)
                songs += AudioSong(id, title, artist, album, duration, uri)
            }
        }
    } catch (_: SecurityException) {
        return emptyList()
    } catch (_: Exception) {
        return emptyList()
    }
    return songs
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun PlayerApp() {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(hasAudioPermission(context)) }
    var songs by remember { mutableStateOf(emptyList<AudioSong>()) }
    var query by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    var selectedTab by remember { mutableStateOf(0) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }

    val controllerState = remember { mutableStateOf<MediaController?>(null) }
    val playingState = remember { mutableStateOf(false) }
    val currentTitle = remember { mutableStateOf("ما في أغنية قيد التشغيل") }
    val currentArtist = remember { mutableStateOf("اختار أغنية من مكتبتك") }
    val durationState = remember { mutableStateOf(0L) }
    val positionState = remember { mutableStateOf(0L) }

    DisposableEffect(context) {
        val token = SessionToken(context, android.content.ComponentName(context, PlaybackService::class.java))
        val future: ListenableFuture<MediaController> = MediaController.Builder(context, token).buildAsync()
        val executor: Executor = ContextCompat.getMainExecutor(context)
        future.addListener({
            try {
                val controller = future.get()
                controllerState.value = controller
                fun sync() {
                    playingState.value = controller.isPlaying
                    currentTitle.value = controller.mediaMetadata.title?.toString() ?: "ما في أغنية قيد التشغيل"
                    currentArtist.value = controller.mediaMetadata.artist?.toString() ?: "اختار أغنية من مكتبتك"
                    durationState.value = controller.duration.takeIf { it > 0 } ?: 0L
                    positionState.value = controller.currentPosition.coerceAtLeast(0L)
                }
                controller.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) = sync()
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = sync()
                    override fun onPlaybackStateChanged(playbackState: Int) = sync()
                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = sync()
                })
                sync()
            } catch (_: Exception) { }
        }, executor)
        onDispose {
            MediaController.releaseFuture(future)
            controllerState.value = null
        }
    }

    LaunchedEffect(permissionGranted, refreshKey) {
        if (permissionGranted) songs = withContext(Dispatchers.IO) { readSongs(context) }
    }

    DisposableEffect(permissionGranted) {
        if (!permissionGranted) {
            onDispose { }
        } else {
            val scope = CoroutineScope(Dispatchers.Main)
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = refresh()
                override fun onChange(selfChange: Boolean, uri: android.net.Uri?) = refresh()
                private fun refresh() {
                    scope.launch {
                        val latest = withContext(Dispatchers.IO) { readSongs(context) }
                        songs = latest
                    }
                }
            }
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            onDispose {
                context.contentResolver.unregisterContentObserver(observer)
                scope.cancel()
            }
        }
    }

    LaunchedEffect(controllerState.value) {
        while (true) {
            val controller = controllerState.value
            if (controller != null) {
                positionState.value = controller.currentPosition.coerceAtLeast(0L)
                durationState.value = controller.duration.takeIf { it > 0 } ?: 0L
                playingState.value = controller.isPlaying
            }
            delay(500)
        }
    }

    val filtered = songs.filter {
        it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(C.Bg)
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(C.Card2, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, null, tint = C.Teal, modifier = Modifier.size(25.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("AmrPlayer", color = C.Text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("مكتبة الموسيقى", color = C.Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { if (permissionGranted) refreshKey++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = "تحديث المكتبة", tint = C.Muted)
                }
            }

            Spacer(Modifier.height(22.dp))
            Text("الأغاني", color = C.Text, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("${songs.size} أغنية على الجهاز", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))

            if (!permissionGranted) {
                Surface(
                    color = C.Card,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, C.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.MusicNote, null, tint = C.Teal, modifier = Modifier.size(36.dp))
                        Text("خلّينا نوصل لمكتبتك الموسيقية", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("AmrPlayer بيقرأ ملفات الصوت الموجودة على جهازك فقط. ما بيرفع أي ملفات ولا يحتاج إنترنت.", color = C.Muted, fontSize = 14.sp)
                        Button(
                            onClick = {
                                permissionMessage = "اسمح بالوصول للصوت حتى تظهر أغاني جهازك."
                                audioPermissionLauncher.launch(audioPermission())
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = C.Teal, contentColor = C.OnTeal)
                        ) { Text("السماح بالوصول للموسيقى") }
                        permissionMessage?.let { Text(it, color = C.Muted, fontSize = 12.sp) }
                    }
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("ابحث عن أغنية أو فنان") },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = C.Muted) }
                )
                Spacer(Modifier.height(12.dp))
                if (songs.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("ما لقينا أغاني. جرّب تحديث المكتبة.", color = C.Muted)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filtered, key = { it.id }) { song ->
                            SongRow(song, onClick = {
                                val controller = controllerState.value
                                if (controller != null) {
                                    if (Build.VERSION.SDK_INT >= 33 &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    val playlist = songs.map { toMediaItem(it) }
                                    val index = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                                    controller.setMediaItems(playlist, index, 0L)
                                    controller.prepare()
                                    controller.play()
                                }
                            })
                        }
                    }
                }
            }
        }

        controllerState.value?.let { controller ->
            MiniPlayer(
                title = currentTitle.value,
                artist = currentArtist.value,
                playing = playingState.value,
                position = positionState.value,
                duration = durationState.value,
                onPlayPause = { if (controller.isPlaying) controller.pause() else controller.play() },
                onNext = { controller.seekToNextMediaItem() },
                onPrevious = { controller.seekToPreviousMediaItem() },
                onSeek = { controller.seekTo(it) }
            )
        }

        NavigationBar(containerColor = C.Card) {
            NavigationBarItem(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                icon = { Icon(Icons.Default.MusicNote, null) },
                label = { Text("المكتبة") }
            )
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                icon = { Icon(Icons.Default.Search, null) },
                label = { Text("بحث") }
            )
            NavigationBarItem(
                selected = false,
                onClick = { refreshKey++; selectedTab = 0 },
                icon = { Icon(Icons.Default.Refresh, null) },
                label = { Text("تحديث") }
            )
        }
    }
}


private fun toMediaItem(song: AudioSong): MediaItem =
    MediaItem.Builder()
        .setMediaId(song.id.toString())
        .setUri(song.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .build()
        )
        .build()

@Composable
private fun SongRow(song: AudioSong, onClick: () -> Unit) {
    Surface(
        color = C.Card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, C.Border),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(42.dp).background(C.Card2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, tint = C.Teal)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = C.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(formatTime(song.duration), color = C.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MiniPlayer(
    title: String,
    artist: String,
    playing: Boolean,
    position: Long,
    duration: Long,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit
) {
    Column(Modifier.fillMaxWidth().background(C.Card).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Slider(
            value = if (duration > 0) position.coerceIn(0L, duration).toFloat() / duration else 0f,
            onValueChange = { if (duration > 0) onSeek((it * duration).toLong()) },
            modifier = Modifier.fillMaxWidth().height(20.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = C.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artist, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatTime(position)} / ${formatTime(duration)}", color = C.Muted, fontSize = 10.sp)
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "السابق", tint = C.Text) }
            IconButton(onClick = onPlayPause) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "إيقاف" else "تشغيل", tint = C.Teal, modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "التالي", tint = C.Text) }
        }
    }
}
