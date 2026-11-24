package com.spiderstudio.amrplayer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** ألوان الهوية (نفس Amrtune): داكن + تركوازي. */
object C {
    val Bg = Color(0xFF030D1B)
    val Card = Color(0xFF0D1B2B)
    val Card2 = Color(0xFF14263A)
    val Border = Color(0xFF1A2D43)
    val Teal = Color(0xFF34D3B0)
    val OnTeal = Color(0xFF03241D)
    val Text = Color(0xFFEAF0F6)
    val Muted = Color(0xFF8A98AB)
    val Red = Color(0xFFFF7A7A)
}

@Composable
fun AmrPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Teal,
            onPrimary = C.OnTeal,
            background = C.Bg,
            onBackground = C.Text,
            surface = C.Card,
            onSurface = C.Text,
            surfaceVariant = C.Card2,
            error = C.Red,
        ),
        typography = Typography(),
        content = content,
    )
}

/** أيقونات غير موجودة بمجموعة core (مسارات Material Icons). */
object AppIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    val MusicNote: ImageVector by lazy {
        icon("music_note", "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z")
    }
}
