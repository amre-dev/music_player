package com.spiderstudio.amrplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** هيكل التنقل: ثلاث تبويبات فاضية. المرحلة 1 بتعبّيها (انظر AMRPLAYER_SPEC.md). */
@Composable
fun AppRoot() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Scaffold(
            containerColor = C.Bg,
            bottomBar = { BottomBar(tab) { tab = it } },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Placeholder(
                    when (tab) {
                        0 -> "المكتبة"
                        1 -> "قوائم التشغيل"
                        else -> "الإعدادات"
                    }
                )
            }
        }
    }
}

@Composable
private fun Placeholder(title: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(AppIcons.MusicNote, contentDescription = null, tint = C.Muted, modifier = Modifier.size(48.dp))
        Text(title, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("قريباً", color = C.Muted, fontSize = 14.sp)
    }
}

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
            icon = { Icon(Icons.Default.List, contentDescription = null) },
            label = { Text("القوائم") },
        )
        NavigationBarItem(
            selected = tab == 2, onClick = { onTab(2) }, colors = colors,
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("الإعدادات") },
        )
    }
}
