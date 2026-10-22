package com.zhousl.aether.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import kotlinx.coroutines.launch

/*
 * Gemini-style chat chrome ported from GlassDemo 0.4.5 (github.com/zts5202/GlassDemo, GeminiApp.kt).
 * Colours, shaders and glass parameters are copied 1:1; only the plumbing is adapted to Aether.
 *
 * Fallbacks: AGSL (RuntimeShader) needs API 33. Below that the glows are plain gradients and the stars a soft tint.
 * The Kyant backdrop library itself degrades: lens refraction needs API 33, blur API 31, below 31 only the surface tint.
 */

private const val GLOW = """
uniform float2 res;
uniform float t;
uniform float3 cl; uniform float3 cc; uniform float3 cr; uniform float3 cm;
uniform float A; uniform float Y0; uniform float wx;
uniform float reveal; uniform float alpha; uniform float br;
float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }
half4 main(float2 p) {
  float2 uv = p / res;
  float x = uv.x, y = uv.y;
  float s = 0.05 * sin(t * 0.35);
  float g0 = exp(-pow((x - (0.08 + s)) / wx, 2.0));
  float g1 = exp(-pow((x - (0.50 - s * 0.6 + 0.03 * sin(t * 0.5))) / wx, 2.0));
  float g2 = exp(-pow((x - (0.92 + s)) / wx, 2.0));
  float3 top = (cl * g0 + cc * g1 + cr * g2) / (g0 + g1 + g2);
  float yy = y - 0.01 * sin(t * 0.6 + x * 4.0);
  float it = exp(-A * (1.0 - 0.14 * br) * pow(max(yy - Y0, 0.0), 2.0));
  float im = exp(-(pow((x - 0.5) / 0.35, 2.0) + pow((yy - 0.36) / 0.12, 2.0)));
  float3 col = (top * it + cm * im) * (0.9 + 0.1 * br);
  float edge = reveal * 0.55;
  col *= 1.0 - smoothstep(edge - 0.12, edge, y);
  col *= alpha;
  col += (hash(p + fract(t)) - 0.5) / 255.0;
  return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

/** Seconds since start; read ONLY in draw lambdas so frames never recompose the screen. */
@Composable
fun rememberBreathTime(running: Boolean = true): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val start = withInfiniteAnimationFrameNanos { it } - (t.floatValue * 1e9f).toLong()
        while (true) withInfiniteAnimationFrameNanos { t.floatValue = (it - start) / 1e9f }
    }
    return t
}

/** Thinking glow from the top (breathing 3.5 s). The shader runs only while visible. */
@Composable
fun ThinkingGlow(active: Boolean, turn: Int, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            reveal.snapTo(0f)
            launch { alpha.animateTo(1f, tween(500)) }
            reveal.animateTo(1f, tween(1800, easing = FastOutSlowInEasing))
        } else {
            alpha.animateTo(0f, tween(1200))
        }
    }
    val activeState = rememberUpdatedState(active)
    val running by remember { derivedStateOf { activeState.value || alpha.value > 0f } }
    val time = rememberBreathTime(running)
    if (!running) return
    val pal = paletteForTurn(turn)
    if (Build.VERSION.SDK_INT >= 33) {
        GlowShader(pal, time, { alpha.value }, { reveal.value }, modifier.testTag("thinkingGlow"))
    } else {
        Canvas(modifier.testTag("thinkingGlow")) {
            val a = alpha.value
            val b = 0.9f + 0.1f * kotlin.math.sin(time.value * 2f * Math.PI.toFloat() / 3.5f)
            drawRect(
                Brush.verticalGradient(
                    0f to pal.c.copy(alpha = 0.8f * a * b),
                    0.25f to pal.l.copy(alpha = 0.5f * a * b),
                    0.5f to Color.Transparent,
                )
            )
        }
    }
}

private fun Color.v3(): FloatArray = floatArrayOf(red, green, blue)
private fun lerpC(a: Color, b: Color, k: Float) =
    Color(a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k)

@RequiresApi(33)
@Composable
private fun GlowShader(pal: GlowPalette, time: State<Float>, alpha: () -> Float, reveal: () -> Float, modifier: Modifier) {
    val shader = remember { RuntimeShader(GLOW) }
    val brush = remember { ShaderBrush(shader) }
    val layer = rememberGraphicsLayer()
    Canvas(modifier) {
        val t = time.value
        val k = ((t - 1f) / 4f).coerceIn(0f, 1f)
        fun u(n: String, a: Color, b: Color) {
            val c = lerpC(a, b, k).v3(); shader.setFloatUniform(n, c[0], c[1], c[2])
        }
        u("cl", pal.l, pal.lateL); u("cc", pal.c, pal.lateC); u("cr", pal.r, pal.lateR); u("cm", pal.m, pal.lateM)
        val scale = 8f
        val small = IntSize((size.width / scale).toInt().coerceAtLeast(1), (size.height / scale).toInt().coerceAtLeast(1))
        shader.setFloatUniform("res", small.width.toFloat(), small.height.toFloat())
        shader.setFloatUniform("t", t)
        shader.setFloatUniform("A", pal.a); shader.setFloatUniform("Y0", pal.y0); shader.setFloatUniform("wx", pal.wx)
        shader.setFloatUniform("br", kotlin.math.sin(t * 2f * Math.PI.toFloat() / 3.5f))
        shader.setFloatUniform("reveal", reveal()); shader.setFloatUniform("alpha", alpha())
        layer.compositingStrategy = CompositingStrategy.Offscreen
        layer.pivotOffset = Offset.Zero
        layer.scaleX = scale; layer.scaleY = scale
        layer.record(small) { drawRect(brush) }
        drawLayer(layer)
    }
}

/** Deep-blue bottom glow (idle breathing, 4 s period). */
@Composable
fun BottomBlueGlow(modifier: Modifier = Modifier) {
    val time = rememberBreathTime()
    val v = remember { Brush.verticalGradient(0.5f to Color.Black, 0.7f to p3(6, 11, 28), 0.85f to p3(19, 30, 78), 1f to p3(25, 40, 106)) }
    val rc = remember { p3(20, 32, 88).copy(alpha = 0.45f) }
    Canvas(modifier.testTag("bottomBlueGlow")) {
        val b = 0.92f + 0.08f * kotlin.math.sin(time.value * 2f * Math.PI.toFloat() / 4f)
        drawRect(v, alpha = b)
        drawRect(Brush.radialGradient(listOf(rc, Color.Transparent), center = Offset(0f, size.height * 0.9f), radius = size.width * 0.9f), alpha = b)
    }
}

private const val STARS = """
uniform float2 res;
uniform float t;
uniform float cell;
uniform float alpha;
uniform float typing;
half4 main(float2 p) {
  float2 uv = p / res;
  float hb = 1.0 - uv.y;
  float ga = (1.0 - smoothstep(0.0, 1.0, hb)) * 0.35;
  half3 back = mix(half3(0.05, 0.10, 0.35), half3(0.22, 0.10, 0.40), smoothstep(0.0, 0.6, hb));
  float2 g = floor(p / cell);
  float2 f = fract(p / cell) - 0.5;
  float h = 1.0 - (g.y + 0.5) * cell / res.y;
  float fade = pow(clamp(1.0 - h, 0.0, 1.0), 1.4);
  float surf = 0.05 * sin(g.x * cell / res.x * 9.0 + t * 1.3);
  float w = exp(-pow((h + surf - fract(t / 1.6)) * 6.0, 2.0));
  float tw = 1.0 + typing * 0.35 * sin(t * 18.0 + g.x * 2.1 + g.y * 3.7);
  float r = mix(0.12, 0.47, fade) * (0.9 + 0.15 * w);
  float2 a = abs(f);
  float s = sqrt(a.x) + sqrt(a.y);
  float star = 1.0 - smoothstep(r * 0.8, r, s);
  float halo = exp(-dot(f, f) * 40.0) * 0.35 * fade;
  float b = clamp((star + halo) * fade * (0.7 + 0.3 * w) * tw, 0.0, 1.0);
  half3 sc = half3(0.482, 0.424, 1.0);
  half3 col = back * ga + sc * b;
  float outA = clamp(ga + b, 0.0, 1.0) * alpha;
  return half4(col * alpha, outA);
}
"""

/** Purple-blue four-point star grid above the composer while it is focused. */
@Composable
fun InputStars(visible: Boolean, lastTypedSeconds: State<Float>, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(visible) { alpha.animateTo(if (visible) 1f else 0f, tween(if (visible) 350 else 500)) }
    val vis = rememberUpdatedState(visible)
    val show by remember { derivedStateOf { vis.value || alpha.value > 0f } }
    val time = rememberBreathTime(show)
    if (!show) return
    if (Build.VERSION.SDK_INT >= 33) {
        StarsShader(time, lastTypedSeconds, { alpha.value }, modifier.testTag("inputStars"))
    } else {
        Canvas(modifier.testTag("inputStars")) {
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF3A2A88).copy(alpha = 0.45f * alpha.value))))
        }
    }
}

@RequiresApi(33)
@Composable
private fun StarsShader(time: State<Float>, lastTyped: State<Float>, alpha: () -> Float, modifier: Modifier) {
    val shader = remember { RuntimeShader(STARS) }
    val brush = remember { ShaderBrush(shader) }
    Canvas(modifier) {
        shader.setFloatUniform("res", size.width, size.height)
        shader.setFloatUniform("t", time.value)
        shader.setFloatUniform("cell", 14.dp.toPx())
        shader.setFloatUniform("alpha", alpha())
        val since = System.nanoTime() / 1e9f - lastTyped.value
        shader.setFloatUniform("typing", kotlin.math.exp(-since * 3f).coerceIn(0f, 1f))
        drawRect(brush)
    }
}

fun nowSeconds(): Float = System.nanoTime() / 1e9f

/** Backdrop holding the glows; null outside the chat screen, where [geminiGlass] falls back to a flat tint. */
val LocalGeminiBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 0.4.2/0.4.4 rim: 1dp highlight with default (soft) blur. */
private val SoftRimHighlight = Highlight(width = 1.dp)
private val GlassSurface = Color.White.copy(alpha = 0.05f)
private val LegacyTint = Color(0xFF2A2550).copy(alpha = 0.80f)

/**
 * Liquid glass exactly as GlassDemo 0.4.4/0.4.5 `glassOnBlack`: saturation 1.5, blur 4dp, lens refraction
 * (edge band [edge], amount 28dp, depth effect, optional chromatic dispersion), soft 1dp rim, no shadow.
 */
@Composable
fun Modifier.geminiGlass(shape: Shape, edge: Dp = 10.dp, dispersion: Boolean = false): Modifier {
    val backdrop = LocalGeminiBackdrop.current
        ?: return this.clip(shape).background(Color.White.copy(alpha = 0.08f)).border(1.dp, Color.White.copy(alpha = 0.10f), shape)
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1.5f)
            blur(4.dp.toPx())
            lens(refractionHeight = edge.toPx(), refractionAmount = 28.dp.toPx(), depthEffect = true, chromaticAberration = dispersion)
        },
        highlight = { SoftRimHighlight },
        shadow = null,
        onDrawSurface = {
            drawRect(GlassSurface)
            if (Build.VERSION.SDK_INT < 31) drawRect(LegacyTint)
        },
    )
}

/** Gemini-like four-colour sparkle drawn as a vector path (no emoji, no brand asset). */
@Composable
fun GeminiSparkle(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val c = Offset(w / 2, w / 2)
        val p = Path().apply {
            moveTo(c.x, 0f)
            quadraticTo(c.x, c.y, w, c.y); quadraticTo(c.x, c.y, c.x, w)
            quadraticTo(c.x, c.y, 0f, c.y); quadraticTo(c.x, c.y, c.x, 0f); close()
        }
        drawPath(p, Brush.sweepGradient(listOf(Color(0xFF4285F4), Color(0xFF9B72CB), Color(0xFFD96570), Color(0xFFF4B400), Color(0xFF34A853), Color(0xFF4285F4)), c))
    }
}
