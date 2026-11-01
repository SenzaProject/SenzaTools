package com.senzaproject.senzatools.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.ui.graphics.vector.ImageVector

/** Bar navigasi: Home, Device, Analyzer, Tools, Toolkit. Settings dibuka lewat ikon gear di Home. */
enum class Dest(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    DEVICE("Device", Icons.Rounded.PhoneAndroid),
    ANALYZER("Analyzer", Icons.Rounded.Analytics),
    TOOLS("Tools", Icons.Rounded.Build),
    TOOLKIT("Toolkit", Icons.Rounded.Widgets),
    SETTINGS("Settings", Icons.Rounded.Settings);

    companion object {
        /** Tujuan yang tampil di bar navigasi (Settings dibuka dari Home). */
        val inNav: Array<Dest> = arrayOf(HOME, DEVICE, ANALYZER, TOOLS, TOOLKIT)
    }
}
