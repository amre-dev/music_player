package com.spiderstudio.amrplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class AudioSong(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val added: Long,
    val uri: Uri,
)

/** قراءة المكتبة من MediaStore فقط. أي فشل يرجّع قائمة فاضية بدل الانهيار. */
fun readSongs(context: Context): List<AudioSong> {
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATE_ADDED,
    )
    val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?)"
    val args = arrayOf("Music/%")
    val out = ArrayList<AudioSong>()
    try {
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out += AudioSong(
                    id = id,
                    title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: "مقطع صوتي",
                    artist = c.getString(iArtist)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "فنان غير معروف",
                    album = c.getString(iAlbum)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "",
                    duration = c.getLong(iDur),
                    added = c.getLong(iAdded),
                    uri = ContentUris.withAppendedId(collection, id),
                )
            }
        }
    } catch (_: Exception) {
        return emptyList()
    }
    return out
}

fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/** تخزين خفيف محلي: المفضلة + آخر أغنية وموضعها. */
class Store(context: Context) {
    private val sp = context.getSharedPreferences("amrplayer", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = HashSet(sp.getStringSet("favs", emptySet()) ?: emptySet())
    fun saveFavorites(s: Set<String>) { sp.edit().putStringSet("favs", HashSet(s)).apply() }

    var lastId: Long
        get() = sp.getLong("last_id", -1L)
        set(v) { sp.edit().putLong("last_id", v).apply() }
    var lastPos: Long
        get() = sp.getLong("last_pos", 0L)
        set(v) { sp.edit().putLong("last_pos", v).apply() }
}
