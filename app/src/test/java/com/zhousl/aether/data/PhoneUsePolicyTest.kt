package com.zhousl.aether.data

import org.junit.Assert.*
import org.junit.Test

class PhoneUsePolicyTest {
    @Test fun orientationOverridesRequireVirtualDisplayStartOrLaunch() {
        val foreground = AppSettings()
        val virtual = foreground.copy(phoneUseVirtualDisplayEnabled = true,
            agentModeAuthorizationMethod = AgentModeAuthorizationMethod.Shizuku)
        assertNull(phoneUseDisplayOrientationRequested(foreground, "start", org.json.JSONObject()))
        val request = org.json.JSONObject().put("orientation", "landscape")
        assertEquals(AgentModeDisplayOrientation.Landscape, phoneUseDisplayOrientationRequested(virtual, "launch", request))
        assertEquals(AgentModeDisplayOrientation.Device,
            phoneUseDisplayOrientationRequested(virtual, "start", org.json.JSONObject().put("orientation", "device")))
        assertThrows(IllegalArgumentException::class.java) { phoneUseDisplayOrientationRequested(foreground, "launch", request) }
        for (action in listOf("observe", "screenshot", "tap", "stop", "status")) {
            assertThrows(IllegalArgumentException::class.java) { phoneUseDisplayOrientationRequested(virtual, action, request) }
        }
        for (value in listOf("auto", "", 1, org.json.JSONObject.NULL)) {
            assertThrows(IllegalArgumentException::class.java) {
                phoneUseDisplayOrientationRequested(virtual, "start", org.json.JSONObject().put("orientation", value))
            }
        }
        assertEquals(AgentModeDisplayOrientation.Device, virtual.agentModeDisplayOrientation)
    }

    @Test fun menuClickRetriesRevokedAccessInAnAlreadySelectedConversation() {
        val policy = PhoneUsePermissionRequestPolicy()
        assertTrue(policy.shouldRequest(true, false, manuallyEnabled = true))
        // Dismiss, send a normal message, and stop: the explicit menu action must still retry.
        val selected = phoneUseSelectionAfterMenuClick(selected = true, enabled = true, granted = false)
        assertTrue(selected)
        assertTrue(policy.shouldRequest(true, false, manuallyEnabled = selected))
        assertTrue(phoneUseSelectionAfterMenuClick(selected = false, enabled = true, granted = false))
        assertFalse(phoneUseSelectionAfterMenuClick(selected = true, enabled = true, granted = true))
        assertTrue(phoneUseSelectionAfterMenuClick(selected = false, enabled = true, granted = true))
        assertFalse(phoneUseSelectionAfterMenuClick(selected = false, enabled = false, granted = false))
    }

    @Test fun returnToAetherRequiresAnExplicitForegroundStop() {
        val settings = AppSettings()
        assertFalse(phoneUseReturnToAetherRequested(settings, "stop", org.json.JSONObject()))
        assertFalse(phoneUseReturnToAetherRequested(settings, "stop", org.json.JSONObject().put("return_to_aether", false)))
        val request = org.json.JSONObject().put("return_to_aether", true)
        assertTrue(phoneUseReturnToAetherRequested(settings, "stop", request))
        assertThrows(IllegalArgumentException::class.java) { phoneUseReturnToAetherRequested(settings, "tap", request) }
        assertThrows(IllegalArgumentException::class.java) {
            phoneUseReturnToAetherRequested(settings.copy(phoneUseVirtualDisplayEnabled = true), "stop", request)
        }
        assertThrows(IllegalArgumentException::class.java) {
            phoneUseReturnToAetherRequested(settings, "stop", org.json.JSONObject().put("return_to_aether", "true"))
        }
    }

    @Test fun permissionPromptRespectsDisabledChoiceAndExplicitReenable() {
        val policy = PhoneUsePermissionRequestPolicy()
        assertFalse(policy.shouldRequest(enabled = false, granted = false))
        assertTrue(policy.shouldRequest(enabled = true, granted = false))
        assertFalse(policy.shouldRequest(enabled = true, granted = false))
        assertFalse(policy.shouldRequest(enabled = false, granted = false))
        assertTrue(policy.shouldRequest(enabled = true, granted = false, manuallyEnabled = true))
        assertFalse(policy.shouldRequest(enabled = true, granted = true, manuallyEnabled = true))
    }

    @Test fun missingPermissionCanBeRequestedAgainOnNextLaunch() {
        assertTrue(PhoneUsePermissionRequestPolicy().shouldRequest(true, false))
        assertTrue(PhoneUsePermissionRequestPolicy().shouldRequest(true, false))
    }

    @Test fun observationsFollowUserPreferenceAndGateExplicitAlternatives() {
        val settings = AppSettings(phoneUseVirtualDisplayEnabled = true,
            phoneUseInputStrategy = PhoneUseInputStrategy.ScreenshotFirst,
            phoneUseAllowStrategySwitch = false)
        assertEquals(PhoneUseInputStrategy.ScreenshotFirst, resolvePhoneUseInputStrategy(settings, "observe", ""))
        assertEquals(PhoneUseInputStrategy.ScreenshotFirst, resolvePhoneUseInputStrategy(settings, "click", ""))
        assertThrows(IllegalArgumentException::class.java) { resolvePhoneUseInputStrategy(settings, "tree", "") }
        assertThrows(IllegalArgumentException::class.java) { resolvePhoneUseInputStrategy(settings, "observe", "element_tree_first") }
        assertEquals(PhoneUseInputStrategy.ElementTreeFirst,
            resolvePhoneUseInputStrategy(settings.copy(phoneUseAllowStrategySwitch = true), "tree", ""))
    }

    @Test fun outputPreferenceChoosesWhenBothTargetsExistAndRejectsDisabledFallback() {
        val elements = AppSettings(phoneUseVirtualDisplayEnabled = true, phoneUseAllowStrategySwitch = false)
        assertTrue(phoneUseUsesElementAction(elements, "click", true, true, ""))
        assertThrows(IllegalArgumentException::class.java) { phoneUseUsesElementAction(elements, "click", false, true, "") }
        // A stray node_id cannot disguise a swipe as an element operation.
        assertThrows(IllegalArgumentException::class.java) { phoneUseUsesElementAction(elements, "swipe", true, true, "") }
        val gestures = elements.copy(phoneUseOutputStrategy = PhoneUseOutputStrategy.GestureFirst)
        assertFalse(phoneUseUsesElementAction(gestures, "click", true, true, ""))
        assertThrows(IllegalArgumentException::class.java) { phoneUseUsesElementAction(gestures, "click", true, false, "") }
        assertThrows(IllegalArgumentException::class.java) { phoneUseUsesElementAction(gestures, "click", true, true, "element_first") }
    }

    @Test fun allowedFallbackAndExplicitOverrideDoNotAlterPreferences() {
        val settings = AppSettings(phoneUseVirtualDisplayEnabled = true,
            phoneUseOutputStrategy = PhoneUseOutputStrategy.GestureFirst)
        assertTrue(phoneUseUsesElementAction(settings, "click", true, true, "element_first"))
        assertTrue(phoneUseUsesElementAction(settings, "click", true, false, ""))
        assertEquals(PhoneUseOutputStrategy.GestureFirst, settings.phoneUseOutputStrategy)
    }

    @Test fun realScreenForcesTreeFirstAndRejectsStrategyBypass() {
        val settings = AppSettings(
            phoneUseInputStrategy = PhoneUseInputStrategy.ScreenshotFirst,
            phoneUseOutputStrategy = PhoneUseOutputStrategy.GestureFirst,
            phoneUseAllowStrategySwitch = true,
        )
        assertTrue(phoneUseTargetsRealScreen(settings))
        assertEquals(PhoneUseInputStrategy.ElementTreeFirst, resolvePhoneUseInputStrategy(settings, "observe", ""))
        assertEquals(PhoneUseInputStrategy.ElementTreeFirst, resolvePhoneUseInputStrategy(settings, "start", ""))
        assertEquals(PhoneUseInputStrategy.ElementTreeFirst, resolvePhoneUseInputStrategy(settings, "click", "element_tree_first"))
        assertThrows(IllegalArgumentException::class.java) { resolvePhoneUseInputStrategy(settings, "screenshot", "") }
        assertThrows(IllegalArgumentException::class.java) { resolvePhoneUseInputStrategy(settings, "observe", "screenshot_first") }
        assertThrows(IllegalArgumentException::class.java) {
            phoneUseUsesElementAction(settings, "click", hasNode = true, hasCoordinates = true, override = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            phoneUseUsesElementAction(settings, "click", hasNode = true, hasCoordinates = false, override = "gesture_first")
        }
        assertThrows(IllegalArgumentException::class.java) {
            phoneUseUsesElementAction(settings, "text", hasNode = false, hasCoordinates = true, override = "")
        }
        assertTrue(phoneUseUsesElementAction(settings, "click", hasNode = true, hasCoordinates = false, override = ""))
        assertTrue(phoneUseUsesElementAction(settings, "scroll", hasNode = true, hasCoordinates = false, override = "element_first"))
        assertFalse(phoneUseUsesElementAction(settings, "swipe", hasNode = false, hasCoordinates = true, override = ""))
        assertEquals(PhoneUseInputStrategy.ScreenshotFirst, settings.phoneUseInputStrategy)
        assertTrue(settings.phoneUseAllowStrategySwitch)
        val arguments = org.json.JSONObject().put("x", 10).put("node_id", "legacy")
        assertEquals("e1", phoneUseTargetElementId(org.json.JSONObject().put("eid", "e1").put("node_id", "legacy")))
        assertEquals("legacy", phoneUseTargetElementId(arguments))
        assertTrue(realScreenModelCoordinatesSupplied(arguments))
        assertFalse(realScreenModelCoordinatesSupplied(org.json.JSONObject().put("eid", "e1")))
    }
}
