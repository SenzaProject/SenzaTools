package com.senzaproject.senzaai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import com.senzaproject.senzaai.di.LocalServices
import com.senzaproject.senzaai.di.ServiceLocator
import com.senzaproject.senzaai.navigation.Dest
import com.senzaproject.senzaai.ui.AnalyzerViewModel
import com.senzaproject.senzaai.ui.ChatViewModel
import com.senzaproject.senzaai.ui.VmFactory
import com.senzaproject.senzaai.ui.components.FloatingNavBar
import com.senzaproject.senzaai.ui.components.GlassBackground
import com.senzaproject.senzaai.ui.screens.AiScreen
import com.senzaproject.senzaai.ui.screens.AnalyzerScreen
import com.senzaproject.senzaai.ui.screens.DeviceScreen
import com.senzaproject.senzaai.ui.screens.HomeScreen
import com.senzaproject.senzaai.ui.screens.SettingsScreen
import com.senzaproject.senzaai.ui.screens.ToolsScreen
import com.senzaproject.senzaai.ui.theme.SenzaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val services = (application as SenzaApp).services
        setContent {
            CompositionLocalProvider(LocalServices provides services) {
                SenzaTheme(services.uiPrefs.themeMode, services.uiPrefs.reducedEffects) {
                    SenzaRoot(services)
                }
            }
        }
    }
}

@Composable
private fun SenzaRoot(s: ServiceLocator) {
    var current by rememberSaveable { mutableStateOf(Dest.HOME.name) }
    val dest = Dest.valueOf(current)
    val factory = VmFactory(s)
    val analyzerVm: AnalyzerViewModel = viewModel(factory = factory)
    val chatVm: ChatViewModel = viewModel(factory = factory)

    BackHandler(enabled = dest != Dest.HOME) { current = Dest.HOME.name }

    GlassBackground {
        Box(Modifier.fillMaxSize()) {
            when (dest) {
                Dest.HOME -> HomeScreen(s) { current = it.name }
                Dest.DEVICE -> DeviceScreen(s)
                Dest.ANALYZER -> AnalyzerScreen(s, analyzerVm)
                Dest.TOOLS -> ToolsScreen(s)
                Dest.AI -> AiScreen(s, chatVm)
                Dest.SETTINGS -> SettingsScreen(s)
            }
            FloatingNavBar(dest, { current = it.name }, Modifier.align(Alignment.BottomCenter))
        }
    }
}
