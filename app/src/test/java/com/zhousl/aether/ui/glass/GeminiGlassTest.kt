package com.zhousl.aether.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiGlassTest {
    @Test
    fun thinkingPalettesRotateAbcInOrder() {
        assertEquals(listOf("A", "B", "C", "A", "B"), (0..4).map { paletteForTurn(it).name })
        assertEquals("C", paletteForTurn(-1).name)
    }

    @Test
    fun p3ColoursAreConvertedToSrgbLikeGlassDemo() {
        // Bottom glow target measured on the K90 (P3 19,30,78) must not be used raw: sRGB value differs.
        val c = p3(19, 30, 78)
        val raw = floatArrayOf(19 / 255f, 30 / 255f, 78 / 255f)
        assertTrue(c.colorSpace.name.contains("sRGB"))
        assertTrue(kotlin.math.abs(c.blue - raw[2]) > 0.005f || kotlin.math.abs(c.red - raw[0]) > 0.005f)
        // Palette C emerald is outside sRGB: green channel clamps to <= 1 and red to >= 0.
        val emerald = GeminiPalettes[2].l
        assertTrue(emerald.red in 0f..1f && emerald.green in 0f..1f)
    }

    @Test
    fun symbolsUseTheMaterialSymbolsViewport() {
        val icons = listOf(
            GeminiSymbols.Menu, GeminiSymbols.EditNote, GeminiSymbols.Add, GeminiSymbols.ArrowUpward,
            GeminiSymbols.Stop, GeminiSymbols.ContentCopy, GeminiSymbols.Refresh, GeminiSymbols.Delete,
            GeminiSymbols.BarChart, GeminiSymbols.KeyboardArrowDown,
        )
        icons.forEach {
            assertEquals(960f, it.viewportWidth)
            assertEquals(960f, it.viewportHeight)
            assertTrue(it.name.startsWith("symbols.rounded."))
        }
    }
}
