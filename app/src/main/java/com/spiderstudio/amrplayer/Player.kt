package com.spiderstudio.amrplayer

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/** حالة المشغّل كما تراها الواجهة (مصدرها MediaController). */
class PlayerState {
    var controller by mutableStateOf<MediaController?>(null)
    var playing by mutableStateOf(false)
    var mediaId by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var duration by mutableLongStateOf(0L)
    var position by mutableLongStateOf(0L)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var error by mutableStateOf<String?>(null)

    fun sync(c: MediaController) {
        playing = c.isPlaying
        mediaId = c.currentMediaItem?.mediaId
        title = c.mediaMetadata.title?.toString() ?: ""
        artist = c.mediaMetadata.artist?.toString() ?: ""
        duration = if (c.duration > 0) c.duration else 0L
        position = c.currentPosition.coerceAtLeast(0L)
        shuffle = c.shuffleModeEnabled
        repeat = c.repeatMode
    }
}

fun toMediaItem(song: AudioSong): MediaItem =
    MediaItem.Builder()
        .setMediaId(song.id.toString())
        .setUri(song.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .build(),
        )
        .build()

@Composable
fun rememberPlayerState(context: Context): PlayerState {
    val state = remember { PlayerState() }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future: ListenableFuture<MediaController> = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        state.sync(c)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        state.error = "تعذّر تشغيل هذا الملف"
                    }
                })
                state.controller = c
                state.sync(c)
            } catch (_: Exception) {
                state.error = "تعذّر الاتصال بخدمة التشغيل"
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            state.controller = null
            MediaController.releaseFuture(future)
        }
    }
    return state
}
