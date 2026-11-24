package com.spiderstudio.amrplayer

import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)
    private var openId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (savedInstanceState == null) openId = parseOpenId(intent)
        setContent {
            AmrPlayerTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AppRoot(resumeTick = resumeTick, openId = openId, onOpenHandled = { openId = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseOpenId(intent)?.let { openId = it }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    /** فتح ملف صوتي من تطبيق ثاني (مثل Amrtune): نقرأ رقم MediaStore إن وُجد. */
    private fun parseOpenId(i: Intent?): Long? {
        if (i?.action != Intent.ACTION_VIEW) return null
        val data = i.data ?: return null
        if (data.authority != "media") return null
        return try {
            ContentUris.parseId(data)
        } catch (_: Exception) {
            null
        }
    }
}
