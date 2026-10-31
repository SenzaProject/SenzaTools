package com.senzaproject.senzaai.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** Navigasi utama sesuai spesifikasi: Home, Device, Analyzer, Tools, AI Assistant, Settings. */
enum class Dest(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    DEVICE("Device", Icons.Rounded.PhoneAndroid),
    ANALYZER("Analyzer", Icons.Rounded.Analytics),
    TOOLS("Tools", Icons.Rounded.Build),
    AI("AI", Icons.Rounded.AutoAwesome),
    SETTINGS("Settings", Icons.Rounded.Settings)
}
