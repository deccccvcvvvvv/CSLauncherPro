package pro.cslauncher.core

import pro.cslauncher.core.download.DownloadManager
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.net.HttpResponse
import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.util.Hashing
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** A transport backed by an in-memory URL map: no network in tests, and failures are reproducible. */
class FakeTransport(
    private val routes: MutableMap<String, ByteArray> = ConcurrentHashMap(),
    private val statuses: MutableMap<String, Int> = ConcurrentHashMap()
) : HttpTransport {

    val requestCount = java.util.concurrent.atomic.AtomicInteger()
    val requestedHeaders = mutableListOf<Map<String, String>>()

    fun put(url: String, body: String) = apply { routes[url] = body.toByteArray() }
    fun putBytes(url: String, body: ByteArray) = apply { routes[url] = body }
    fun status(url: String, code: Int) = apply { statuses[url] = code }

    override fun open(url: String, headers: Map<String, String>, method: String, body: ByteArray?): HttpResponse {
        requestCount.incrementAndGet()
        requestedHeaders += headers
        val status = statuses[url] ?: 200
        val bytes = routes[url] ?: ByteArray(0)
        return HttpResponse(
            code = status,
            headers = mapOf("Content-Length" to listOf(bytes.size.toString())),
            body = ByteArrayInputStream(bytes),
            onClose = {}
        )
    }

    fun downloads(): DownloadManager = DownloadManager(this, maxConcurrency = 3, maxAttempts = 2, retryBaseDelayMs = 1)
}

object TestWorld {
    fun dirs(): LauncherDirs {
        val root = Files.createTempDirectory("cs-test")
        val dirs = LauncherDirs(root)
        dirs.createBase()
        return dirs
    }

    fun platform(android: Boolean = false): PlatformInfo =
        if (android) PlatformInfo.android(abi = "arm64-v8a", release = "14", api = 34, glesVersion = 32, vulkanApi = "1.1.128")
        else PlatformInfo.host()

    fun sha1(bytes: ByteArray): String = Hashing.sha1(bytes)
    fun sha256(bytes: ByteArray): String = Hashing.sha256(bytes)
    fun tmpFile(content: String, suffix: String = ".txt"): Path {
        val f = Files.createTempFile("cs-test", suffix)
        Files.writeString(f, content)
        return f
    }
}
