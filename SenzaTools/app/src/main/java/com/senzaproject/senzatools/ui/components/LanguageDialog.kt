package com.senzaproject.senzatools.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.senzaproject.senzatools.i18n.Lang
import com.senzaproject.senzatools.i18n.tr
import com.senzaproject.senzatools.ui.theme.LocalGlass

/** Dialog pemilih bahasa. Perubahan berlaku langsung tanpa restart aplikasi. */
@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val g = LocalGlass.current
    val selected = Lang.selected
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (g.dark) Color(0xFF151B3D) else Color(0xFFF4F6FF),
        shape = RoundedCornerShape(28.dp),
        title = { Text(tr("Bahasa"), color = g.text, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                LanguageRow(tr("Ikuti sistem"), selected == Lang.SYSTEM) {
                    Lang.set(ctx, Lang.SYSTEM)
                    onDismiss()
                }
                Lang.languages.forEach { l ->
                    LanguageRow(l.nativeName, selected == l.code) {
                        Lang.set(ctx, l.code)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("Tutup"), color = g.accent, fontWeight = FontWeight.Bold) }
        }
    )
}

@Composable
private fun LanguageRow(name: String, selected: Boolean, onClick: () -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name, Modifier.weight(1f),
            color = if (selected) g.accent else g.text,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
        if (selected) Text("✓", color = g.accent, fontWeight = FontWeight.Bold)
    }
}
