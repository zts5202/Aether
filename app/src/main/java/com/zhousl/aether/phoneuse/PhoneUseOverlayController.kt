package com.zhousl.aether.phoneuse

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.widget.ImageView
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import com.zhousl.aether.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Two accessibility windows: an untouchable edge and a capsule-sized input surface.
 * Opening Steer does not open an Activity or pause the session being controlled.
 * All window mutations are on the main thread; service updates are coalesced there.
 */
internal class PhoneUseOverlayController(
    private val service: AccessibilityService,
    private val onSteer: (PhoneUseOverlayState, String) -> Boolean,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val ime = service.getSystemService(InputMethodManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val easing = PathInterpolator(.22f, .8f, .18f, 1f)
    private var edge: PhoneUseEdgeView? = null
    private var preparedEdge: PhoneUseEdgeView? = null
    private var capsule: FrameLayout? = null
    private var commentary: PhoneUseCommentaryView? = null
    private var editor: EditText? = null
    private var editorRow: LinearLayout? = null
    private var capsuleParams: WindowManager.LayoutParams? = null
    private var state: PhoneUseOverlayState? = null
    private var editingState: PhoneUseOverlayState? = null
    private var draft = ""
    private var visible = false
    private var disposed = false
    private var navBottom = 0
    private var imeBottom = 0
    private var keyboardBounds: Rect? = null
    private var keyboardLift = 0f
    private var targetKeyboardLift = 0f
    private var keyboardAnimator: ValueAnimator? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var suppression = 0
    private var inputSuppression = 0
    private var focusSuppression = 0
    private var generation = 0
    private var entranceAnimator: ValueAnimator? = null
    private val entranceBlur by lazy {
        if (Build.VERSION.SDK_INT >= 31) (0..18).map {
            if (it == 0) null else RenderEffect.createBlurEffect(it * density * .5f, it * density * .5f, Shader.TileMode.DECAL)
        } else emptyList()
    }

    val isEditing: Boolean get() = editingState != null

    fun update(next: PhoneUseOverlayState?, show: Boolean) {
        if (disposed) return
        val changedRun = state?.sessionId != next?.sessionId || state?.runId != next?.runId
        if (changedRun) {
            closeEditor()
            draft = ""
        }
        state = next
        if (next != null) {
            // agent_display starts in Aether before launching another app. Prepare native
            // shader/blur resources here, outside the edge's first visible animation frame.
            if (preparedEdge == null) preparedEdge = createEdgeView()
            entranceBlur.size
        }
        if (!show || next == null) { hide(); return }
        val appearing = !visible
        if (appearing) {
            generation++
            if (!attach()) return
            visible = true
            edge?.animate()?.cancel()
            edge?.alpha = 1f
            edge?.start()
        }
        commentary?.setCommentary(next.commentaryId, next.commentary)
        val hasText = next.commentary.isNotBlank() || isEditing
        capsule?.let { view ->
            if (hasText && (appearing || view.visibility != View.VISIBLE)) {
                view.visibility = View.VISIBLE
                view.alpha = 0f
                view.translationY = dp(20).toFloat()
                view.scaleX = .92f
                view.scaleY = .76f
                entranceAnimator?.cancel()
                if (Build.VERSION.SDK_INT >= 31) {
                    entranceAnimator = ValueAnimator.ofInt(18, 0).apply {
                        duration = motionDuration(520)
                        interpolator = easing
                        addUpdateListener { view.setRenderEffect(entranceBlur[it.animatedValue as Int]) }
                        start()
                    }
                }
                view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(motionDuration(560)).setInterpolator(easing).start()
            } else if (!hasText) view.visibility = View.INVISIBLE
        }
        applyWindowSuppression()
    }

    private fun attach(): Boolean {
        if (edge != null) return true
        updateScreenSize()
        val edgeView = preparedEdge ?: createEdgeView().also { preparedEdge = it }
        val host = PhoneUseCapsuleLayout(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val text = PhoneUseCommentaryView(service).apply {
            onHeightChanged = { positionCapsule() }
        }
        host.addView(text, FrameLayout.LayoutParams(-1, -2))
        val detector = GestureDetector(service, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean { openEditor(); return true }
        })
        text.setOnTouchListener { _, event -> detector.onTouchEvent(event); true }
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionCapsule() }
        val edgeParams = windowParams("$WINDOW_PREFIX edge", -1, -1).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val cardParams = windowParams("$WINDOW_PREFIX commentary", commentaryWidth(), -2)
        try {
            wm.addView(edgeView, edgeParams)
            wm.addView(host, cardParams)
        } catch (error: RuntimeException) {
            if (edgeView.parent != null) runCatching { wm.removeViewImmediate(edgeView) }
            if (host.parent != null) runCatching { wm.removeViewImmediate(host) }
            android.util.Log.w("PhoneUseOverlay", "Cannot attach accessibility feedback", error)
            return false
        }
        edge = edgeView
        capsule = host
        commentary = text
        capsuleParams = cardParams
        host.visibility = View.INVISIBLE
        positionCapsule()
        edgeView.requestApplyInsets()
        return true
    }

    private fun createEdgeView() = PhoneUseEdgeView(service).apply {
        onInsetsChanged = { updateInsets(it) }
        addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            screenWidth = right - left
            screenHeight = bottom - top
            updateKeyboardLift(animate = false)
            positionCapsule()
        }
    }

    private fun openEditor() {
        val target = state ?: return
        val host = capsule ?: return
        val params = capsuleParams ?: return
        if (isEditing || !visible) return
        editingState = target
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val orb = ImageView(service).apply {
            setImageDrawable(PhoneUseSteerDrawable(PhoneUseSteerDrawable.Kind.Orb))
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        val field = object : EditText(service) {
            override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { closeEditor(); return true }
                return super.onKeyPreIme(keyCode, event)
            }
        }.apply {
            background = null
            setTextColor(Color.rgb(32, 32, 38))
            setHintTextColor(Color.rgb(114, 111, 124))
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            hint = service.getString(R.string.phone_use_overlay_steer)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSingleLine = true
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setText(draft)
            setSelection(text.length)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) { sendSteer(); true } else false
            }
        }
        val send = ImageButton(service).apply {
            background = null
            setPadding(dp(9), dp(10), dp(9), dp(10))
            setOnClickListener { if (field.text.isNullOrBlank()) closeEditor() else sendSteer() }
        }
        fun updateAction() {
            val empty = field.text.isNullOrBlank()
            send.setImageDrawable(PhoneUseSteerDrawable(if (empty) PhoneUseSteerDrawable.Kind.Close else PhoneUseSteerDrawable.Kind.Send))
            send.contentDescription = service.getString(if (empty) R.string.common_cancel else R.string.common_send)
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateAction() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateAction()
        row.addView(orb, LinearLayout.LayoutParams(dp(36), dp(44)))
        row.addView(field, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(send, LinearLayout.LayoutParams(dp(40), dp(44)))
        editor = field
        editorRow = row
        commentary?.visibility = View.GONE
        host.addView(row, FrameLayout.LayoutParams(-1, -2))
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        wm.updateViewLayout(host, params)
        row.alpha = 0f
        row.translationY = dp(8).toFloat()
        row.animate().alpha(1f).translationY(0f).setDuration(motionDuration(300)).setInterpolator(easing).start()
        field.requestFocus()
        field.post { if (isEditing && !disposed) ime.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
        positionCapsule()
    }

    private fun sendSteer() {
        val target = editingState ?: return
        val text = editor?.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        if (onSteer(target, text)) {
            editor?.setText("")
            draft = ""
            closeEditor()
        } else {
            Toast.makeText(service, R.string.message_session_no_longer_running, Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeEditor() {
        if (!isEditing) return
        draft = editor?.text?.toString().orEmpty()
        editor?.let { ime.hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
        editorRow?.let { capsule?.removeView(it) }
        editor = null
        editorRow = null
        editingState = null
        commentary?.visibility = View.VISIBLE
        capsuleParams?.let { params ->
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            capsule?.let { if (it.isAttachedToWindow) wm.updateViewLayout(it, params) }
        }
        positionCapsule()
    }

    private fun updateInsets(insets: WindowInsets) {
        navBottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        else {
            @Suppress("DEPRECATION")
            insets.stableInsetBottom
        }
        updateKeyboardLift(animate = false)
        positionCapsule()
    }

    /** Accessibility reports display-space IME bounds even when a small overlay gets zero Insets. */
    fun updateKeyboardBounds(bounds: Rect?) {
        if (disposed || keyboardBounds == bounds) return
        keyboardBounds = bounds?.let(::Rect)
        updateKeyboardLift(animate = visible)
    }

    private fun updateKeyboardLift(animate: Boolean) {
        val bounds = keyboardBounds
        val width = editorWidth()
        val left = (screenWidth - width - dp(8)).coerceAtLeast(0)
        val overlaps = bounds != null && !bounds.isEmpty && bounds.right > left && bounds.left < left + width
        imeBottom = if (overlaps) (screenHeight - bounds.top).coerceAtLeast(0) else 0
        // Steer uses this lift. Commentary positioning ignores it and stays on the right rail.
        val lift = if (overlaps) (imeBottom + dp(12) - navBottom - dp(76)).coerceAtLeast(0).toFloat() else 0f
        if (lift == targetKeyboardLift) return
        targetKeyboardLift = lift
        keyboardAnimator?.cancel()
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            keyboardLift = lift
            positionCapsule()
        } else {
            keyboardAnimator = ValueAnimator.ofFloat(keyboardLift, lift).apply {
                duration = 240
                interpolator = easing
                addUpdateListener {
                    keyboardLift = it.animatedValue as Float
                    positionCapsule()
                }
                start()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun updateScreenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val size = android.graphics.Point()
            wm.defaultDisplay.getRealSize(size)
            screenWidth = size.x
            screenHeight = size.y
        }
    }

    /** Narrow status chip. Kept to the right half so it cannot span the screen. */
    private fun commentaryWidth(): Int {
        if (screenWidth <= 0) return dp(148)
        val maxRightRail = (screenWidth / 2 - dp(12)).coerceAtLeast(dp(96))
        return dp(148).coerceAtMost(maxRightRail)
    }

    private fun editorWidth(): Int = minOf((screenWidth - dp(24)).coerceAtLeast(dp(120)), dp(360))

    private fun positionCapsule() {
        val host = capsule ?: return
        val params = capsuleParams ?: return
        if (!host.isAttachedToWindow) return
        val editing = isEditing
        val width = if (editing) editorWidth() else commentaryWidth()
        val marginEnd = dp(8)
        val height = host.measuredHeight.takeIf { it > 0 } ?: if (editing) dp(52) else dp(40)
        val x = (screenWidth - width - marginEnd).coerceAtLeast(0)
        val y = if (editing) {
            val bottom = navBottom + dp(76) + keyboardLift.roundToInt()
            max(dp(40), screenHeight - bottom - height)
        } else {
            val topSafe = dp(64)
            val sendClearance = navBottom + dp(168)
            val maxY = (screenHeight - sendClearance - height).coerceAtLeast(topSafe)
            (screenHeight * 0.28f).roundToInt().coerceIn(topSafe, maxY)
        }
        if (params.width != width || params.x != x || params.y != y) {
            params.width = width
            params.x = x
            params.y = y
            wm.updateViewLayout(host, params)
        }
    }

    private fun hide() {
        if (!visible) return
        visible = false
        closeEditor()
        edge?.stop()
        val token = ++generation
        edge?.animate()?.alpha(0f)?.setDuration(motionDuration(220))?.start()
        capsule?.animate()?.alpha(0f)?.translationY(dp(8).toFloat())?.setDuration(motionDuration(180))?.start()
        // Never let an invisible capsule intercept touches during the exit animation.
        applyWindowSuppression()
        handler.postDelayed({ if (!visible && generation == token) detach() }, motionDuration(230))
    }

    private fun windowParams(name: String, width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        title = name
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        // FLAG_BLUR_BEHIND / blurBehindRadius blur the whole app behind an accessibility
        // overlay. The chip's frosted look is only the translucent fill in PhoneUseCapsuleLayout.
    }

    /** Right-edge chip rectangle, so a Send tap can miss it. Null when the chip is hidden. */
    fun touchBounds(): Rect? {
        if (!visible || disposed) return null
        val params = capsuleParams ?: return null
        val host = capsule ?: return null
        if (host.visibility != View.VISIBLE) return null
        val width = when {
            params.width > 0 -> params.width
            host.width > 0 -> host.width
            else -> return null
        }
        val height = when {
            host.height > 0 -> host.height
            params.height > 0 -> params.height
            else -> return null
        }
        return Rect(params.x, params.y, params.x + width, params.y + height)
    }

    /**
     * HyperOS drops clipboard writes from a background app. A focused accessibility
     * field can copy, then this window is removed before the WeChat gesture.
     */
    fun copyToClipboard(text: String): Boolean {
        if (text.isEmpty() || disposed) return false
        val manager = service.getSystemService(ClipboardManager::class.java) ?: return false
        val edit = EditText(service).apply {
            setText(text)
            setSelection(0, text.length)
            alpha = 0f
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
            title = "$WINDOW_PREFIX clipboard"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return try {
            wm.addView(edit, params)
            edit.requestFocus()
            edit.setSelection(0, text.length)
            edit.onTextContextMenuItem(android.R.id.copy)
            manager.setPrimaryClip(ClipData.newPlainText("aether-phone-use", text))
            true
        } catch (_: RuntimeException) {
            false
        } finally {
            if (edit.parent != null) runCatching { wm.removeViewImmediate(edit) }
        }
    }

    /** Hide feedback for a display screenshot without losing the Steer draft or window focus. */
    suspend fun <T> withoutVisuals(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        suppression++
        try {
            applyWindowSuppression()
            if (edge != null) delay(50) // allow SurfaceFlinger to consume the transparent window transaction
            block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                suppression--
                applyWindowSuppression()
            }
        }
    }

    /** Agent coordinates pass through the capsule; an overlapping IME yields temporarily. */
    suspend fun <T> withAgentGesture(y1: Int, y2: Int, block: suspend () -> T): T {
        val throughKeyboard = isEditing && imeBottom > dp(100) && max(y1, y2) >= screenHeight - imeBottom
        return withUnderlyingInput(throughKeyboard, block)
    }

    /** Global Back must reach the app, rather than dismissing the user's Steer composer. */
    suspend fun <T> withAgentNavigation(block: suspend () -> T): T = withUnderlyingInput(isEditing, block)

    private suspend fun <T> withUnderlyingInput(yieldKeyboard: Boolean, block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        inputSuppression++
        if (yieldKeyboard) focusSuppression++
        try {
            if (yieldKeyboard) editor?.let { ime.hideSoftInputFromWindow(it.windowToken, 0) }
            applyWindowSuppression()
            if (capsule != null) delay(if (yieldKeyboard) 220 else 32)
            block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                // Let global navigation finish changing the app before returning keyboard focus.
                if (yieldKeyboard) delay(180)
                inputSuppression--
                if (yieldKeyboard) focusSuppression--
                applyWindowSuppression()
                if (yieldKeyboard && isEditing && visible && !disposed) editor?.let { field ->
                    field.requestFocus()
                    field.post { if (editor === field && isEditing) ime.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
                }
            }
        }
    }

    private fun applyWindowSuppression() {
        if (disposed) return
        edge?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            val alpha = if (suppression > 0) 0f else 1f
            if (params.alpha != alpha) { params.alpha = alpha; wm.updateViewLayout(view, params) }
        }
        capsule?.let { view ->
            val params = capsuleParams ?: return@let
            val previousFlags = params.flags
            val previousAlpha = params.alpha
            params.alpha = if (suppression > 0) 0f else 1f
            params.flags = if (!isEditing || focusSuppression > 0) params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                else params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            val interactive = visible && suppression == 0 && inputSuppression == 0 && view.visibility == View.VISIBLE
            params.flags = if (interactive) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (params.flags != previousFlags || params.alpha != previousAlpha) wm.updateViewLayout(view, params)
        }
    }

    fun dispose() {
        if (disposed) return
        closeEditor()
        disposed = true
        visible = false
        generation++
        handler.removeCallbacksAndMessages(null)
        detach()
        preparedEdge = null
    }

    private fun detach() {
        entranceAnimator?.cancel()
        entranceAnimator = null
        keyboardAnimator?.cancel()
        keyboardAnimator = null
        keyboardLift = 0f
        targetKeyboardLift = 0f
        keyboardBounds = null
        listOfNotNull(edge, capsule).forEach {
            it.animate().cancel()
            runCatching { wm.removeViewImmediate(it) }
        }
        edge = null
        capsule = null
        commentary = null
        capsuleParams = null
        imeBottom = 0
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()
    private fun motionDuration(millis: Long): Long = if (ValueAnimator.areAnimatorsEnabled()) millis else 0

    companion object { const val WINDOW_PREFIX = "Aether Phone Use" }
}
