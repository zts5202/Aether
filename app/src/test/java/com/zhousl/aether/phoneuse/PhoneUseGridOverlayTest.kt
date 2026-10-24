package com.zhousl.aether.phoneuse

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke test for the host grid painter.
 * Robolectric's Canvas does not rasterize strokes, so pixel colors are not asserted.
 * Line positions and tap points share [phoneUseGridCellPixelRange], covered by [PhoneUseGridTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PhoneUseGridOverlayTest {
    @Test fun gridOverlayPaintsEveryCellWithoutThrowing() {
        val bitmap = Bitmap.createBitmap(360, 240, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val drawn = drawPhoneUseGrid(bitmap)
        assertEquals(360, drawn.width)
        assertEquals(240, drawn.height)
        assertEquals(8 * 12, PhoneUseGridRows * PhoneUseGridCols)
        val (x, y) = phoneUseGridCellCenter(PhoneUseGridCell(3, 5), drawn.width, drawn.height)
        val xRange = phoneUseGridCellPixelRange(5, PhoneUseGridCols, drawn.width)
        val yRange = phoneUseGridCellPixelRange(3, PhoneUseGridRows, drawn.height)
        assertEquals(true, x in xRange && y in yRange)
    }
}
