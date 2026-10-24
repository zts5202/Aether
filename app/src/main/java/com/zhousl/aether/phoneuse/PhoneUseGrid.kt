package com.zhousl.aether.phoneuse

import kotlin.math.roundToInt
import org.json.JSONObject

/** Real-screen fallback grid. Eight rows by twelve columns, zero-based. */
internal const val PhoneUseGridRows = 8
internal const val PhoneUseGridCols = 12

internal data class PhoneUseGridCell(val row: Int, val col: Int) {
    val id: String get() = phoneUseGridLabel(row, col)

    init {
        require(row in 0 until PhoneUseGridRows) { "Grid row $row is outside 0..${PhoneUseGridRows - 1}." }
        require(col in 0 until PhoneUseGridCols) { "Grid col $col is outside 0..${PhoneUseGridCols - 1}." }
    }
}

internal fun phoneUseGridLabel(row: Int, col: Int): String = "r${row}c${col}"

/**
 * Inclusive pixel span of one grid strip.
 * The same ranges are used to draw labels and to place a tap, so the gesture
 * lands inside the cell the model reads.
 */
internal fun phoneUseGridCellPixelRange(index: Int, divisions: Int, extent: Int): IntRange {
    val safeExtent = extent.coerceAtLeast(1)
    val start = (index.toLong() * safeExtent / divisions).toInt().coerceIn(0, safeExtent - 1)
    val endExclusive = ((index + 1).toLong() * safeExtent / divisions).toInt().coerceIn(start + 1, safeExtent)
    return start until endExclusive
}

/** Center of [cell] in display pixels. This is host-side only; the model never sends it. */
internal fun phoneUseGridCellCenter(
    cell: PhoneUseGridCell,
    displayWidth: Int,
    displayHeight: Int,
): Pair<Int, Int> {
    val x = phoneUseGridStripCenter(cell.col, PhoneUseGridCols, displayWidth)
    val y = phoneUseGridStripCenter(cell.row, PhoneUseGridRows, displayHeight)
    return x to y
}

internal fun phoneUseGridStripCenter(index: Int, divisions: Int, extent: Int): Int {
    val range = phoneUseGridCellPixelRange(index, divisions, extent)
    val raw = ((index + 0.5) * extent.coerceAtLeast(1) / divisions).roundToInt()
    return raw.coerceIn(range.first, range.last)
}

private val PhoneUseGridCellId = Regex("""^r\s*(\d+)\s*c\s*(\d+)$""", RegexOption.IGNORE_CASE)
private val PhoneUseGridRowCol = Regex("""^(\d+)\s*[,，]\s*(\d+)$""")

internal fun parsePhoneUseGridCell(raw: String): PhoneUseGridCell {
    val text = raw.trim()
    val match = PhoneUseGridCellId.matchEntire(text) ?: PhoneUseGridRowCol.matchEntire(text)
        ?: throw IllegalArgumentException(phoneUseGridCellRejected(raw))
    val row = match.groupValues[1].toInt()
    val col = match.groupValues[2].toInt()
    if (row !in 0 until PhoneUseGridRows || col !in 0 until PhoneUseGridCols) {
        throw IllegalArgumentException(phoneUseGridCellRejected(raw))
    }
    return PhoneUseGridCell(row, col)
}

/**
 * Reads `cell`, `grid_id`, or `row`+`col`. Returns null when the action did not name a cell.
 * Pixel values and 0..1000 coordinates are rejected rather than clamped.
 */
internal fun phoneUseGridCellFromArguments(arguments: JSONObject): PhoneUseGridCell? {
    val cellText = arguments.optString("cell").trim().ifBlank { arguments.optString("grid_id").trim() }
    val hasRow = arguments.has("row") && !arguments.isNull("row")
    val hasCol = arguments.has("col") && !arguments.isNull("col")
    if (cellText.isBlank() && !hasRow && !hasCol) return null
    val fromLabel = if (cellText.isNotBlank()) parsePhoneUseGridCell(cellText) else null
    val fromAxes = if (hasRow || hasCol) {
        if (!hasRow || !hasCol) {
            throw IllegalArgumentException("Pass both row and col, or a single cell / grid_id such as r3c5.")
        }
        parsePhoneUseGridCell("${jsonGridAxis(arguments, "row")},${jsonGridAxis(arguments, "col")}")
    } else null
    if (fromLabel != null && fromAxes != null && fromLabel != fromAxes) {
        throw IllegalArgumentException(
            "cell/grid_id ${fromLabel.id} does not match row,col ${fromAxes.row},${fromAxes.col}.",
        )
    }
    return fromLabel ?: fromAxes
}

internal fun phoneUseGridContract(): JSONObject = JSONObject()
    .put("rows", PhoneUseGridRows)
    .put("cols", PhoneUseGridCols)
    .put("indexing", "zero_based")
    .put("id", "r{row}c{col}")
    .put("example", "r3c5")
    .put("also_accepts", "grid_id, row,col, or row+col")
    .put("tap_argument", "cell")
    .put("long_press_action", "long_click")
    .put("coordinates", "forbidden")

internal fun phoneUseGridStdout(treeUsable: Boolean, explicit: Boolean): String = when {
    treeUsable && explicit ->
        "元素树里已有可用 eid，请优先用 eid 点击或长按。8×12 网格只是这次显式观察附上的，不要把格子换算成像素或 0..1000 坐标。文字仍调用 text：先 commitText，再 ACTION_SET_TEXT，剪贴板粘贴只是最后回退。"
    treeUsable ->
        "使用元素树里的 eid。不要提交像素或 0..1000 坐标。"
    else ->
        "没有可用 eid。截图已画上 8×12 网格，格子编号是 r{行}c{列}（行 0..7，列 0..11），也可传 row,col 或 grid_id。点击或长按请用 cell（例如 r3c5）。不要计算像素，不要提交 0..1000 坐标。文字仍调用 text：先 commitText，再 ACTION_SET_TEXT，剪贴板粘贴只是最后回退。"
}

private fun jsonGridAxis(arguments: JSONObject, key: String): String {
    val value = arguments.get(key)
    return when (value) {
        is Number -> {
            val asDouble = value.toDouble()
            if (asDouble.isNaN() || asDouble.isInfinite() || asDouble != asDouble.toInt().toDouble()) {
                throw IllegalArgumentException(
                    "$key must be a whole 8×12 grid index. Do not send pixels or normalized 0..1000 coordinates.",
                )
            }
            asDouble.toInt().toString()
        }
        is String -> value.trim()
        else -> throw IllegalArgumentException(
            "$key must be a whole 8×12 grid index. Do not send pixels or normalized 0..1000 coordinates.",
        )
    }
}

private fun phoneUseGridCellRejected(raw: String): String =
    "Grid cell '$raw' is outside the 8×12 grid. Use r{row}c{col} or row,col with row 0..7 and col 0..11 (for example r3c5). Do not send raw pixels or normalized 0..1000 coordinates."
