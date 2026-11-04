package com.senzaproject.senzatools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import com.senzaproject.senzatools.di.LocalServices
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.navigation.Dest
import com.senzaproject.senzatools.ui.AnalyzerViewModel
import com.senzaproject.senzatools.ui.VmFactory
import com.senzaproject.senzatools.ui.components.BottomNavBar
import com.senzaproject.senzatools.ui.components.GlassBackground
import com.senzaproject.senzatools.ui.screens.AnalyzerScreen
import com.senzaproject.senzatools.ui.screens.DeviceScreen
import com.senzaproject.senzatools.ui.screens.HomeScreen
import com.senzaproject.senzatools.ui.screens.SettingsScreen
import com.senzaproject.senzatools.ui.screens.ToolsScreen
import com.senzaproject.senzatools.ui.screens.UpdateGate
import com.senzaproject.senzatools.ui.screens.UtilitiesScreen
import com.senzaproject.senzatools.ui.theme.SenzaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val services = (application as SenzaApp).services
        // Root diminta sekali saat apk dibuka; kalau sudah diizinkan di root manager, langsung aktif tanpa dialog.
        services.access.autoRequestRootOnce()
        // Bila "Terapkan saat boot" kernel aktif: tandai boot ini stabil setelah root terverifikasi dan uptime cukup.
        services.kernel.confirmStableLater()
        setContent {
            CompositionLocalProvider(LocalServices provides services) {
                SenzaTheme(services.uiPrefs.themeMode) {
                    UpdateGate(services) { SenzaRoot(services) }
                }
            }
        }
    }
}

@Composable
private fun SenzaRoot(s: ServiceLocator) {
    var current by rememberSaveable { mutableStateOf(Dest.HOME.name) }
    val dest = Dest.values().firstOrNull { it.name == current } ?: Dest.HOME
    val factory = VmFactory(s)
    val analyzerVm: AnalyzerViewModel = viewModel(factory = factory)

    BackHandler(enabled = dest != Dest.HOME) { current = Dest.HOME.name }

    GlassBackground {
        Column(Modifier.fillMaxSize()) {
            // Settings adalah layar sub (dibuka dari Home), jadi bar navigasi disembunyikan di sana.
            Box(Modifier.weight(1f).fillMaxWidth().then(if (dest == Dest.SETTINGS) Modifier.navigationBarsPadding() else Modifier)) {
                when (dest) {
                    Dest.HOME -> HomeScreen(s) { current = it.name }
                    Dest.DEVICE -> DeviceScreen(s)
                    Dest.ANALYZER -> AnalyzerScreen(s, analyzerVm)
                    Dest.TOOLS -> ToolsScreen(s)
                    Dest.TOOLKIT -> UtilitiesScreen(s)
                    Dest.SETTINGS -> SettingsScreen(s) { current = Dest.HOME.name }
                }
            }
            if (dest != Dest.SETTINGS) BottomNavBar(dest, { current = it.name })
        }
    }
}
