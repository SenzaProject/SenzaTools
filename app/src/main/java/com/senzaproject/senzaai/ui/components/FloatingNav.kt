package com.senzaproject.senzaai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.senzaproject.senzaai.navigation.Dest
import com.senzaproject.senzaai.ui.theme.LocalGlass

@Composable
fun FloatingNavBar(current: Dest, onSelect: (Dest) -> Unit, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(32.dp)
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (g.dark) androidx.compose.ui.graphics.Color(0xE6161C44) else androidx.compose.ui.graphics.Color(0xE6FFFFFF),
                            if (g.dark) androidx.compose.ui.graphics.Color(0xE60D1233) else androidx.compose.ui.graphics.Color(0xD9F1F3FF)
                        )
                    )
                )
                .border(1.dp, Brush.linearGradient(listOf(g.borderHi, g.borderLo, g.borderHi.copy(alpha = 0.4f))), shape)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Dest.values().forEach { d ->
                val sel = d == current
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .then(
                            if (sel) Modifier.background(
                                Brush.linearGradient(listOf(g.accent.copy(alpha = if (g.dark) 0.55f else 0.95f), g.accent2.copy(alpha = if (g.dark) 0.45f else 0.85f)))
                            ) else Modifier
                        )
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(d) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val tint = if (sel) androidx.compose.ui.graphics.Color.White else g.textDim
                    Icon(d.icon, d.label, tint = tint, modifier = Modifier.size(22.dp))
                    Text(d.label, color = tint, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}
