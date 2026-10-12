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
internal const val WeTypePackageName = "com.tencent.wetype"

internal val PasteLabels = listOf("粘贴", "Paste", "貼上")
internal val SelectAllLabels = listOf("全选", "Select all", "Select All")
internal val CutLabels = listOf("剪切", "Cut", "剪切文字")
internal val SendLabels = listOf("发送", "Send", "傳送")

internal fun normalizePhoneUseAction(action: String): String = when (action) {
    "fill_text", "filltext" -> "text"
    else -> action
}

internal fun isWeChatPackage(packageName: String?): Boolean =
    packageName?.substringBefore('/')?.trim() == WeChatPackageName

internal fun isWeTypePackage(packageName: String?): Boolean =
    packageName?.substringBefore('/')?.trim() == WeTypePackageName

/** Window titles often look like `com.tencent.mm/.ui.LauncherUI` while the root package is blank or the IME. */
internal fun mentionsWeChat(packageName: String?, title: CharSequence?): Boolean {
    if (isWeChatPackage(packageName)) return true
    val titleText = title?.toString().orEmpty()
    val index = titleText.indexOf(WeChatPackageName)
    if (index < 0) return false
    val next = titleText.getOrNull(index + WeChatPackageName.length)
    return next == null || next == '/' || next == ' ' || next == '}' || next == ')'
}

/** A rejected node write is not the end of text entry. Stale observations stay errors. */
internal fun shouldRecoverTextEntryWithClipboard(error: Throwable): Boolean {
    if (error is kotlinx.coroutines.CancellationException) return false
    if (error is PhoneUseElementException) {
        return error.code == "action_rejected" || error.code == "action_unsupported"
    }
    val message = error.message.orEmpty()
    return message.contains("Focused field rejected text") || message.contains("No focused input")
}

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

/** Long-press points on the input line, above the keyboard and left of a trailing send button. */
internal fun inputLongPressPoints(
    anchorX: Int,
    anchorY: Int,
    density: Float,
    screenWidth: Int,
    screenHeight: Int,
    keyboardTop: Int?,
): List<Pair<Int, Int>> {
    if (screenWidth <= 0 || screenHeight <= 0) return emptyList()
    val safeDensity = density.coerceAtLeast(1f)
    val ceiling = ((keyboardTop ?: screenHeight) - 1).coerceIn(0, screenHeight - 1)
    fun clamp(x: Int, y: Int) = x.coerceIn(0, screenWidth - 1) to y.coerceIn(0, ceiling)
    val left = (anchorX - 72f * safeDensity).roundToInt()
    val aboveKeys = keyboardTop?.let { (it - 28f * safeDensity).roundToInt() }
    return listOfNotNull(
        clamp(anchorX, anchorY),
        aboveKeys?.let { clamp(anchorX, it) },
        clamp(left, anchorY),
    ).distinct()
}

/** Bottom composer row: left of the send button, above a normal keyboard or the gesture inset. */
internal fun bottomInputBarPoint(
    screenWidth: Int,
    screenHeight: Int,
    density: Float,
    keyboardTop: Int?,
): Pair<Int, Int> {
    if (screenWidth <= 0 || screenHeight <= 0) return 0 to 0
    val safeDensity = density.coerceAtLeast(1f)
    val x = (screenWidth * 0.42f).roundToInt().coerceIn(0, screenWidth - 1)
    val normalTop = keyboardTop?.takeIf { it > (screenHeight * 0.45f).roundToInt() }
    val y = if (normalTop != null) {
        (normalTop - 32f * safeDensity).roundToInt()
    } else {
        (screenHeight - 64f * safeDensity).roundToInt()
    }
    val ceiling = ((normalTop ?: screenHeight) - 1).coerceIn(0, screenHeight - 1)
    return x to y.coerceIn(0, ceiling)
}

/** True when the long-press edit menu would land inside the IME, including com.tencent.wetype. */
internal fun keyboardCoversPasteTarget(anchorX: Int, anchorY: Int, keyboard: Rect?, density: Float): Boolean {
    if (keyboard == null || keyboard.isEmpty) return false
    if (keyboard.contains(anchorX, anchorY)) return true
    val menu = pasteMenuRegion(
        anchorX,
        anchorY,
        screenWidth = keyboard.right.coerceAtLeast(anchorX + 1),
        screenHeight = keyboard.bottom.coerceAtLeast(anchorY + 1),
        density = density,
    )
    return Rect.intersects(menu, keyboard)
}

/** Anchor first, then other points on the input row. Callers retry when the first paste does not verify. */
internal fun weChatInputPressPoints(
    anchorX: Int,
    anchorY: Int,
    density: Float,
    screenWidth: Int,
    screenHeight: Int,
    keyboardTop: Int?,
): List<Pair<Int, Int>> {
    if (screenWidth <= 0 || screenHeight <= 0) return emptyList()
    val bar = bottomInputBarPoint(screenWidth, screenHeight, density, keyboardTop)
    val safeDensity = density.coerceAtLeast(1f)
    val ceiling = ((keyboardTop?.takeIf { it > (screenHeight * 0.45f).roundToInt() } ?: screenHeight) - 1)
        .coerceIn(0, screenHeight - 1)
    fun clamp(x: Int, y: Int) = x.coerceIn(0, screenWidth - 1) to y.coerceIn(0, ceiling)
    return (
        listOf(
            clamp(anchorX, anchorY),
            bar,
            clamp((screenWidth * 0.30f).roundToInt(), bar.second),
            clamp((screenWidth * 0.55f).roundToInt(), (bar.second - 16f * safeDensity).roundToInt()),
        ) + inputLongPressPoints(anchorX, anchorY, density, screenWidth, screenHeight, keyboardTop)
    ).distinct()
}

/** Right side of the input row. Shifts left when that point sits on the commentary chip. */
internal fun sendButtonPoint(
    inputY: Int,
    screenWidth: Int,
    screenHeight: Int,
    density: Float,
    keyboardTop: Int?,
    overlay: Rect?,
): Pair<Int, Int>? {
    if (screenWidth <= 0 || screenHeight <= 0) return null
    val safeDensity = density.coerceAtLeast(1f)
    val normalTop = keyboardTop?.takeIf { it > (screenHeight * 0.45f).roundToInt() }
    val ceiling = ((normalTop ?: screenHeight) - 1).coerceIn(0, screenHeight - 1)
    val y = inputY.coerceIn(0, ceiling)
    var x = (screenWidth - 40f * safeDensity).roundToInt().coerceIn(0, screenWidth - 1)
    if (overlay != null && overlay.contains(x, y)) {
        x = (overlay.left - (16f * safeDensity).roundToInt()).coerceIn(0, screenWidth - 1)
        if (overlay.contains(x, y)) return null
    }
    if (normalTop != null && y >= normalTop) return null
    return x to y
}

/** Newest chat bubble sits just above the composer, not inside the input line. */
internal fun chatBubbleRegion(inputY: Int, screenWidth: Int, screenHeight: Int, density: Float): Rect {
    val safeDensity = density.coerceAtLeast(1f)
    val height = screenHeight.coerceAtLeast(1)
    val bottom = (inputY - (8f * safeDensity).roundToInt()).coerceIn(1, height)
    val top = (bottom - (180f * safeDensity).roundToInt()).coerceAtLeast(0)
    return Rect(0, top, screenWidth.coerceAtLeast(1), bottom.coerceAtLeast(top + 1).coerceAtMost(height))
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
    clipboardUnavailable: Boolean = false,
): String {
    val scene = buildList {
        if (weChat) add("微信 com.tencent.mm")
        if (emptyTree) add("无障碍树为空或没有可写节点")
        if (isEmpty()) add("受限输入场景")
    }.joinToString("，")
    val target = if (text.isEmpty()) "（清空输入框）" else "「${text.take(40)}」"
    return buildString {
        appendLine("文字没有写进输入框，这次 text/fill_text 不能当作成功。")
        appendLine("场景：$scene。")
        appendLine("目标：$target")
        appendLine("已尝试：${attempts.joinToString("；").ifBlank { "无" }}。")
        appendLine("校验：无障碍树和截图都没有确认目标文字。未校验不得当作成功，也不得声称已发送。")
        if (clipboardUnavailable) {
            append("剪贴板不可用，所以无法粘贴。只有剪贴板本身不可用时，才可以提示检查输入法是否拦截了剪贴板。不要请用户手动打字或发送。")
        } else {
            appendLine("请继续只调用 text，把完整目标文字交给剪贴板粘贴。清空请发送空文字。")
            appendLine("不要点软键盘退格，也不要请用户手动粘贴。")
            appendLine("不要逐键打拼音，不要点输入法按键，也不要请用户手动打字或发送。")
            append("不要换输入法。未校验到目标文字不得当作已输入或已发送。")
        }
    }
}

internal fun sendNotVerifiedReflection(text: String, attempts: List<String>): String {
    val target = if (text.isEmpty()) "目标文字" else "「${text.take(40)}」"
    return buildString {
        appendLine("输入框里的${target}已经通过校验，但没有在聊天气泡里确认发出。")
        appendLine("已尝试：${attempts.joinToString("；").ifBlank { "无" }}。")
        appendLine("不要再粘贴第二份，除非输入框里已经没有这段文字。")
        appendLine("请再调用一次 key=ENTER，并带上同一段 text。宿主会先发 KEYCODE_ENTER，不行再点输入栏右侧的发送，并避开右侧思考条。")
        append("不要换输入法，不要逐键拼音，不要请用户打字或发送，也不要声称消息已经发出。")
    }
}

internal fun keyboardTapReflection(): String = """
软键盘上的点击不能当作删除或输入。微信或空树里点退格、逐键拼音都写不进输入框。
请改用 text：写入完整目标文字，会先放入剪贴板，再粘贴覆盖；清空则发送空文字。
不要点软键盘退格，也不要请用户手动粘贴。
不要逐键打拼音，也不要请用户手动打字或发送。
""".trim()

internal data class TextEntryOutcome(
    val verified: Boolean,
    val method: String,
    val verifiedBy: String,
    val attempts: List<String>,
    val reflection: String,
    val weChat: Boolean,
    val emptyTree: Boolean,
    val sent: Boolean = false,
    val sentBy: String = "",
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
    /** Ctrl+V / KEYCODE_PASTE when the device allows it. False when the system has no such injection. */
    suspend fun trySystemPasteShortcut(): Boolean = false
    fun keyboardBounds(): Rect? = keyboardTop()?.let { top ->
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val safeTop = top.coerceIn(0, screenHeight)
        if (safeTop >= screenHeight) null else Rect(0, safeTop, screenWidth, screenHeight)
    }
    fun keyboardPackage(): String? = null
    /** BACK only while an IME or WeChat keyboard is actually showing. */
    suspend fun dismissKeyboard(): Boolean = false
    fun overlayBounds(): Rect? = null
    /** KEYCODE_ENTER (66). False when the device rejects key injection. */
    suspend fun tryKeycodeEnter(): Boolean = false
    /** Non-editable on-screen text that already shows the message, such as a chat bubble. */
    fun bubbleContains(text: String): Boolean = false
    suspend fun capture(): Bitmap?
}

internal class PhoneUseTextEntry(
    private val host: PhoneUseTextHost,
    private val glyphs: PhoneUseGlyphLocator = PhoneUseGlyphLocator(),
) {
    suspend fun enter(text: String, sendAfterVerify: Boolean = false): TextEntryOutcome {
        lastRequestedText = text
        val attempts = mutableListOf<String>()
        if (text.isNotEmpty()) {
            val wrote = runCatching { host.writeClipboard(text) }.isSuccess
            if (!wrote) {
                attempts += "写入剪贴板失败"
                return failure(attempts, clipboardUnavailable = true)
            }
        }
        val before = host.fieldText()
        val filled = if (text.isEmpty()) {
            clearByNodes(before, attempts)?.let { success(it, "tree", attempts) }
                ?: gestureEntry(text, before, attempts)
        } else {
            replaceByNodes(text, before, attempts)?.let { success(it, "tree", attempts) }
                ?: gestureEntry(text, before, attempts)
        }
        if (!sendAfterVerify || text.isEmpty() || !filled.verified) return filled
        return sendVerified(filled, text)
    }

    /** Send a draft that was already verified. Does not paste again. */
    suspend fun sendOnly(expected: String): TextEntryOutcome {
        val sample = expected.trim().ifBlank { host.fieldText()?.trim().orEmpty() }
        val attempts = mutableListOf("校验通过后才发送")
        if (sample.isEmpty()) {
            attempts += "没有可核对的目标文字"
            return TextEntryOutcome(
                verified = false,
                method = "",
                verifiedBy = "",
                attempts = attempts,
                reflection = sendNotVerifiedReflection(sample, attempts),
                weChat = host.weChat,
                emptyTree = host.emptyTree,
            )
        }
        return sendVerified(success("already_verified", "caller", attempts), sample)
    }

    private fun success(method: String, verifiedBy: String, attempts: List<String>) = TextEntryOutcome(
        verified = true,
        method = method,
        verifiedBy = verifiedBy,
        attempts = attempts.toList(),
        reflection = "",
        weChat = host.weChat,
        emptyTree = host.emptyTree,
    )

    private fun failure(attempts: List<String>, clipboardUnavailable: Boolean = false) = TextEntryOutcome(
        verified = false,
        method = "",
        verifiedBy = "",
        attempts = attempts.toList(),
        reflection = textEntryReflection(
            lastRequestedText,
            attempts,
            host.weChat,
            host.emptyTree,
            clipboardUnavailable,
        ),
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
        val beforeShot = host.capture()
        try {
            return if (text.isEmpty()) gestureClear(before, beforeShot, attempts) else gesturePaste(text, before, beforeShot, attempts)
        } finally {
            beforeShot?.recycle()
        }
    }

    private suspend fun gestureClear(before: String?, beforeShot: Bitmap?, attempts: MutableList<String>): TextEntryOutcome {
        val point = focusComposer(attempts)
        val region = inputVerifyRegion(point.first, point.second, host.screenWidth, host.screenHeight, host.density)
        host.longPress(point.first, point.second)
        kotlinx.coroutines.delay(640)
        locate(SelectAllLabels, "全选", point)?.takeIf { usable(it) }?.let { select ->
            host.tap(select.first, select.second)
            kotlinx.coroutines.delay(240)
            attempts += "已点「全选」"
            host.longPress(point.first, point.second)
            kotlinx.coroutines.delay(480)
        }
        val cut = locate(CutLabels, "剪切", point)?.takeIf { usable(it) }
        if (cut == null) {
            attempts += "长按菜单里没有找到「剪切」"
            return failure(attempts)
        }
        host.tap(cut.first, cut.second)
        kotlinx.coroutines.delay(280)
        attempts += "已点「剪切」"
        val via = confirm("", before, beforeShot, region)
        if (via != null) return success("gesture_cut", via, attempts)
        attempts += "剪切后树和截图都没有确认输入框已空"
        return failure(attempts)
    }

    private suspend fun gesturePaste(
        text: String,
        before: String?,
        beforeShot: Bitmap?,
        attempts: MutableList<String>,
    ): TextEntryOutcome {
        var region = inputVerifyRegion(
            host.anchor().first, host.anchor().second, host.screenWidth, host.screenHeight, host.density,
        )
        val tried = mutableListOf<Pair<Int, Int>>()
        repeat(3) { index ->
            if (!runCatching { host.writeClipboard(text) }.isSuccess) {
                attempts += "写入剪贴板失败"
                return failure(attempts, clipboardUnavailable = true)
            }
            val candidates = weChatInputPressPoints(
                host.anchor().first,
                host.anchor().second,
                host.density,
                host.screenWidth,
                host.screenHeight,
                host.keyboardBounds()?.top,
            )
            val raw = candidates.firstOrNull { it !in tried } ?: host.anchor()
            tried += raw
            var point = avoidCoveringKeyboard(if (index == 0) host.anchor() else raw, attempts)
            if (index == 0) {
                host.tap(point.first, point.second)
                kotlinx.coroutines.delay(420)
                attempts += "已点输入栏以取得焦点"
                point = avoidCoveringKeyboard(host.anchor(), attempts)
                region = inputVerifyRegion(point.first, point.second, host.screenWidth, host.screenHeight, host.density)
            }
            val keyboard = host.keyboardBounds()
            if (keyboardCoversPasteTarget(point.first, point.second, keyboard, host.density)) {
                point = liftAboveKeyboard(point, keyboard)
            }
            host.longPress(point.first, point.second)
            kotlinx.coroutines.delay(640)
            if (!host.fieldText().isNullOrEmpty()) {
                locate(SelectAllLabels, "全选", point)?.takeIf { usable(it) }?.let { select ->
                    host.tap(select.first, select.second)
                    kotlinx.coroutines.delay(240)
                    attempts += "已点「全选」"
                    host.longPress(point.first, point.second)
                    kotlinx.coroutines.delay(480)
                }
            }
            var located = locate(PasteLabels, "粘贴", point)?.takeIf { usable(it) }
            if (located == null && host.keyboardBounds() != null) {
                val dismissed = runCatching { host.dismissKeyboard() }.getOrDefault(false)
                if (dismissed) {
                    attempts += "粘贴目标被输入法挡住，已收起后重新长按"
                    point = host.anchor()
                    host.longPress(point.first, point.second)
                    kotlinx.coroutines.delay(480)
                    located = locate(PasteLabels, "粘贴", point)?.takeIf { usable(it) }
                }
            }
            if (located == null) attempts += "第${index + 1}处长按后，没有落在输入法外面的「粘贴」"
            val taps = buildList {
                if (located != null) add(located)
                pasteTapCandidates(
                    point.first, point.second, host.density, host.screenWidth, host.screenHeight, host.keyboardBounds()?.top,
                ).filter { usable(it) }.forEach { add(it) }
            }.distinct().take(3)
            if (taps.isEmpty()) {
                attempts += "第${index + 1}处没有可点的「粘贴」位置"
                return@repeat
            }
            for (tapPoint in taps) {
                host.tap(tapPoint.first, tapPoint.second)
                kotlinx.coroutines.delay(300)
                val via = confirm(text, before, beforeShot, region)
                if (via != null) {
                    attempts += "已点「粘贴」并确认文字进入输入框"
                    return success("gesture_paste", via, attempts)
                }
            }
            attempts += "第${index + 1}次粘贴后，树和截图都没有出现目标文字"
        }
        val shortcut = runCatching { host.trySystemPasteShortcut() }.getOrDefault(false)
        if (!shortcut) {
            attempts += "系统不支持粘贴快捷键"
        } else {
            kotlinx.coroutines.delay(300)
            val via = confirm(text, before, beforeShot, region)
            if (via != null) {
                attempts += "已用系统粘贴快捷键并确认文字"
                return success("key_paste", via, attempts)
            }
            attempts += "系统粘贴快捷键没有通过校验"
        }
        return failure(attempts)
    }

    private suspend fun focusComposer(attempts: MutableList<String>): Pair<Int, Int> {
        val point = avoidCoveringKeyboard(host.anchor(), attempts)
        host.tap(point.first, point.second)
        kotlinx.coroutines.delay(420)
        attempts += "已点输入栏以取得焦点"
        return avoidCoveringKeyboard(host.anchor(), attempts)
    }

    private suspend fun avoidCoveringKeyboard(point: Pair<Int, Int>, attempts: MutableList<String>): Pair<Int, Int> {
        val keyboard = host.keyboardBounds() ?: return point
        val wetype = isWeTypePackage(host.keyboardPackage())
        val covers = keyboardCoversPasteTarget(point.first, point.second, keyboard, host.density)
        val hugeWeType = wetype && keyboard.height() > host.screenHeight / 2
        if (!covers && !hugeWeType) return point
        val dismissed = runCatching { host.dismissKeyboard() }.getOrDefault(false)
        if (dismissed) {
            attempts += "已收起挡住粘贴位置的输入法"
            return host.anchor()
        }
        attempts += "输入法仍盖住粘贴位置，改为在键盘上方的输入栏操作"
        return liftAboveKeyboard(point, host.keyboardBounds())
    }

    private fun liftAboveKeyboard(point: Pair<Int, Int>, keyboard: Rect?): Pair<Int, Int> {
        if (keyboard == null || keyboard.isEmpty || host.screenWidth <= 0 || host.screenHeight <= 0) return point
        val gap = (32f * host.density.coerceAtLeast(1f)).roundToInt()
        val y = if (keyboard.top <= gap) point.second else minOf(point.second, keyboard.top - gap)
        return point.first.coerceIn(0, host.screenWidth - 1) to y.coerceIn(0, host.screenHeight - 1)
    }

    private fun usable(point: Pair<Int, Int>): Boolean {
        val keyboard = host.keyboardBounds()
        if (keyboard != null && keyboard.contains(point.first, point.second)) return false
        val overlay = host.overlayBounds()
        if (overlay != null && overlay.contains(point.first, point.second)) return false
        return true
    }

    private suspend fun sendVerified(filled: TextEntryOutcome, text: String): TextEntryOutcome {
        val attempts = filled.attempts.toMutableList()
        attempts += "文字已校验，开始发送"
        val input = host.anchor()
        val inputRegion = inputVerifyRegion(input.first, input.second, host.screenWidth, host.screenHeight, host.density)
        val bubbleRegion = chatBubbleRegion(input.second, host.screenWidth, host.screenHeight, host.density)
        val before = runCatching { host.capture() }.getOrNull()
        try {
            val dispatched = runCatching { host.tryKeycodeEnter() }.getOrDefault(false)
            kotlinx.coroutines.delay(360)
            if (confirmSent(text, inputRegion, bubbleRegion, before)) {
                attempts += "KEYCODE_ENTER 后已在聊天气泡确认文字"
                return filled.copy(
                    method = "${filled.method}+enter",
                    attempts = attempts.toList(),
                    sent = true,
                    sentBy = "keycode_enter",
                    reflection = "",
                )
            }
            attempts += if (dispatched) "KEYCODE_ENTER 已发出，但气泡里还没有目标文字" else "KEYCODE_ENTER 不可用"
            val keyboard = host.keyboardBounds()
            val labeled = host.labelCenter(SendLabels)?.takeIf { acceptableSendPoint(it, keyboard) }
            val point = labeled ?: sendButtonPoint(
                input.second,
                host.screenWidth,
                host.screenHeight,
                host.density,
                keyboard?.top,
                host.overlayBounds(),
            )?.takeIf { acceptableSendPoint(it, keyboard) }
            if (point == null) {
                attempts += "没有避开浮层和键盘的发送位置"
                return filled.copy(attempts = attempts.toList(), sent = false, reflection = sendNotVerifiedReflection(text, attempts))
            }
            host.tap(point.first, point.second)
            kotlinx.coroutines.delay(420)
            if (confirmSent(text, inputRegion, bubbleRegion, before)) {
                attempts += "已点发送并在气泡中确认文字"
                return filled.copy(
                    method = "${filled.method}+send",
                    attempts = attempts.toList(),
                    sent = true,
                    sentBy = "send_button",
                    reflection = "",
                )
            }
            attempts += "点击发送后仍未在聊天气泡确认目标文字"
            return filled.copy(attempts = attempts.toList(), sent = false, reflection = sendNotVerifiedReflection(text, attempts))
        } finally {
            before?.recycle()
        }
    }

    private fun acceptableSendPoint(point: Pair<Int, Int>, keyboard: Rect?): Boolean {
        if (!usable(point)) return false
        if (keyboard != null && keyboard.contains(point.first, point.second)) return false
        return point.second >= (host.screenHeight * 0.5f).roundToInt()
    }

    private suspend fun confirm(
        expected: String,
        before: String?,
        beforeShot: Bitmap?,
        region: Rect,
    ): String? {
        val after = host.fieldText()
        if (nodeReadbackVerified(before, after, expected)) return "tree"
        if (expected.isNotEmpty() && screenshotShowsNewText(expected, beforeShot, region)) {
            val sample = before?.take(8).orEmpty()
            if (sample.isNotEmpty() && !expected.contains(sample) && screenshotStillShows(sample, region)) return null
            return "screenshot"
        }
        if (expected.isEmpty() && !before.isNullOrEmpty() && screenshotCleared(before, beforeShot, region)) return "screenshot"
        return null
    }

    private suspend fun confirmSent(
        expected: String,
        inputRegion: Rect,
        bubbleRegion: Rect,
        beforeShot: Bitmap?,
    ): Boolean {
        val sample = expected.trim().take(12)
        if (sample.isEmpty()) return false
        val fieldHasDraft = host.fieldText()?.contains(sample) == true
        val bubbleInTree = host.bubbleContains(expected) || host.bubbleContains(sample)
        val shot = host.capture()
        try {
            val sizes = fieldSizes()
            val inputHasDraft = shot != null && glyphs.find(shot, sample, inputRegion, sizes) != null
            val bubbleNew = shot != null &&
                glyphs.find(shot, sample, bubbleRegion, sizes) != null &&
                (beforeShot == null || glyphs.find(beforeShot, sample, bubbleRegion, sizes) == null)
            if (!(bubbleInTree || bubbleNew) || inputHasDraft) return false
            // A stale editable node still reporting the draft does not count once the input image is clear.
            if (fieldHasDraft && shot == null) return false
            return true
        } finally {
            shot?.recycle()
        }
    }

    private suspend fun locate(
        labels: List<String>,
        glyph: String,
        anchor: Pair<Int, Int>,
        bitmap: Bitmap? = null,
    ): Pair<Int, Int>? {
        host.labelCenter(labels)?.let { return it }
        val shot = bitmap ?: host.capture() ?: return null
        try {
            val menu = pasteMenuRegion(anchor.first, anchor.second, host.screenWidth, host.screenHeight, host.density)
            val sizes = floatArrayOf(14f, 16f, 13f, 18f).map { it * host.density.coerceAtLeast(1f) }.toFloatArray()
            for (label in listOf(glyph) + labels.filter { it != glyph }) {
                val hit = glyphs.find(shot, label, menu, sizes) ?: continue
                return (hit.left + hit.right) / 2 to (hit.top + hit.bottom) / 2
            }
            return null
        } finally {
            if (bitmap == null) shot.recycle()
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

    private suspend fun screenshotStillShows(sample: String, region: Rect): Boolean {
        val after = host.capture() ?: return false
        try {
            return glyphs.find(after, sample, region, fieldSizes()) != null
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
