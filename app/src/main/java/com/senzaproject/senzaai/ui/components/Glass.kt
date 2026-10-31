package com.senzaproject.senzaai.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.senzaproject.senzaai.domain.model.Certainty
import com.senzaproject.senzaai.domain.model.InfoSection
import com.senzaproject.senzaai.domain.model.Severity
import com.senzaproject.senzaai.ui.theme.LocalGlass
import com.senzaproject.senzaai.ui.theme.LocalReducedEffects
import kotlin.math.max
import kotlin.math.min

// ------------------------------------------------------------------ Latar belakang

@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    val g = LocalGlass.current
    val reduced = LocalReducedEffects.current
    val phase = if (reduced) {
        0f
    } else {
        val t = rememberInfiniteTransition(label = "bg")
        t.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse),
            label = "bgPhase"
        ).value
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(g.bgTop, g.bgBottom)))
            .drawBehind {
                val w = size.width
                val h = size.height
                val a = if (g.dark) 0.55f else 0.65f
                fun blob(c: Color, cx: Float, cy: Float, r: Float) {
                    drawCircle(
                        brush = Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), center = Offset(cx, cy), radius = r),
                        radius = r, center = Offset(cx, cy)
                    )
                }
                blob(g.blob1, w * (0.15f + 0.25f * phase), h * (0.12f + 0.06f * phase), w * 0.75f)
                blob(g.blob2, w * (0.95f - 0.25f * phase), h * (0.42f + 0.08f * phase), w * 0.70f)
                blob(g.blob3, w * (0.25f + 0.2f * phase), h * (0.85f - 0.08f * phase), w * 0.80f)
            },
        content = content
    )
}

// ------------------------------------------------------------------ Util

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

// ------------------------------------------------------------------ Teks

@Composable
fun Title(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = LocalGlass.current.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = LocalGlass.current.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier, dim: Boolean = false, color: Color? = null, maxLines: Int = Int.MAX_VALUE) {
    val g = LocalGlass.current
    Text(text, modifier, color = color ?: if (dim) g.textDim else g.text, fontSize = 14.sp, lineHeight = 20.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(text, modifier, color = color ?: LocalGlass.current.textDim, fontSize = 12.sp, lineHeight = 16.sp)
}

@Composable
fun Mono(text: String, modifier: Modifier = Modifier, size: Int = 12) {
    Text(text, modifier, color = LocalGlass.current.text, fontSize = size.sp, lineHeight = (size + 5).sp, fontFamily = FontFamily.Monospace)
}

@Composable
fun ScreenScaffold(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Column {
            Title(title)
            if (subtitle != null) Caption(subtitle)
        }
        content()
    }
}

// ------------------------------------------------------------------ Kartu & tombol

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(24.dp)
    val fill = if (strong) g.glassFillStrong else g.glassFill
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(fill, fill.copy(alpha = fill.alpha * 0.55f))))
            .border(1.dp, Brush.linearGradient(listOf(g.borderHi, g.borderLo, g.borderHi.copy(alpha = 0.35f))), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
    icon: ImageVector? = null
) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(16.dp)
    val accent = if (danger) g.bad else g.accent
    val bg = if (primary) Brush.linearGradient(listOf(accent, if (danger) g.bad.copy(alpha = 0.8f) else g.accent2))
    else Brush.linearGradient(listOf(g.glassFillStrong, g.glassFill))
    val fg = if (primary) Color.White else if (danger) g.bad else g.text
    Row(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, if (primary) Color.White.copy(alpha = 0.35f) else g.borderLo.copy(alpha = 0.8f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val g = LocalGlass.current
    val bg by animateColorAsState(if (selected) g.accent.copy(alpha = if (g.dark) 0.45f else 0.9f) else g.glassFill, label = "chip")
    val fg = if (selected) (if (g.dark) g.text else Color.White) else g.text
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (selected) g.borderHi.copy(alpha = 0.5f) else g.borderLo, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = fg, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun ChipRow(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEachIndexed { i, s -> Chip(s, i == selected) { onSelect(i) } }
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun severityColor(s: Severity): Color {
    val g = LocalGlass.current
    return when (s) {
        Severity.INFO -> g.accent2
        Severity.WARN -> g.warn
        Severity.ERROR -> g.bad
    }
}

@Composable
fun SeverityPill(s: Severity) = StatusPill(
    when (s) {
        Severity.INFO -> "INFO"
        Severity.WARN -> "PERHATIAN"
        Severity.ERROR -> "MASALAH"
    }, severityColor(s)
)

@Composable
fun CertaintyPill(c: Certainty) {
    val g = LocalGlass.current
    StatusPill(
        c.label, when (c) {
            Certainty.PROVEN -> g.good
            Certainty.PROBABLE -> g.warn
            Certainty.UNKNOWN -> g.textDim
        }
    )
}

// ------------------------------------------------------------------ Panel info

@Composable
fun InfoSectionCard(section: InfoSection, startExpanded: Boolean = true) {
    ExpandableCard(section.title, startExpanded) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                section.rows.forEach { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(r.label, Modifier.weight(0.42f), color = LocalGlass.current.textDim, fontSize = 13.sp, lineHeight = 18.sp)
                        Text(r.value, Modifier.weight(0.58f), color = LocalGlass.current.text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
fun ExpandableCard(title: String, startExpanded: Boolean = false, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(startExpanded) }
    val g = LocalGlass.current
    GlassCard {
        Row(
            Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = !open },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Heading(title, Modifier.weight(1f))
            if (trailing != null) {
                Caption(trailing)
                Spacer(Modifier.width(8.dp))
            }
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = g.textDim)
        }
        if (open) content()
    }
}

/** Daftar dengan pemotongan: tampilkan [initial] item dulu, tombol untuk memuat lebih banyak. */
@Composable
fun <T> LimitedList(items: List<T>, initial: Int = 40, step: Int = 80, row: @Composable (T) -> Unit) {
    var shown by remember(items) { mutableStateOf(initial) }
    val n = min(shown, items.size)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until n) row(items[i])
        if (items.size > n) {
            GlassButton("Tampilkan lebih banyak (${items.size - n} tersisa)", { shown = shown + step }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.4f), color = LocalGlass.current.textDim, fontSize = 13.sp, lineHeight = 18.sp)
        Text(value, Modifier.weight(0.6f), color = LocalGlass.current.text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun GlassDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalGlass.current.borderLo.copy(alpha = 0.5f)))
}

// ------------------------------------------------------------------ Input & dialog

@Composable
fun GlassTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    password: Boolean = false,
    mono: Boolean = false,
    minLines: Int = 1,
    keyboard: KeyboardType = KeyboardType.Text
) {
    val g = LocalGlass.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        textStyle = androidx.compose.ui.text.TextStyle(
            color = g.text, fontSize = 14.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        ),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = g.accent, unfocusedBorderColor = g.borderLo,
            focusedLabelColor = g.accent, unfocusedLabelColor = g.textDim,
            cursorColor = g.accent, focusedTextColor = g.text, unfocusedTextColor = g.text,
            focusedContainerColor = g.glassFill, unfocusedContainerColor = g.glassFill
        )
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Lanjutkan",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val g = LocalGlass.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (g.dark) Color(0xFF151B3D) else Color(0xFFF4F6FF),
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, color = g.text, fontWeight = FontWeight.SemiBold) },
        text = { Text(message, color = g.text, fontSize = 14.sp, lineHeight = 20.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText, color = if (danger) g.bad else g.accent, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal", color = g.textDim) } }
    )
}

// ------------------------------------------------------------------ Gauge & grafik

@Composable
fun RingGauge(value: Float?, label: String, sub: String? = null, modifier: Modifier = Modifier, size: Dp = 92.dp) {
    val g = LocalGlass.current
    val target = ((value ?: 0f) / 100f).coerceIn(0f, 1f)
    val progress by animateFloatAsState(target, tween(600), label = "ring")
    val color = when {
        value == null -> g.textDim
        value >= 90f -> g.bad
        value >= 75f -> g.warn
        else -> g.accent2
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = this.size.minDimension * 0.11f, cap = StrokeCap.Round)
                val inset = stroke.width / 2
                val arcSize = Size(this.size.width - stroke.width, this.size.height - stroke.width)
                drawArc(g.glassFillStrong, 135f, 270f, false, Offset(inset, inset), arcSize, style = stroke)
                if (value != null) {
                    drawArc(Brush.sweepGradient(listOf(color.copy(alpha = 0.6f), color)), 135f, 270f * progress, false, Offset(inset, inset), arcSize, style = stroke)
                }
            }
            Text(if (value == null) "—" else "${value.toInt()}%", color = g.text, fontSize = (size.value * 0.22f).sp, fontWeight = FontWeight.Bold)
        }
        Text(label, color = g.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (sub != null) Caption(sub)
    }
}

/** Grafik garis sederhana. Nilai null dilewati (garis terputus). */
@Composable
fun LineChart(
    series: List<Pair<String, List<Float?>>>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    minY: Float? = null,
    maxY: Float? = null,
    unit: String = ""
) {
    val g = LocalGlass.current
    val all = series.flatMap { it.second }.filterNotNull()
    val lo = minY ?: (all.minOrNull() ?: 0f)
    var hi = maxY ?: (all.maxOrNull() ?: 1f)
    if (hi - lo < 1f) hi = lo + 1f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            series.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors[i % colors.size]))
                    Caption(s.first + (s.second.lastOrNull { it != null }?.let { " ${"%.1f".format(it)}$unit" } ?: ""))
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val w = size.width
            val h = size.height
            for (i in 0..3) {
                val y = h * i / 3f
                drawLine(g.borderLo.copy(alpha = 0.6f), Offset(0f, y), Offset(w, y), 1f)
            }
            series.forEachIndexed { si, (_, data) ->
                if (data.size < 2) return@forEachIndexed
                val c = colors[si % colors.size]
                val path = Path()
                var pen = false
                data.forEachIndexed { i, v ->
                    if (v == null) {
                        pen = false
                        return@forEachIndexed
                    }
                    val x = w * i / max(1, data.size - 1)
                    val y = h - h * ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
                    if (!pen) {
                        path.moveTo(x, y)
                        pen = true
                    } else path.lineTo(x, y)
                }
                drawPath(path, c, style = Stroke(width = 3f, cap = StrokeCap.Round))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption("${"%.0f".format(lo)}$unit")
            Caption("${"%.0f".format(hi)}$unit")
        }
    }
}

@Composable
fun BarMeter(percent: Float, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val p by animateFloatAsState((percent / 100f).coerceIn(0f, 1f), tween(500), label = "bar")
    val color = if (percent >= 90f) g.bad else if (percent >= 75f) g.warn else g.accent2
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(g.glassFillStrong)) {
        Box(Modifier.fillMaxSize(p).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color))))
    }
}

@Composable
fun Spinner(text: String) {
    val g = LocalGlass.current
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(g.accent.copy(alpha = a)))
        Caption(text)
    }
}

@Composable
fun ErrorCard(message: String) {
    val g = LocalGlass.current
    GlassCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("ERROR", g.bad)
            Body(message, Modifier.weight(1f))
        }
    }
}
