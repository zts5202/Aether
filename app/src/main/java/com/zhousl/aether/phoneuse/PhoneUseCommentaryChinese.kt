package com.zhousl.aether.phoneuse

/**
 * The phone-control bubble sits on top of the app being operated.
 * Model commentary is often English; the bubble itself always shows Simplified Chinese.
 * Chinese passages are kept. English is reduced to a short status line.
 */
internal fun phoneUseCommentaryForOverlay(raw: String): String {
    val text = raw.replace('\r', '\n').replace(Regex("[\\t ]+"), " ").trim()
    if (text.isEmpty()) return ""
    if (shouldKeepOriginalChinese(text)) return text
    val cues = overlayCues(text.replace('\n', ' ').lowercase())
    if (cues.isNotEmpty()) return cues.take(2).joinToString("，") + "。"
    val stripped = text.replace(Regex("[A-Za-z]+"), " ").replace(Regex("\\s+"), " ").trim()
    if (stripped.count(::isHan) >= 2) return stripped
    return "正在思考下一步。"
}

private fun shouldKeepOriginalChinese(text: String): Boolean {
    val han = text.count(::isHan)
    val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
    if (han == 0) return false
    if (latin == 0) return true
    return han >= 4 && han >= latin
}

private fun isHan(ch: Char): Boolean {
    val code = ch.code
    return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF
}

private fun overlayCues(lower: String): List<String> {
    val cues = mutableListOf<Pair<Int, String>>()
    fun add(priority: Int, matched: Boolean, label: String) {
        if (matched && cues.none { it.second == label }) cues += priority to label
    }
    val keyboard = hasStem(lower, "keyboard") || hasWord(lower, "ime")
    val send = hasStem(lower, "send")
    val blocked = hasStem(lower, "cover") || hasStem(lower, "block") || hasStem(lower, "obscur") ||
        hasStem(lower, "hide") || hasWord(lower, "hidden") || lower.contains("over the")
    val keyboardBlocked = keyboard && blocked
    val keyboardOnSend = keyboardBlocked && (send || hasStem(lower, "button"))
    val explicitEnter = lower.contains("enter key") || lower.contains("return key")
    val enterKey = explicitEnter || hasStem(lower, "enter")
    val press = hasStem(lower, "press")
    val tap = hasStem(lower, "tap") || hasStem(lower, "click")
    val screenshot = lower.contains("screenshot") || lower.contains("screen shot")
    val lookingShot = screenshot && hasAnyStem(lower, "check", "look", "see", "locate", "find", "inspect", "current")
    val wechat = hasStem(lower, "wechat") || lower.contains("微信")
    val typing = hasAnyStem(lower, "type", "typing", "fill", "write")
    val browser = hasStem(lower, "chrome") || hasStem(lower, "browser")

    add(0, keyboardOnSend, "键盘挡住了发送键")
    add(1, keyboardBlocked && !keyboardOnSend, "键盘挡住了目标")
    add(2, explicitEnter || (enterKey && (send || press)), "准备按回车键发送")
    add(3, lookingShot, "正在查看截图")
    add(4, screenshot && !lookingShot, "正在截取屏幕")
    add(5, hasStem(lower, "paste") || hasStem(lower, "clipboard"), "正在通过剪贴板粘贴")
    add(6, wechat && send && !keyboardOnSend && !(enterKey && (send || press)), "正在微信里发送")
    add(7, wechat && !send, "正在操作微信")
    add(8, typing && (hasAnyStem(lower, "input", "field") || lower.contains("text field") || lower.contains("edittext")), "正在输入文字")
    add(9, (tap || press) && send && !keyboardOnSend, "正在点击发送键")
    add(10, lower.contains("long press") || lower.contains("long-press") || lower.contains("longpress"), "正在长按")
    add(11, tap && !((tap || press) && send), "正在点击")
    add(12, hasStem(lower, "swipe") || hasStem(lower, "scroll"), "正在滑动页面")
    add(13, hasAnyStem(lower, "fail", "error", "unable") || lower.contains("cannot") || lower.contains("can't"), "上一步未成功")
    add(14, browser, "正在打开浏览器")
    add(15, !browser && hasAnyStem(lower, "open", "launch"), "正在打开页面")
    add(16, hasAnyStem(lower, "wait", "loading"), "正在等待页面")
    add(17, Regex("\\bback\\b").containsMatchIn(lower), "正在返回")
    add(18, hasStem(lower, "search"), "正在搜索")
    add(19, !screenshot && (hasWord(lower, "screen") || hasStem(lower, "page")), "正在查看页面")
    add(20, hasAnyStem(lower, "element", "node", "accessibility") || hasWord(lower, "tree"), "正在读取页面元素")
    return cues.sortedBy { it.first }.map { it.second }
}

private fun hasStem(text: String, token: String): Boolean =
    Regex("\\b${Regex.escape(token)}").containsMatchIn(text)

private fun hasWord(text: String, token: String): Boolean =
    Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(text)

private fun hasAnyStem(text: String, vararg tokens: String): Boolean = tokens.any { hasStem(text, it) }
