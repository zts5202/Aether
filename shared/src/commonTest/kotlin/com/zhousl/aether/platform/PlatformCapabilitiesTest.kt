package com.zhousl.aether.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformCapabilitiesTest {
    @Test
    fun androidEnablesLocalRuntimeAndPhoneUse() {
        assertTrue(PlatformCapabilities.Android.alpine)
        assertTrue(PlatformCapabilities.Android.alpineChrome)
        assertTrue(PlatformCapabilities.Android.stdioMcp)
        assertTrue(PlatformCapabilities.Android.scriptExtensions)
        assertTrue(PlatformCapabilities.Android.termux)
        assertTrue(PlatformCapabilities.Android.runtimeSelection)
        assertTrue(PlatformCapabilities.Android.agentMode)
        assertTrue(PlatformCapabilities.Android.scheduledTasks)
        assertTrue(PlatformCapabilities.Android.persistentBackground)
        assertTrue(PlatformCapabilities.Android.localNotifications)
        assertTrue(PlatformCapabilities.Android.nativeMods)
        assertTrue(PlatformCapabilities.Android.layeredScreenTransitions)
        assertFalse(PlatformCapabilities.Android.supportsTabletLayout)
    }
}
