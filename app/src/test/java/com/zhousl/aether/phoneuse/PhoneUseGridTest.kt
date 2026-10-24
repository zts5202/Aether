package com.zhousl.aether.phoneuse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUseGridTest {
    @Test fun cellCentersMatchTheEightByTwelveGrid() {
        assertEquals(8, PhoneUseGridRows)
        assertEquals(12, PhoneUseGridCols)
        // 1080x2400. r3c5 center is (5.5 columns, 3.5 rows).
        assertEquals(495 to 1050, phoneUseGridCellCenter(PhoneUseGridCell(3, 5), 1080, 2400))
        assertEquals(45 to 150, phoneUseGridCellCenter(PhoneUseGridCell(0, 0), 1080, 2400))
        assertEquals(1035 to 2250, phoneUseGridCellCenter(PhoneUseGridCell(7, 11), 1080, 2400))
        assertEquals(42 to 63, phoneUseGridCellCenter(PhoneUseGridCell(0, 0), 1000, 1000))
    }

    @Test fun everyCellCenterStaysInsideItsCell() {
        for (width in listOf(12, 720, 1001, 1080)) {
            for (height in listOf(8, 1280, 2003, 2400)) {
                for (row in 0 until PhoneUseGridRows) {
                    for (col in 0 until PhoneUseGridCols) {
                        val (x, y) = phoneUseGridCellCenter(PhoneUseGridCell(row, col), width, height)
                        val xRange = phoneUseGridCellPixelRange(col, PhoneUseGridCols, width)
                        val yRange = phoneUseGridCellPixelRange(row, PhoneUseGridRows, height)
                        assertTrue("$width x $height r${row}c${col} x=$x not in $xRange", x in xRange)
                        assertTrue("$width x $height r${row}c${col} y=$y not in $yRange", y in yRange)
                    }
                }
            }
        }
    }

    @Test fun parsesCellIdsWithoutAcceptingPixels() {
        assertEquals(PhoneUseGridCell(3, 5), parsePhoneUseGridCell("r3c5"))
        assertEquals(PhoneUseGridCell(3, 5), parsePhoneUseGridCell("R3C5"))
        assertEquals(PhoneUseGridCell(3, 5), parsePhoneUseGridCell("r3 c5"))
        assertEquals(PhoneUseGridCell(3, 5), parsePhoneUseGridCell("3,5"))
        assertEquals(PhoneUseGridCell(3, 5), parsePhoneUseGridCell("3，5"))
        assertEquals(PhoneUseGridCell(0, 11), parsePhoneUseGridCell("r0c11"))
        val pixels = assertThrowsMessage { parsePhoneUseGridCell("1080,2400") }
        assertTrue(pixels.contains("0..1000"))
        assertTrue(pixels.contains("r3c5"))
        val normalized = assertThrowsMessage { parsePhoneUseGridCell("500,500") }
        assertTrue(normalized.contains("pixels") || normalized.contains("0..1000"))
        assertThrowsMessage { parsePhoneUseGridCell("r8c0") }
        assertThrowsMessage { parsePhoneUseGridCell("r0c12") }
    }

    @Test fun readsCellArgumentsAndIgnoresMissingOnes() {
        assertNull(phoneUseGridCellFromArguments(JSONObject().put("eid", "e1")))
        assertEquals(
            PhoneUseGridCell(3, 5),
            phoneUseGridCellFromArguments(JSONObject().put("cell", "r3c5")),
        )
        assertEquals(
            PhoneUseGridCell(3, 5),
            phoneUseGridCellFromArguments(JSONObject().put("grid_id", "3,5")),
        )
        assertEquals(
            PhoneUseGridCell(3, 5),
            phoneUseGridCellFromArguments(JSONObject().put("row", 3).put("col", 5)),
        )
        assertEquals(
            PhoneUseGridCell(3, 5),
            phoneUseGridCellFromArguments(JSONObject().put("cell", "r3c5").put("row", 3).put("col", 5)),
        )
        val mismatch = assertThrowsMessage {
            phoneUseGridCellFromArguments(JSONObject().put("cell", "r3c5").put("row", 1).put("col", 1))
        }
        assertTrue(mismatch.contains("does not match"))
        val fraction = assertThrowsMessage {
            phoneUseGridCellFromArguments(JSONObject().put("row", 3.5).put("col", 1))
        }
        assertTrue(fraction.contains("0..1000"))
    }

    @Test fun gridContractDescribesTheHostMapping() {
        val contract = phoneUseGridContract()
        assertEquals(8, contract.getInt("rows"))
        assertEquals(12, contract.getInt("cols"))
        assertEquals("r3c5", contract.getString("example"))
        assertEquals("forbidden", contract.getString("coordinates"))
        val stdout = phoneUseGridStdout(treeUsable = false, explicit = false)
        assertTrue(stdout.contains("commitText"))
        assertTrue(stdout.contains("r3c5"))
        assertTrue(stdout.contains("0..1000"))
        assertTrue(phoneUseGridStdout(treeUsable = true, explicit = true).contains("eid"))
    }

    private fun assertThrowsMessage(block: () -> Unit): String {
        try {
            block()
        } catch (error: IllegalArgumentException) {
            return error.message.orEmpty()
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
