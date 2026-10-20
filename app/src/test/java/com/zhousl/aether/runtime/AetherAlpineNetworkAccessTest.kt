package com.zhousl.aether.runtime

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AetherAlpineNetworkAccessTest {
    @Test
    fun internetPermissionProbeUsesFiniteHeadRequest() {
        val request = alpineInternetPermissionRequest()

        assertEquals("https://dl-cdn.alpinelinux.org/alpine/", request.url)
        assertEquals("HEAD", request.method)
        assertEquals(15_000, request.timeoutMillis)
        assertTrue(request.allowsCellularAccess)
        assertTrue(request.ignoresLocalCache)
    }

    @Test
    fun alpineNetworkCheckWaitsForPermissionAndAllowsCellular() {
        val config = alpineNetworkConfiguration()

        assertTrue(config.waitsForConnectivity)
        assertTrue(config.allowsCellularAccess)
        assertTrue(config.allowsExpensiveNetworkAccess)
        assertTrue(config.allowsConstrainedNetworkAccess)
        assertEquals(60_000, config.timeoutIntervalForResourceMillis)
        assertFalse(config.cacheEnabled)
    }

    @Test
    fun alpineNetworkCheckCanRetryAfterOfflineFailure() {
        var online = false
        val access = AetherAlpineNetworkAccess(
            client = {
                if (online) {
                    AlpineNetworkProbeResult(statusCode = 200)
                } else {
                    AlpineNetworkProbeResult(failed = true)
                }
            },
        )

        assertNetworkFailure(access, expectedCode = 2)
        online = true
        access.check()
    }

    @Test
    fun alpineNetworkCheckAcceptsReachableMirror() {
        val access = AetherAlpineNetworkAccess(
            client = { request ->
                val status = if (request.url.contains("mirrors.tuna.tsinghua.edu.cn")) 200 else 503
                AlpineNetworkProbeResult(statusCode = status)
            },
        )

        access.check()
    }

    @Test
    fun alpineNetworkCheckExplainsDeniedPermission() {
        val access = AetherAlpineNetworkAccess(
            client = {
                AlpineNetworkProbeResult(
                    failed = true,
                    denial = AlpineNetworkDenial.DataNotAllowed,
                )
            },
        )

        val error = assertNetworkFailure(access, expectedCode = 1)
        assertTrue(error.message!!.contains("Settings > Apps > Aether"))
    }

    @Test
    fun alpineNetworkCheckExplainsRestrictedCellularData() {
        val access = AetherAlpineNetworkAccess(
            client = { AlpineNetworkProbeResult(failed = true) },
            cellularRestricted = { true },
        )

        val error = assertNetworkFailure(access, expectedCode = 1)
        assertTrue(error.message!!.contains("Mobile data"))
    }

    @Test
    fun alpineNetworkCheckDoesNotProceedUntilRequestCompletes() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val access = AetherAlpineNetworkAccess(
            client = {
                started.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
                AlpineNetworkProbeResult(statusCode = 200)
            },
        )
        val proceeded = AtomicBoolean(false)
        val worker = Thread {
            access.check()
            proceeded.set(true)
        }
        worker.start()

        assertTrue(started.await(3, TimeUnit.SECONDS))
        assertFalse(proceeded.get())
        release.countDown()
        worker.join(3_000)
        assertTrue(proceeded.get())
    }

    private fun assertNetworkFailure(
        access: AetherAlpineNetworkAccess,
        expectedCode: Int,
    ): AlpineNetworkAccessException {
        try {
            access.check()
            fail("Network check should have failed")
        } catch (error: AlpineNetworkAccessException) {
            assertEquals(expectedCode, error.code)
            return error
        }
        error("unreachable")
    }
}

class AlpineSetupMarkerTest {
    @Test
    fun partialAlpineRootIsNotInitializedAfterRelaunch() {
        val root = createTempRoot()
        try {
            File(root, "rootfs/bin").mkdirs()
            File(root, "rootfs/bin/sh").writeText("")
            File(root, "rootfs/etc").mkdirs()
            File(root, "rootfs/etc/alpine-release").writeText("3.23.4\n")

            assertFalse(hasCompletedAlpineSetup(root))
            File(root, AlpineSetupCompleteMarkerName).writeText("")
            assertTrue(hasCompletedAlpineSetup(root))
            assertTrue(File(root, "rootfs/etc/alpine-release").delete())
            assertFalse(hasCompletedAlpineSetup(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun workingRootfsWithoutMarkerIsAdoptedInsteadOfReinstalled() {
        val root = createTempRoot()
        try {
            File(root, "rootfs/bin").mkdirs()
            File(root, "rootfs/bin/sh").writeText("")
            File(root, "rootfs/etc").mkdirs()
            File(root, "rootfs/etc/alpine-release").writeText("3.23.4\n")
            File(root, "rootfs/usr/bin").mkdirs()
            File(root, "rootfs/usr/bin/node").writeText("")

            assertFalse(hasCompletedAlpineSetup(root))
            assertFalse(shouldReinstallAlpineRootfs(structureComplete = true))
            assertTrue(adoptCompletedSetupMarker(root))
            assertTrue(hasCompletedAlpineSetup(root))
            assertTrue(File(root, AlpineSetupCompleteMarkerName).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun freshRootfsWithoutNodeIsNotMarkedCompleteAndIsNotReinstalled() {
        val root = createTempRoot()
        try {
            File(root, "rootfs/bin").mkdirs()
            File(root, "rootfs/bin/sh").writeText("")
            File(root, "rootfs/etc").mkdirs()
            File(root, "rootfs/etc/alpine-release").writeText("3.23.4\n")

            assertFalse(adoptCompletedSetupMarker(root))
            assertFalse(hasCompletedAlpineSetup(root))
            assertFalse(shouldReinstallAlpineRootfs(structureComplete = alpineRootfsFilesPresent(root)))
            assertTrue(shouldReinstallAlpineRootfs(structureComplete = false))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createTempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "alpine-marker-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }
}
