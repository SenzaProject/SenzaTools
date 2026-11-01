package com.senzaproject.senzatools.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.senzaproject.senzatools.navigation.Dest
import com.senzaproject.senzatools.ui.theme.LocalGlass
import com.senzaproject.senzatools.ui.theme.LocalReducedEffects
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.senzaproject.senzatools.i18n.tr

/**
 * Rekam isi composable ini ke [layer] lalu gambar seperti biasa. Layer ini dipakai bar navigasi
 * sebagai "backdrop" (isi layar di belakang kaca) untuk blur dan pembiasan.
 */
fun Modifier.recordInto(layer: GraphicsLayer): Modifier = this.drawWithContent {
    layer.record(this, layoutDirection, androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) {
        this@drawWithContent.drawContent()
    }
    drawLayer(layer)
}

private val BarHeight = 64.dp
private val BarPad = 6.dp

/**
 * Bar navigasi liquid glass.
 *  - [backdrop] null atau "efek dikurangi" aktif -> bar datar (tanpa blur/pembiasan).
 *  - Android 13+ : lensa membiaskan isi di bawahnya (AGSL) dengan fringe pelangi di tepi.
 *  - Android 12  : lensa tanpa pembiasan (hanya blur + kilau).
 */
@Composable
fun FloatingNavBar(
    current: Dest,
    onSelect: (Dest) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GraphicsLayer? = null
) {
    if (backdrop == null || LocalReducedEffects.current) FlatNavBar(current, onSelect, modifier)
    else LiquidNavBar(current, onSelect, backdrop, modifier)
}

// ---------------------------------------------------------------------------------- Shader

private const val LENS_AGSL = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float edge;
uniform float strength;
uniform float dispersion;
uniform float mag;

float sdRR(float2 p, float2 hs, float r) {
    float2 q = abs(p) - (hs - float2(r, r));
    return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 fc) {
    float2 hs = size * 0.5;
    float2 p = fc - hs;
    float sd = sdRR(p, hs, radius);
    float2 g = float2(
        sdRR(p + float2(1.0, 0.0), hs, radius) - sdRR(p - float2(1.0, 0.0), hs, radius),
        sdRR(p + float2(0.0, 1.0), hs, radius) - sdRR(p - float2(0.0, 1.0), hs, radius));
    float2 n = g / max(length(g), 0.0001);

    float t = clamp(1.0 + sd / edge, 0.0, 1.0);
    float bend = t * t * t;

    float2 base = hs + p / mag;
    float2 off = -n * bend * strength;
    float2 lo = float2(0.5, 0.5);
    float2 hi = size - float2(0.5, 0.5);
    half4 cr = content.eval(clamp(base + off * (1.0 + dispersion), lo, hi));
    half4 cg = content.eval(clamp(base + off, lo, hi));
    half4 cb = content.eval(clamp(base + off * (1.0 - dispersion), lo, hi));
    return half4(cr.r, cg.g, cb.b, 1.0);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class LensShader {
    private val shader = RuntimeShader(LENS_AGSL)

    fun effect(w: Float, h: Float): RenderEffect {
        val m = minOf(w, h)
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("radius", m / 2f)
        shader.setFloatUniform("edge", m * 0.30f)
        shader.setFloatUniform("strength", m * 0.20f)
        shader.setFloatUniform("dispersion", 0.22f)
        shader.setFloatUniform("mag", 1.06f)
        return android.graphics.RenderEffect
            .createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
    }
}

// ---------------------------------------------------------------------------------- Bar liquid glass

@android.annotation.SuppressLint("NewApi")
@Composable
private fun LiquidNavBar(
    current: Dest,
    onSelect: (Dest) -> Unit,
    backdrop: GraphicsLayer,
    modifier: Modifier
) {
    val g = LocalGlass.current
    val density = LocalDensity.current
    val items = Dest.inNav
    val n = items.size
    val scope = rememberCoroutineScope()
    val barShape = RoundedCornerShape(BarHeight / 2)

    var barRoot by remember { mutableStateOf(Offset.Zero) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }

    val padPx = with(density) { BarPad.toPx() }
    val barH = with(density) { BarHeight.toPx() }
    val itemW = if (barSize.width > 0) (barSize.width - 2f * padPx) / n else 0f
    val lensW = itemW * 1.30f
    val lensH = barH * 1.20f
    val pillW = (itemW - with(density) { 4.dp.toPx() }).coerceAtLeast(1f)
    val pillH = barH - with(density) { 12.dp.toPx() }
    val sx0 = if (lensW > 0f) pillW / lensW else 1f
    val sy0 = pillH / lensH

    fun cxFor(i: Int) = padPx + itemW * (i + 0.5f)

    val lensCx = remember { Animatable(0f) }
    val active = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var placed by remember { mutableStateOf(false) }
    val shader = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) LensShader() else null }

    LaunchedEffect(current, itemW) {
        if (itemW <= 0f || dragging) return@LaunchedEffect
        val target = cxFor(items.indexOf(current).coerceAtLeast(0))
        if (!placed) { lensCx.snapTo(target); placed = true }
        else lensCx.animateTo(target, spring(dampingRatio = 0.72f, stiffness = 380f))
    }

    val barTint = if (g.dark) Color(0x8C12183A) else Color(0x8CFFFFFF)
    val lensVeil = if (g.dark) Color(0x2EFFFFFF) else Color(0x80FFFFFF)
    val blurPx = with(density) { 12.dp.toPx() }

    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .onGloballyPositioned { barRoot = it.positionInRoot(); barSize = it.size }
        ) {
            // 1) kaca bar: backdrop yang diburamkan
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        shape = barShape
                        clip = true
                        renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                    }
                    .drawBehind { translate(-barRoot.x, -barRoot.y) { drawLayer(backdrop) } }
            )
            // 2) tint + tepi
            Box(
                Modifier
                    .matchParentSize()
                    .background(barTint, barShape)
                    .border(
                        1.dp,
                        Brush.linearGradient(listOf(Color.White.copy(0.95f), Color.White.copy(0.25f), Color.White.copy(0.6f))),
                        barShape
                    )
            )
            if (itemW > 0f) {
                // 3) pil abu saat diam (item terpilih)
                Box(
                    Modifier
                        .offset { IntOffset((lensCx.value - pillW / 2f).roundToInt(), ((barH - pillH) / 2f).roundToInt()) }
                        .size(with(density) { pillW.toDp() }, with(density) { pillH.toDp() })
                        .graphicsLayer { alpha = 1f - active.value }
                        .background(g.text.copy(alpha = 0.10f), RoundedCornerShape(50))
                )
                // 4) ikon + label (warna normal, item terpilih beraksen)
                NavItemsRow(items, current, g.text, g.accent, Modifier.fillMaxSize().padding(BarPad))
                // 5) lensa
                val lensWdp = with(density) { lensW.toDp() }
                val lensHdp = with(density) { lensH.toDp() }
                Box(
                    Modifier
                        .offset { IntOffset((lensCx.value - lensW / 2f).roundToInt(), ((barH - lensH) / 2f).roundToInt()) }
                        .size(lensWdp, lensHdp)
                        .graphicsLayer {
                            val a = active.value
                            val stretch = (abs(lensCx.velocity) / 6000f).coerceAtMost(0.10f)
                            scaleX = (sx0 + (1f - sx0) * a) * (1f + stretch)
                            scaleY = (sy0 + (1f - sy0) * a) * (1f - stretch * 0.5f)
                            alpha = (a * 1.6f).coerceAtMost(1f)
                        }
                        .drawBehind {
                            // bayangan lembut di bawah lensa
                            val a = active.value
                            val cr = size.minDimension / 2f
                            for (i in 0..5) {
                                val grow = i * 2.dp.toPx()
                                drawRoundRect(
                                    Color.Black.copy(alpha = 0.022f * a),
                                    topLeft = Offset(-grow, 5.dp.toPx() - grow),
                                    size = Size(size.width + 2 * grow, size.height + 2 * grow),
                                    cornerRadius = CornerRadius(cr + grow)
                                )
                            }
                        }
                ) {
                    // lensa yang membiaskan: backdrop + veil + salinan item beraksen
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                shape = RoundedCornerShape(50)
                                clip = true
                                renderEffect = shader?.effect(size.width, size.height)
                            }
                            .drawBehind {
                                val lx = barRoot.x + lensCx.value - lensW / 2f
                                val ly = barRoot.y + (barH - lensH) / 2f
                                translate(-lx, -ly) { drawLayer(backdrop) }
                                drawRect(lensVeil)
                            }
                    ) {
                        NavItemsRow(
                            items, null, g.accent, g.accent,
                            Modifier
                                .layout { m, c ->
                                    val p = m.measure(
                                        Constraints.fixed((barSize.width - 2 * padPx).roundToInt(), (barH - 2 * padPx).roundToInt())
                                    )
                                    layout(c.maxWidth, c.maxHeight) {
                                        p.place(
                                            (padPx - (lensCx.value - lensW / 2f)).roundToInt(),
                                            (padPx - (barH - lensH) / 2f).roundToInt()
                                        )
                                    }
                                }
                        )
                    }
                    // kilau + tepi spekuler (tidak ikut dibiaskan)
                    Box(Modifier.fillMaxSize().drawBehind { drawLensRim() })
                }
                // 6) gestur: tap = pilih, geser = lensa mengikuti jari
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(itemW) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                dragging = true
                                fun idxAt(x: Float) = ((x - padPx) / itemW).toInt().coerceIn(0, n - 1)
                                scope.launch { active.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 420f)) }
                                scope.launch { lensCx.animateTo(cxFor(idxAt(down.position.x)), spring(0.75f, 380f)) }
                                var lastX = down.position.x
                                drag(down.id) { ch ->
                                    lastX = ch.position.x
                                    ch.consume()
                                    val target = lastX.coerceIn(cxFor(0), cxFor(n - 1))
                                    scope.launch { lensCx.animateTo(target, spring(dampingRatio = 1f, stiffness = 900f)) }
                                }
                                val idx = idxAt(lastX)
                                dragging = false
                                onSelect(items[idx])
                                scope.launch { lensCx.animateTo(cxFor(idx), spring(0.7f, 330f)) }
                                scope.launch { active.animateTo(0f, spring(0.8f, 300f)) }
                            }
                        }
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLensRim() {
    val w = size.width
    val h = size.height
    val cr = h / 2f
    val sw = 1.5.dp.toPx()
    // kilau cembung di bagian atas
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color.White.copy(0.45f), Color.Transparent), endY = h * 0.45f),
        topLeft = Offset(w * 0.14f, h * 0.05f),
        size = Size(w * 0.72f, h * 0.40f),
        cornerRadius = CornerRadius(cr)
    )
    // fringe pelangi tipis
    drawRoundRect(
        brush = Brush.sweepGradient(
            listOf(
                Color(0x5522D3EE), Color(0x55FF4DB8), Color(0x55FFE14D), Color(0x5522D3EE)
            ),
            center = Offset(w / 2f, h / 2f)
        ),
        topLeft = Offset(sw * 1.6f, sw * 1.6f),
        size = Size(w - sw * 3.2f, h - sw * 3.2f),
        cornerRadius = CornerRadius(cr - sw * 1.6f),
        style = Stroke(1.dp.toPx())
    )
    // tepi spekuler: terang di kiri-atas, redup di kanan-bawah
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(Color.White.copy(0.95f), Color.White.copy(0.15f), Color.White.copy(0.65f)),
            start = Offset(0f, 0f), end = Offset(w, h)
        ),
        topLeft = Offset(sw / 2f, sw / 2f),
        size = Size(w - sw, h - sw),
        cornerRadius = CornerRadius(cr - sw / 2f),
        style = Stroke(sw)
    )
}

@Composable
private fun NavItemsRow(
    items: Array<Dest>,
    selected: Dest?,
    tint: Color,
    selectedTint: Color,
    modifier: Modifier
) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        items.forEach { d ->
            val c = if (d == selected) selectedTint else tint
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(d.icon, tr(d.label), tint = c, modifier = Modifier.size(22.dp))
                Text(tr(d.label), color = c, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------------------------- Bar datar (cadangan)

@Composable
private fun FlatNavBar(current: Dest, onSelect: (Dest) -> Unit, modifier: Modifier = Modifier) {
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
                            if (g.dark) Color(0xE6161C44) else Color(0xE6FFFFFF),
                            if (g.dark) Color(0xE60D1233) else Color(0xD9F1F3FF)
                        )
                    )
                )
                .border(1.dp, Brush.linearGradient(listOf(g.borderHi, g.borderLo, g.borderHi.copy(alpha = 0.4f))), shape)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Dest.inNav.forEach { d ->
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
                    val tint = if (sel) Color.White else g.textDim
                    Icon(d.icon, tr(d.label), tint = tint, modifier = Modifier.size(22.dp))
                    Text(tr(d.label), color = tint, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}
