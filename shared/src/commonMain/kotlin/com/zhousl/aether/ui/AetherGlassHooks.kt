package com.zhousl.aether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * Liquid-glass hook. The Android app provides an implementation backed by the Kyant backdrop library that refracts the
 * app's glow layer; shared code only asks for "a glass surface in this shape". When nothing is provided (light theme,
 * previews, tests) the old flat [fallback] colour is used, so behaviour is unchanged.
 */
@Stable
interface AetherGlass {
    /** [panel] = large readable surface (menus, drawer, cards): darker tint behind text. */
    @Composable
    fun Modifier.glass(shape: Shape, panel: Boolean): Modifier
}

val LocalAetherGlass = staticCompositionLocalOf<AetherGlass?> { null }

@Composable
fun Modifier.aetherGlassOr(shape: Shape, fallback: Color, panel: Boolean = true): Modifier {
    val glass = LocalAetherGlass.current ?: return this.clip(shape).background(fallback)
    return with(glass) { this@aetherGlassOr.glass(shape, panel) }.clip(shape)
}

/**
 * Same-window overlay host. Popups and dialogs normally live in their own window, where the glass cannot see the app's
 * glow layer. When a host is installed, [AetherAnchoredPopup] / [AetherOverlayDialog] draw inside the main window instead.
 */
class AetherOverlayHost {
    class Entry(val key: Any) {
        var content: @Composable () -> Unit by mutableStateOf({})
        var onDismiss: () -> Unit by mutableStateOf({})
    }

    val entries = mutableStateListOf<Entry>()

    fun dismissTop(): Boolean {
        val top = entries.lastOrNull() ?: return false
        top.onDismiss()
        return true
    }
}

val LocalAetherOverlayHost = staticCompositionLocalOf<AetherOverlayHost?> { null }

/** Renders all registered overlays; place once at the root, above the content, filling the window. */
@Composable
fun AetherOverlayHostContent(host: AetherOverlayHost) {
    host.entries.forEach { entry -> androidx.compose.runtime.key(entry.key) { entry.content() } }
}

@Composable
private fun RegisterOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit): Boolean {
    val host = LocalAetherOverlayHost.current ?: return false
    val entry = remember { AetherOverlayHost.Entry(Any()) }
    val latestContent by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismiss)
    SideEffect {
        entry.content = { latestContent() }
        entry.onDismiss = { latestDismiss() }
    }
    DisposableEffect(host) {
        entry.content = { latestContent() }
        entry.onDismiss = { latestDismiss() }
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
    return true
}

/** Placement of popup content: anchor and window in window pixels. */
fun interface AetherPopupPlacement {
    fun place(anchor: IntRect, window: IntSize, content: IntSize): IntOffset
}

/** Popup.alignment + offset semantics, relative to the anchor, clamped into the window. */
fun alignedPlacement(alignment: androidx.compose.ui.Alignment, offset: IntOffset): AetherPopupPlacement =
    AetherPopupPlacement { anchor, window, content ->
        val inAnchor = alignment.align(content, IntSize(anchor.width, anchor.height), LayoutDirection.Ltr)
        IntOffset(
            (anchor.left + inAnchor.x + offset.x).coerceIn(0, (window.width - content.width).coerceAtLeast(0)),
            (anchor.top + inAnchor.y + offset.y).coerceIn(0, (window.height - content.height).coerceAtLeast(0)),
        )
    }

/**
 * Drop-in for `Popup(alignment, offset, onDismissRequest)`: anchored to the parent layout. Drawn in the main window
 * when an [AetherOverlayHost] is installed (so liquid glass works), otherwise falls back to a real [Popup].
 */
@Composable
fun AetherAnchoredPopup(
    placement: AetherPopupPlacement,
    onDismissRequest: () -> Unit,
    focusable: Boolean = true,
    content: @Composable () -> Unit,
) {
    var anchor by remember { mutableStateOf<IntRect?>(null) }
    // Zero-size probe that reports the parent's bounds (what Popup anchors to).
    Box(
        Modifier.size(0.dp).onGloballyPositioned { c ->
            val b = (c.parentLayoutCoordinates ?: c).boundsInRoot()
            anchor = IntRect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
        }
    )
    val registered = RegisterOverlay(onDismissRequest) {
        val a = anchor ?: return@RegisterOverlay
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismissRequest,
            )
        )
        Layout(content = {
            Box(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) { content() }
        }) { measurables, constraints ->
            val p = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
            val window = IntSize(constraints.maxWidth, constraints.maxHeight)
            val pos = placement.place(a, window, IntSize(p.width, p.height))
            layout(constraints.maxWidth, constraints.maxHeight) { p.place(pos) }
        }
    }
    if (!registered) {
        Popup(
            popupPositionProvider = object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                    placement.place(anchorBounds, windowSize, popupContentSize)
            },
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = focusable, dismissOnBackPress = true, dismissOnClickOutside = true),
            content = content,
        )
    }
}

/** Centered modal dialog with a dim scrim, drawn in the main window when a host exists, else a platform Dialog. */
@Composable
fun AetherOverlayDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val registered = RegisterOverlay(onDismissRequest) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismissRequest,
            ),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Box(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) { content() }
        }
    }
    if (!registered) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest) { content() }
    }
}
