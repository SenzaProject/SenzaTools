package com.senzaproject.senzaai.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tombol "liquid glass" (kaca cair):
 *  - badan kaca tembus pandang dengan gradasi warna + kilau permukaan atas,
 *  - tepi spekuler (rim light) yang terang di sudut kiri-atas dan redup di kanan-bawah,
 *  - cahaya dalam di bagian bawah,
 *  - saat ditekan: tombol "melesak" dengan pegas (bouncy) dan muncul pantulan cahaya
 *    cair tepat di titik sentuhan.
 *
 * Tanda tangan sengaja sama dengan GlassButton lama supaya mudah diganti.
 */
@Composable
fun LiquidGlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    cornerRadius: Dp = 28.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var touch by remember { mutableStateOf(Offset.Zero) }

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "lgScale"
    )
    val squash by animateFloatAsState(
        targetValue = if (pressed) 1.03f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "lgSquash"
    )
    val glow by animateFloatAsState(if (pressed) 1f else 0f, tween(220), label = "lgGlow")

    val tint = when {
        danger -> Color(0xFFFF5470)
        primary -> Color(0xFF4FC3FF)
        else -> Color(0xFFFFFFFF)
    }
    val tint2 = when {
        danger -> Color(0xFFFF8A5B)
        primary -> Color(0xFF8B7BFF)
        else -> Color(0xFFBFD3FF)
    }
    val bodyAlpha = if (primary || danger) 0.55f else 0.20f
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale * squash
                scaleY = scale / squash
                alpha = if (enabled) 1f else 0.45f
            }
            .shadow(
                elevation = if (pressed) 2.dp else 10.dp,
                shape = shape,
                ambientColor = tint.copy(alpha = 0.35f),
                spotColor = tint.copy(alpha = 0.45f)
            )
            .clip(shape)
            .drawBehind {
                val w = size.width
                val h = size.height
                val cr = minOf(cornerRadius.toPx(), h / 2f)
                val radius = CornerRadius(cr, cr)

                // 1) badan kaca: gradasi diagonal tembus pandang
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(tint.copy(alpha = bodyAlpha), tint2.copy(alpha = bodyAlpha * 0.55f)),
                        start = Offset(0f, 0f),
                        end = Offset(w, h)
                    ),
                    cornerRadius = radius
                )
                // 2) kilau permukaan atas (meniru cembung kaca)
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.04f), Color.Transparent),
                        startY = 0f,
                        endY = h * 0.65f
                    ),
                    topLeft = Offset(h * 0.10f, h * 0.06f),
                    size = Size(w - h * 0.20f, h * 0.50f),
                    cornerRadius = CornerRadius(cr * 0.8f, cr * 0.8f)
                )
                // 3) cahaya dalam di bagian bawah (cahaya terbias)
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(tint2.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(w / 2f, h * 1.05f),
                        radius = w * 0.55f
                    ),
                    cornerRadius = radius
                )
                // 4) pantulan cair mengikuti titik sentuhan
                if (glow > 0.01f) {
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.55f * glow), Color.White.copy(alpha = 0.12f * glow), Color.Transparent),
                            center = touch,
                            radius = maxOf(w, h) * 0.55f
                        ),
                        cornerRadius = radius
                    )
                }
                // 5) tepi spekuler (rim light)
                val sw = 1.4.dp.toPx()
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.50f)),
                        start = Offset(0f, 0f),
                        end = Offset(w, h)
                    ),
                    topLeft = Offset(sw / 2f, sw / 2f),
                    size = Size(w - sw, h - sw),
                    cornerRadius = CornerRadius(cr - sw / 2f, cr - sw / 2f),
                    style = Stroke(width = sw)
                )
            }
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
                this.onClick(label = null) { if (enabled) { onClick(); true } else false }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = { off ->
                        touch = off
                        val press = PressInteraction.Press(off)
                        interaction.emit(press)
                        val released = tryAwaitRelease()
                        interaction.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                    },
                    onTap = { onClick() }
                )
            }
            .defaultMinSize(minHeight = 46.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1f), 4f)
            )
        )
    }
}
