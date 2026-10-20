package com.zhousl.aether.runtime

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

internal const val AlpineOfficialRepository = "https://dl-cdn.alpinelinux.org/alpine"
internal const val AlpineChinaRepository = "https://mirrors.tuna.tsinghua.edu.cn/alpine"
internal const val AlpineSetupCompleteMarkerName = ".aether-setup-complete"

internal object AlpineNetworkMessages {
    const val Restricted =
        "Network access is disabled for Aether. In Settings > Apps > Aether, allow Mobile data and Wi-Fi, then retry Alpine initialization."
    const val Unreachable =
        "Cannot connect to the Alpine repositories. Connect to the internet and allow network access for Aether if Android asks, then retry. If access was previously denied, enable Mobile data and Wi-Fi in Settings > Apps > Aether."
}

internal data class AlpineNetworkConfiguration(
    val waitsForConnectivity: Boolean,
    val allowsCellularAccess: Boolean,
    val allowsExpensiveNetworkAccess: Boolean,
    val allowsConstrainedNetworkAccess: Boolean,
    val timeoutIntervalForRequestMillis: Int,
    val timeoutIntervalForResourceMillis: Int,
    val cacheEnabled: Boolean,
)

internal fun alpineNetworkConfiguration(): AlpineNetworkConfiguration = AlpineNetworkConfiguration(
    waitsForConnectivity = true,
    allowsCellularAccess = true,
    allowsExpensiveNetworkAccess = true,
    allowsConstrainedNetworkAccess = true,
    timeoutIntervalForRequestMillis = 15_000,
    timeoutIntervalForResourceMillis = 60_000,
    cacheEnabled = false,
)

internal data class AlpineNetworkRequest(
    val url: String,
    val method: String,
    val timeoutMillis: Int,
    val allowsCellularAccess: Boolean,
    val ignoresLocalCache: Boolean,
)

internal fun alpineApplicationNetworkSettingsIntent(packageName: String): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$packageName"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal fun alpineInternetPermissionRequest(
    url: String = "$AlpineOfficialRepository/",
): AlpineNetworkRequest {
    val configuration = alpineNetworkConfiguration()
    return AlpineNetworkRequest(
        url = url,
        method = "HEAD",
        timeoutMillis = configuration.timeoutIntervalForRequestMillis,
        allowsCellularAccess = configuration.allowsCellularAccess,
        ignoresLocalCache = !configuration.cacheEnabled,
    )
}

internal enum class AlpineNetworkDenial {
    None,
    DataNotAllowed,
    Cellular,
}

internal data class AlpineNetworkProbeResult(
    val statusCode: Int = 0,
    val denial: AlpineNetworkDenial = AlpineNetworkDenial.None,
    val failed: Boolean = false,
)

internal fun interface AlpineNetworkProbeClient {
    fun probe(request: AlpineNetworkRequest): AlpineNetworkProbeResult
}

internal class AlpineNetworkAccessException(
    val code: Int,
    message: String,
) : Exception(message)

/**
 * Probes the official Alpine CDN and the China mirror before package setup.
 * Either repository is enough. A denial is not cached, so a later retry can succeed.
 * Cellular, metered, and constrained networks are allowed.
 */
internal class AetherAlpineNetworkAccess(
    private val client: AlpineNetworkProbeClient,
    private val cellularRestricted: () -> Boolean = { false },
    private val restrictedMessage: () -> String = { AlpineNetworkMessages.Restricted },
    private val unreachableMessage: () -> String = { AlpineNetworkMessages.Unreachable },
) {
    fun check() {
        val requests = listOf(
            alpineInternetPermissionRequest("$AlpineOfficialRepository/"),
            alpineInternetPermissionRequest("$AlpineChinaRepository/"),
        )
        val executor = Executors.newFixedThreadPool(requests.size)
        val completion = ExecutorCompletionService<AlpineNetworkProbeResult>(executor)
        try {
            requests.forEach { request -> completion.submit { client.probe(request) } }
            var denied = false
            repeat(requests.size) {
                val result = completion.take().get()
                if (!result.failed && result.statusCode in 200..399) return
                if (result.denial == AlpineNetworkDenial.DataNotAllowed ||
                    result.denial == AlpineNetworkDenial.Cellular
                ) {
                    denied = true
                }
            }
            val restricted = denied || cellularRestricted()
            throw AlpineNetworkAccessException(
                code = if (restricted) 1 else 2,
                message = if (restricted) restrictedMessage() else unreachableMessage(),
            )
        } finally {
            executor.shutdownNow()
        }
    }
}

internal class AndroidAlpineNetworkProbeClient(
    private val context: Context,
) : AlpineNetworkProbeClient {
    override fun probe(request: AlpineNetworkRequest): AlpineNetworkProbeResult {
        val connection = open(request)
        return try {
            connection.connect()
            AlpineNetworkProbeResult(statusCode = connection.responseCode)
        } catch (_: IOException) {
            AlpineNetworkProbeResult(
                failed = true,
                denial = if (cellularDataRestricted(context)) {
                    AlpineNetworkDenial.Cellular
                } else {
                    AlpineNetworkDenial.None
                },
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun open(request: AlpineNetworkRequest): HttpURLConnection {
        val configuration = alpineNetworkConfiguration()
        val url = URL(request.url)
        val network = selectInternetNetwork(
            allowsCellular = request.allowsCellularAccess && configuration.allowsCellularAccess,
            waitMillis = if (configuration.waitsForConnectivity) request.timeoutMillis.toLong() else 0L,
        )
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        connection.requestMethod = request.method
        connection.connectTimeout = request.timeoutMillis
        connection.readTimeout = request.timeoutMillis
        connection.useCaches = !request.ignoresLocalCache
        connection.instanceFollowRedirects = true
        if (request.ignoresLocalCache) {
            connection.setRequestProperty("Cache-Control", "no-cache")
        }
        return connection
    }

    private fun selectInternetNetwork(allowsCellular: Boolean, waitMillis: Long): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val deadline = System.currentTimeMillis() + waitMillis.coerceAtLeast(0L)
        while (true) {
            val match = manager.allNetworks.firstOrNull { network ->
                networkAllowsSetup(manager.getNetworkCapabilities(network), allowsCellular)
            }
            if (match != null) return match
            if (System.currentTimeMillis() >= deadline) return manager.activeNetwork
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return manager.activeNetwork
            }
        }
    }

    companion object {
        fun cellularDataRestricted(context: Context): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val cellular = manager.allNetworks.mapNotNull { manager.getNetworkCapabilities(it) }
                .filter { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
            if (cellular.isEmpty()) return false
            return cellular.none { capabilities ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            }
        }
    }
}

private fun networkAllowsSetup(capabilities: NetworkCapabilities?, allowsCellular: Boolean): Boolean {
    if (capabilities == null) return false
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
    if (allowsCellular) return true
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}

internal fun alpineRootfsFilesPresent(runtimeRoot: java.io.File): Boolean {
    val rootfs = java.io.File(runtimeRoot, "rootfs")
    return rootfs.isDirectory &&
        pathExists(java.io.File(rootfs, "bin/sh")) &&
        pathExists(java.io.File(rootfs, "etc/alpine-release"))
}

internal fun alpineNodeBinaryPresent(runtimeRoot: java.io.File): Boolean {
    val rootfs = java.io.File(runtimeRoot, "rootfs")
    return listOf("usr/bin/node", "usr/bin/nodejs").any { relative ->
        val binary = java.io.File(rootfs, relative)
        binary.isFile || java.nio.file.Files.isSymbolicLink(binary.toPath())
    }
}

internal fun hasCompletedAlpineSetup(runtimeRoot: java.io.File): Boolean =
    alpineRootfsFilesPresent(runtimeRoot) &&
        java.io.File(runtimeRoot, AlpineSetupCompleteMarkerName).isFile

internal fun writeAlpineSetupCompleteMarker(runtimeRoot: java.io.File) {
    val marker = java.io.File(runtimeRoot, AlpineSetupCompleteMarkerName)
    marker.parentFile?.mkdirs()
    marker.writeText("")
}

/**
 * Existing installs finished before the marker existed. A rootfs that already
 * has Node is working and must not be extracted again; record that in place.
 */
internal fun adoptCompletedSetupMarker(runtimeRoot: java.io.File): Boolean {
    if (hasCompletedAlpineSetup(runtimeRoot)) return true
    if (!alpineRootfsFilesPresent(runtimeRoot) || !alpineNodeBinaryPresent(runtimeRoot)) return false
    writeAlpineSetupCompleteMarker(runtimeRoot)
    return hasCompletedAlpineSetup(runtimeRoot)
}

internal fun shouldReinstallAlpineRootfs(structureComplete: Boolean): Boolean = !structureComplete

private fun pathExists(file: java.io.File): Boolean =
    java.nio.file.Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
