package com.zhousl.aether.phoneuse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.roundToInt

internal const val WeChatPackageName = "com.tencent.mm"

internal val PasteLabels = listOf("粘贴", "Paste", "貼上")
internal val SelectAllLabels = listOf("全选", "Select all", "Select All")
internal val CutLabels = listOf("剪切", "Cut", "剪切文字")

internal fun normalizePhoneUseAction(action: String): String = when (action) {
    "fill_text", "filltext" -> "text"
    else -> action
}

internal fun isWeChatPackage(packageName: String?): Boolean =
    packageName?.substringBefore('/')?.trim() == WeChatPackageName

/** A rejected or unread fill_text must not be reported as success for WeChat or an empty tree. */
internal fun nodeReadbackVerified(before: String?, after: String?, expected: String): Boolean {
    if (after == null || after != expected) return false
    if (expected.isEmpty()) return !before.isNullOrEmpty()
    return true
}

internal fun shouldRejectKeyboardTap(guarded: Boolean, keyboard: Rect?, x: Int, y: Int): Boolean {
    if (!guarded || keyboard == null || keyboard.isEmpty) return false
    return keyboard.contains(x, y)
}

internal fun pasteTapCandidates(
    anchorX: Int,
    anchorY: Int,
    density: Float,
    screenWidth: Int,
    screenHeight: Int,
    keyboardTop: Int?,
): List<Pair<Int, Int>> {
    if (screenWidth <= 0 || screenHeight <= 0) return emptyList()
    val safeDensity = density.coerceAtLeast(1f)
    return listOf(48f, 72f).map { gap ->
        val x = anchorX.coerceIn(0, screenWidth - 1)
        val y = (anchorY - gap * safeDensity).roundToInt()
        x to y
    }.filter { (_, y) ->
        y in 1 until screenHeight && (keyboardTop == null || y < keyboardTop)
    }.distinct()
}

internal fun pasteMenuRegion(
    anchorX: Int,
    anchorY: Int,
    screenWidth: Int,
    screenHeight: Int,
    density: Float,
): Rect {
    val safeDensity = density.coerceAtLeast(1f)
    val dx = (180 * safeDensity).roundToInt()
    val dy = (140 * safeDensity).roundToInt()
    return Rect(
        (anchorX - dx).coerceIn(0, screenWidth),
        (anchorY - dy).coerceIn(0, screenHeight),
        (anchorX + dx).coerceIn(0, screenWidth),
        anchorY.coerceIn(0, screenHeight),
    ).also { if (it.right < it.left) it.right = it.left }
}

internal fun inputVerifyRegion(
    anchorX: Int,
    anchorY: Int,
    screenWidth: Int,
    screenHeight: Int,
    density: Float,
): Rect {
    val pad = (32 * density.coerceAtLeast(1f)).roundToInt().coerceAtLeast(12)
    return Rect(
        0,
        (anchorY - pad).coerceIn(0, screenHeight),
        screenWidth.coerceAtLeast(1),
        (anchorY + pad).coerceIn(0, screenHeight).coerceAtLeast((anchorY - pad).coerceAtLeast(0) + 1),
    )
}

internal fun textEntryReflection(
    text: String,
    attempts: List<String>,
    weChat: Boolean,
    emptyTree: Boolean,
): String {
    val scene = buildList {
        if (weChat) add("微信 com.tencent.mm")
        if (emptyTree) add("无障碍树为空或没有可写节点")
        if (isEmpty()) add("受限输入场景")
    }.joinToString("，")
    val target = if (text.isEmpty()) "（清空输入框）" else "「${text.take(40)}」"
    return buildString {
        appendLine("文字没有写进输入框，这次 fill_text 不能当作成功。")
        appendLine("场景：$scene。")
        appendLine("目标：$target")
        appendLine("已尝试：${attempts.joinToString("；").ifBlank { "无" }}。")
        appendLine("校验：无障碍树和截图都没有确认目标文字。")
        appendLine("请改用完整目标文字覆盖输入框。清空请发送空文字，让系统选中后剪切。")
        appendLine("不要点软键盘退格，也不要请用户手动粘贴。")
        append("若剪贴板粘贴和长按「粘贴」都失败，再提示切换到可以提交文字的输入法后重试。")
    }
}

internal fun keyboardTapReflection(): String = """
软键盘上的点击不能当作删除或输入。微信或空树里点退格清不掉已有文字。
请改用 text：写入完整目标文字，会先选中再粘贴覆盖；清空则发送空文字。
不要点软键盘退格，也不要请用户手动粘贴。
""".trim()

internal data class TextEntryOutcome(
    val verified: Boolean,
    val method: String,
    val verifiedBy: String,
    val attempts: List<String>,
    val reflection: String,
    val weChat: Boolean,
    val emptyTree: Boolean,
)

internal interface PhoneUseTextHost {
    val weChat: Boolean
    val emptyTree: Boolean
    val density: Float
    val screenWidth: Int
    val screenHeight: Int
    fun writeClipboard(text: String)
    /** Null when no field is exposed. Empty when the field is exposed but blank. */
    fun fieldText(): String?
    fun trySetText(text: String): Boolean
    fun trySelectAll(): Boolean
    fun tryPaste(): Boolean
    fun tryCut(): Boolean
    fun labelCenter(labels: List<String>): Pair<Int, Int>?
    fun anchor(): Pair<Int, Int>
    fun keyboardTop(): Int?
    suspend fun tap(x: Int, y: Int)
    suspend fun longPress(x: Int, y: Int)
    suspend fun capture(): Bitmap?
}

internal class PhoneUseTextEntry(
    private val host: PhoneUseTextHost,
    private val glyphs: PhoneUseGlyphLocator = PhoneUseGlyphLocator(),
) {
    suspend fun enter(text: String): TextEntryOutcome {
        lastRequestedText = text
        val attempts = mutableListOf<String>()
        if (text.isNotEmpty()) {
            runCatching { host.writeClipboard(text) }
                .onFailure { attempts += "写入剪贴板失败" }
        }
        val before = host.fieldText()
        if (text.isEmpty()) {
            clearByNodes(before, attempts)?.let { return success(it, "tree", attempts) }
        } else {
            replaceByNodes(text, before, attempts)?.let { return success(it, "tree", attempts) }
        }
        return gestureEntry(text, before, attempts)
    }

    private fun success(method: String, verifiedBy: String, attempts: List<String>) = TextEntryOutcome(
        verified = true,
        method = method,
        verifiedBy = verifiedBy,
        attempts = attempts,
        reflection = "",
        weChat = host.weChat,
        emptyTree = host.emptyTree,
    )

    private fun failure(attempts: List<String>) = TextEntryOutcome(
        verified = false,
        method = "",
        verifiedBy = "",
        attempts = attempts,
        reflection = textEntryReflection(lastRequestedText, attempts, host.weChat, host.emptyTree),
        weChat = host.weChat,
        emptyTree = host.emptyTree,
    )

    private var lastRequestedText: String = ""

    private fun clearByNodes(before: String?, attempts: MutableList<String>): String? {
        if (host.trySetText("") && nodeReadbackVerified(before, host.fieldText(), "")) return "set_text_clear"
        attempts += "ACTION_SET_TEXT 空字符串未能清空"
        if (host.trySelectAll() && host.tryCut() && nodeReadbackVerified(before, host.fieldText(), "")) {
            attempts += "已选中全文并剪切"
            return "cut_selection"
        }
        attempts += "选中后剪切未能清空"
        if (host.trySelectAll() && host.trySetText("") && nodeReadbackVerified(before, host.fieldText(), "")) return "set_text_selection_clear"
        attempts += "选中后再次清空仍未通过回读"
        return null
    }

    private fun replaceByNodes(text: String, before: String?, attempts: MutableList<String>): String? {
        val setAccepted = host.trySetText(text)
        if (setAccepted && nodeReadbackVerified(before, host.fieldText(), text)) return "set_text"
        attempts += if (setAccepted) "ACTION_SET_TEXT 返回成功但文字没有变成目标" else "ACTION_SET_TEXT 被拒绝"
        if (host.trySelectAll()) {
            attempts += "已请求选中全文"
            runCatching { host.writeClipboard(text) }
            if (host.trySetText(text) && nodeReadbackVerified(before, host.fieldText(), text)) return "set_text_selection"
            if (host.tryPaste() && nodeReadbackVerified(before, host.fieldText(), text)) return "paste_overwrite"
            attempts += "选中后的 SET_TEXT / ACTION_PASTE 未通过校验"
        }
        runCatching { host.writeClipboard(text) }
        val pasted = host.tryPaste()
        if (pasted && nodeReadbackVerified(before, host.fieldText(), text)) return "paste"
        attempts += if (pasted) "ACTION_PASTE 已调用但文字未变成目标" else "ACTION_PASTE 被拒绝或没有输入框"
        val after = host.fieldText()
        if (after != null && after != text && after.endsWith(text) && host.trySelectAll()) {
            runCatching { host.writeClipboard(text) }
            if (host.tryPaste() && nodeReadbackVerified(before, host.fieldText(), text)) {
                attempts += "检测到残留文字，已再次选中并粘贴覆盖"
                return "paste_overwrite"
            }
            attempts += "再次粘贴覆盖后仍未变成目标文字"
        }
        return null
    }

    private suspend fun gestureEntry(text: String, before: String?, attempts: MutableList<String>): TextEntryOutcome {
        val anchor = host.anchor()
        val region = inputVerifyRegion(anchor.first, anchor.second, host.screenWidth, host.screenHeight, host.density)
        val beforeShot = host.capture()
        var menuShot: Bitmap? = null
        try {
            host.tap(anchor.first, anchor.second)
            kotlinx.coroutines.delay(160)
            host.longPress(anchor.first, anchor.second)
            kotlinx.coroutines.delay(420)
            attempts += "已对输入位置做真实长按"
            menuShot = host.capture()
            if (text.isEmpty() || !before.isNullOrEmpty()) {
                locate(SelectAllLabels, "全选", anchor)?.let { point ->
                    host.tap(point.first, point.second)
                    kotlinx.coroutines.delay(240)
                    attempts += "已点「全选」"
                    host.longPress(anchor.first, anchor.second)
                    kotlinx.coroutines.delay(360)
                    menuShot?.recycle()
                    menuShot = host.capture()
                }
            }
            if (text.isEmpty()) {
                val cut = locate(CutLabels, "剪切", anchor)
                if (cut == null) {
                    attempts += "长按菜单里没有找到「剪切」"
                    return failure(attempts)
                }
                host.tap(cut.first, cut.second)
                kotlinx.coroutines.delay(280)
                attempts += "已点「剪切」"
                val via = confirm(text, before, beforeShot, menuShot, region, locatedTap = true)
                if (via != null) return success("gesture_cut", via, attempts)
                attempts += "剪切后树和截图都没有确认输入框已空"
                return failure(attempts)
            }
            if (text.isNotEmpty()) runCatching { host.writeClipboard(text) }
            val located = locate(PasteLabels, "粘贴", anchor)
            if (located == null) attempts += "树和截图都没有「粘贴」，改为点长按位置上方的菜单坐标"
            val points = buildList {
                if (located != null) add(located to true)
                pasteTapCandidates(
                    anchor.first, anchor.second, host.density, host.screenWidth, host.screenHeight, host.keyboardTop(),
                ).forEach { add(it to false) }
            }.distinctBy { it.first }.take(3)
            if (points.isEmpty()) {
                attempts += "没有可点的「粘贴」位置"
                return failure(attempts)
            }
            for ((point, locatedTap) in points) {
                host.tap(point.first, point.second)
                kotlinx.coroutines.delay(300)
                val via = confirm(text, before, beforeShot, menuShot, region, locatedTap)
                if (via != null) {
                    attempts += "已点「粘贴」并确认文字进入输入框"
                    return success("gesture_paste", via, attempts)
                }
            }
            attempts += "点「粘贴」后，树和截图都没有出现目标文字"
            return failure(attempts)
        } finally {
            beforeShot?.recycle()
            menuShot?.recycle()
        }
    }

    private suspend fun confirm(
        expected: String,
        before: String?,
        beforeShot: Bitmap?,
        menuShot: Bitmap?,
        region: Rect,
        locatedTap: Boolean,
    ): String? {
        val after = host.fieldText()
        if (nodeReadbackVerified(before, after, expected)) return "tree"
        if (expected.isNotEmpty() && screenshotShowsNewText(expected, beforeShot, region)) return "screenshot"
        if (expected.isEmpty() && !before.isNullOrEmpty() && screenshotCleared(before, beforeShot, region)) return "screenshot"
        if (locatedTap && menuShot != null && inputBandChanged(menuShot, region)) return "screenshot"
        return null
    }

    private suspend fun locate(labels: List<String>, glyph: String, anchor: Pair<Int, Int>): Pair<Int, Int>? {
        host.labelCenter(labels)?.let { return it }
        val bitmap = host.capture() ?: return null
        try {
            val menu = pasteMenuRegion(anchor.first, anchor.second, host.screenWidth, host.screenHeight, host.density)
            val sizes = floatArrayOf(14f, 16f, 13f, 18f).map { it * host.density.coerceAtLeast(1f) }.toFloatArray()
            for (label in listOf(glyph) + labels.filter { it != glyph }) {
                val hit = glyphs.find(bitmap, label, menu, sizes) ?: continue
                return (hit.left + hit.right) / 2 to (hit.top + hit.bottom) / 2
            }
            return null
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun screenshotShowsNewText(expected: String, beforeShot: Bitmap?, region: Rect): Boolean {
        val sample = expected.take(12)
        val after = host.capture() ?: return false
        try {
            val sizes = fieldSizes()
            if (glyphs.find(after, sample, region, sizes) == null) return false
            if (beforeShot != null && glyphs.find(beforeShot, sample, region, sizes) != null) return false
            return true
        } finally {
            after.recycle()
        }
    }

    private suspend fun screenshotCleared(previous: String, beforeShot: Bitmap?, region: Rect): Boolean {
        if (beforeShot == null) return false
        val sample = previous.take(8)
        val sizes = fieldSizes()
        if (glyphs.find(beforeShot, sample, region, sizes) == null) return false
        val after = host.capture() ?: return false
        try {
            return glyphs.find(after, sample, region, sizes) == null
        } finally {
            after.recycle()
        }
    }

    private fun fieldSizes(): FloatArray =
        floatArrayOf(16f, 15f, 18f, 14f, 20f).map { it * host.density.coerceAtLeast(1f) }.toFloatArray()

    private suspend fun inputBandChanged(baseline: Bitmap, region: Rect): Boolean {
        val after = host.capture() ?: return false
        return try {
            bitmapRegionChanged(baseline, after, region)
        } finally {
            after.recycle()
        }
    }
}

internal fun bitmapRegionChanged(before: Bitmap, after: Bitmap, region: Rect): Boolean {
    if (before.width != after.width || before.height != after.height || before.width <= 0 || before.height <= 0) return false
    val bounds = Rect(
        region.left.coerceIn(0, before.width),
        region.top.coerceIn(0, before.height),
        region.right.coerceIn(0, before.width),
        region.bottom.coerceIn(0, before.height),
    )
    val width = bounds.width()
    val height = bounds.height()
    if (width < 8 || height < 8) return false
    val beforePixels = IntArray(width * height)
    val afterPixels = IntArray(width * height)
    before.getPixels(beforePixels, 0, width, bounds.left, bounds.top, width, height)
    after.getPixels(afterPixels, 0, width, bounds.left, bounds.top, width, height)
    var samples = 0
    var changed = 0
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            samples++
            val index = y * width + x
            if (kotlin.math.abs(luminance(beforePixels[index]) - luminance(afterPixels[index])) > 48) changed++
            x += 3
        }
        y += 3
    }
    return samples > 0 && changed.toFloat() / samples.toFloat() > 0.02f
}

private fun luminance(color: Int): Int =
    (android.graphics.Color.red(color) * 3 + android.graphics.Color.green(color) * 6 + android.graphics.Color.blue(color)) / 10

internal fun inkOverlapScore(
    haystack: BooleanArray,
    hayWidth: Int,
    hayHeight: Int,
    needle: BooleanArray,
    needleWidth: Int,
    needleHeight: Int,
    originX: Int,
    originY: Int,
): Float {
    if (needleWidth <= 0 || needleHeight <= 0) return 0f
    var ink = 0
    var hit = 0
    for (y in 0 until needleHeight) {
        val hayY = originY + y
        for (x in 0 until needleWidth) {
            if (!needle[y * needleWidth + x]) continue
            ink++
            val hayX = originX + x
            if (hayY in 0 until hayHeight && hayX in 0 until hayWidth && haystack[hayY * hayWidth + hayX]) hit++
        }
    }
    if (ink == 0) return 0f
    return hit.toFloat() / ink.toFloat()
}

/** Finds rendered text in a screenshot. Used to tap「粘贴」and to confirm the field changed. */
internal class PhoneUseGlyphLocator(
    private val typeface: Typeface = Typeface.DEFAULT,
) {
    fun renderTemplate(text: String, textSizePx: Float): Bitmap? {
        val sample = text.trim()
        if (sample.isEmpty() || textSizePx < 4f) return null
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            color = Color.BLACK
            typeface = this@PhoneUseGlyphLocator.typeface
        }
        val width = paint.measureText(sample).roundToInt().coerceAtLeast(1) + 4
        val height = (paint.fontMetrics.bottom - paint.fontMetrics.top).roundToInt().coerceAtLeast(1) + 4
        if (width > 900 || height > 240) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(sample, 2f, -paint.fontMetrics.top + 2f, paint)
        }
        return bitmap
    }

    fun find(bitmap: Bitmap, query: String, region: Rect, textSizesPx: FloatArray): Rect? =
        runCatching { findImpl(bitmap, query, region, textSizesPx) }.getOrNull()

    private fun findImpl(bitmap: Bitmap, query: String, region: Rect, textSizesPx: FloatArray): Rect? {
        val sample = query.trim().take(16)
        if (sample.isEmpty() || bitmap.width < 8 || bitmap.height < 8) return null
        val bounds = Rect(
            region.left.coerceIn(0, bitmap.width),
            region.top.coerceIn(0, bitmap.height),
            region.right.coerceIn(0, bitmap.width),
            region.bottom.coerceIn(0, bitmap.height),
        )
        if (bounds.width() < 8 || bounds.height() < 8) return null
        val scale = (420f / bounds.width()).coerceAtMost(1f)
        val crop = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        Canvas(crop).drawBitmap(bitmap, -bounds.left.toFloat(), -bounds.top.toFloat(), null)
        val scaled = if (scale < 0.999f) {
            Bitmap.createScaledBitmap(crop, (bounds.width() * scale).roundToInt().coerceAtLeast(1), (bounds.height() * scale).roundToInt().coerceAtLeast(1), true)
        } else crop
        try {
            val dark = inkMap(scaled, darkInk = true)
            val light = inkMap(scaled, darkInk = false)
            var bestScore = 0f
            var best: Rect? = null
            for (size in textSizesPx) {
                val template = renderTemplate(sample, size * scale) ?: continue
                try {
                    val needle = inkMap(template, darkInk = true)
                    val ratio = needle.ink.toFloat() / (template.width * template.height).coerceAtLeast(1)
                    if (needle.ink < 8 || ratio < 0.02f || ratio > 0.75f) continue
                    val hit = bestHit(dark, scaled.width, scaled.height, needle, template.width, template.height)
                    val hitLight = bestHit(light, scaled.width, scaled.height, needle, template.width, template.height)
                    val chosen = if (hit.first >= hitLight.first) hit else hitLight
                    if (chosen.first > bestScore && chosen.second != null) {
                        bestScore = chosen.first
                        best = chosen.second
                    }
                } finally {
                    template.recycle()
                }
            }
            if (bestScore < 0.58f || best == null) return null
            return Rect(
                bounds.left + (best.left / scale).roundToInt(),
                bounds.top + (best.top / scale).roundToInt(),
                bounds.left + (best.right / scale).roundToInt(),
                bounds.top + (best.bottom / scale).roundToInt(),
            )
        } finally {
            if (scaled !== crop) scaled.recycle()
            crop.recycle()
        }
    }

    private class Ink(val bits: BooleanArray, val ink: Int)

    private fun inkMap(bitmap: Bitmap, darkInk: Boolean): Ink {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val bits = BooleanArray(pixels.size)
        var count = 0
        for (index in pixels.indices) {
            if (Color.alpha(pixels[index]) < 40) continue
            val lum = (Color.red(pixels[index]) * 3 + Color.green(pixels[index]) * 6 + Color.blue(pixels[index])) / 10
            val ink = if (darkInk) lum < 150 else lum > 210
            bits[index] = ink
            if (ink) count++
        }
        return Ink(bits, count)
    }

    private fun bestHit(hay: Ink, hayWidth: Int, hayHeight: Int, needle: Ink, needleWidth: Int, needleHeight: Int): Pair<Float, Rect?> {
        if (needleWidth > hayWidth || needleHeight > hayHeight) return 0f to null
        val step = 2
        var best = 0f
        var bestRect: Rect? = null
        var y = 0
        while (y <= hayHeight - needleHeight) {
            var x = 0
            while (x <= hayWidth - needleWidth) {
                val score = inkOverlapScore(hay.bits, hayWidth, hayHeight, needle.bits, needleWidth, needleHeight, x, y)
                if (score > best) {
                    best = score
                    bestRect = Rect(x, y, x + needleWidth, y + needleHeight)
                }
                x += step
            }
            y += step
        }
        val coarse = bestRect ?: return best to null
        for (dy in -step..step) {
            for (dx in -step..step) {
                val score = inkOverlapScore(
                    hay.bits, hayWidth, hayHeight, needle.bits, needleWidth, needleHeight, coarse.left + dx, coarse.top + dy,
                )
                if (score > best) {
                    best = score
                    bestRect = Rect(coarse.left + dx, coarse.top + dy, coarse.left + dx + needleWidth, coarse.top + dy + needleHeight)
                }
            }
        }
        return best to bestRect
    }
}
