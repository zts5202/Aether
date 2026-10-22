package com.zhousl.aether.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces

// Kept free of the Kyant backdrop classes (Java 21 bytecode) so plain JVM 17 unit tests can load it.

object GeminiColors {
    val TextMain = Color(0xFFE0E0E0)
    val TextDim = Color(0xFF9AA0A6)
    val Grey20 = Color(20, 20, 20)
    val SendGrey = Color(0xFF3C4043)
}

/** Reference colours are Display-P3 screenshot values; convert to sRGB once (see GlassDemo 0.4.2 colour fix). */
fun p3(r: Int, g: Int, b: Int): Color =
    Color(r / 255f, g / 255f, b / 255f, 1f, ColorSpaces.DisplayP3).convert(ColorSpaces.Srgb)

data class GlowPalette(
    val name: String, val l: Color, val c: Color, val r: Color, val m: Color, val a: Float, val y0: Float,
    val lateL: Color = l, val lateC: Color = c, val lateR: Color = r, val lateM: Color = m, val wx: Float = 0.28f,
)

val GeminiPalettes: List<GlowPalette> by lazy {
    listOf(
        GlowPalette("A", p3(33, 56, 117), p3(52, 80, 161), p3(29, 45, 90), p3(0, 0, 4), 11f, 0f,
            p3(39, 88, 107), p3(60, 108, 178), p3(37, 58, 119), p3(1, 13, 16)),
        GlowPalette("B", p3(80, 48, 135), p3(201, 131, 127), p3(133, 91, 47), p3(4, 0, 27), 13f, 0.04f),
        GlowPalette("C", p3(0, 165, 100), p3(232, 178, 0), p3(10, 150, 78), p3(0, 26, 14), 12f, 0.02f,
            p3(0, 172, 108), p3(238, 186, 12), p3(18, 158, 72), p3(0, 30, 16), wx = 0.2f),
    )
}

/** Palette for the n-th thinking turn: A, B, C, A, ... */
fun paletteForTurn(turn: Int): GlowPalette = GeminiPalettes[Math.floorMod(turn, GeminiPalettes.size)]
