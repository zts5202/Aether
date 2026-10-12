package com.zhousl.aether.phoneuse

import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.SessionExecutionState

/** The run identity also pins Steer to the exact execution the user is watching. */
internal data class PhoneUseOverlayState(
    val sessionId: String,
    val runId: Long,
    val commentaryId: String = "",
    val commentary: String = "",
    val showCommentary: Boolean = true,
    val showEdgeGlow: Boolean = true,
)

internal fun foregroundPhoneUseOverlay(
    settings: AppSettings,
    states: Collection<SessionExecutionState>,
): PhoneUseOverlayState? {
    if (!settings.agentModeAuthorizationEnabled || settings.phoneUseVirtualDisplayEnabled) return null
    val active = states.filter { it.isRunning && it.phoneUseEnabled }
        .maxByOrNull { it.activeTurnStartedAtMillis ?: 0L } ?: return null
    return PhoneUseOverlayState(
        active.sessionId,
        active.activeTurnStartedAtMillis ?: return null,
        active.phoneUseCommentary?.id.orEmpty(),
        active.phoneUseCommentary?.text.orEmpty(),
        showCommentary = settings.phoneUseShowCommentary,
        showEdgeGlow = settings.phoneUseShowEdgeGlow,
    )
}

internal fun shouldShowPhoneUseOverlay(
    state: PhoneUseOverlayState?,
    foregroundPackage: String?,
    ownPackage: String,
    screenAvailable: Boolean,
): Boolean = state != null && screenAvailable && !foregroundPackage.isNullOrEmpty() && foregroundPackage != ownPackage
