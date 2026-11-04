package com.senzaproject.senzatools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.senzaproject.senzatools.i18n.tr
import com.senzaproject.senzatools.navigation.Dest
import com.senzaproject.senzatools.ui.theme.LocalGlass

/** Bar navigasi bawah yang datar: latar solid, garis tipis di atas, ikon 20dp, label 10sp. */
@Composable
fun BottomNavBar(current: Dest, onSelect: (Dest) -> Unit, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    Column(modifier.fillMaxWidth().background(g.glassFill)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(g.borderLo))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Dest.inNav.forEach { d ->
                val sel = d == current
                val c = if (sel) g.accent else g.textDim
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(d) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(d.icon, tr(d.label), tint = c, modifier = Modifier.size(20.dp))
                    Text(tr(d.label), color = c, fontSize = 10.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}
