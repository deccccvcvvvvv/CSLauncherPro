package pro.cslauncher.core.net

import pro.cslauncher.core.util.CsLog
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** One HTTP exchange. Callers must close it. */
class HttpResponse(
    val code: Int,
    private val headers: Map<String, List<String>>,
    val body: InputStream,
    private val onClose: () -> Unit
) : AutoCloseable {

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key?.equals(name, ignoreCase = true) == true }?.value?.firstOrNull()

    val contentLength: Long get() = header("Content-Length")?.trim()?.toLongOrNull() ?: -1
    val contentType: String? get() = header("Content-Type")
    val acceptsRanges: Boolean get() = (header("Accept-Ranges") ?: "").contains("bytes", true)
    val isSuccess: Boolean get() = code in 200..299
    val isPartial: Boolean get() = code == 206

    /** Start offset of a `Content-Range: bytes 1234-5678/9999` response, used to validate resumes. */
    val contentRangeStart: Long? get() =
        header("Content-Range")?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()

    override fun close() {
        runCatching { body.close() }
        runCatching { onClose() }
    }
}

class HttpStatusException(val url: String, val status: Int, val detail: String = "") :
    IOException("HTTP $status for $url${if (detail.isBlank()) "" else ": $detail"}") {
    /** Network hiccups worth retrying; a 404 is not. */
    val isTransient: Boolean get() = status == 408 || status == 429 || status in 500..599
}

/**
 * Minimal HTTP abstraction so downloads and metadata can be tested against a local/fake origin and
 * replaced on Android if needed. Deliberately blocking: the download engine adds the coroutines.
 */
interface HttpTransport {

    fun open(
        url: String,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: ByteArray? = null
    ): HttpResponse

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        open(url, headers).use { r ->
            if (!r.isSuccess) {
                val detail = runCatching { r.body.readBytes().decodeToString().take(300) }.getOrDefault("")
                throw HttpStatusException(url, r.code, detail)
            }
            r.body.readBytes().decodeToString()
        }

    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        open(url, headers).use { r ->
            if (!r.isSuccess) throw HttpStatusException(url, r.code)
            r.body.readBytes()
        }

    fun postText(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String> = emptyMap()
    ): Pair<Int, String> =
        open(url, headers + ("Content-Type" to contentType), "POST", body.toByteArray()).use { r ->
            r.code to r.body.readBytes().decodeToString()
        }

    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> =
        postText(url, body, "application/json", headers)
}

/**
 * Default transport on `HttpURLConnection`: no third-party dependency, identical behaviour on a
 * desktop host and on Android (which is what keeps the core testable off-device).
 *
 * Security decisions:
 * * **HTTPS only** except loopback, which is what makes the local test origin possible without
 *   opening the door to a plaintext MITM on a device.
 * * **Redirects are followed manually** so an `https → http` downgrade is refused instead of
 *   silently performed by the platform.
 * * **Response bodies are capped** for metadata reads, so a hostile origin cannot exhaust memory.
 */
class UrlConnectionTransport(
    private val userAgent: String = "CSLauncherPro/1.0 (+https://github.com/cslauncherpro)",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val allowInsecureHttp: Boolean = false,
    private val maxMetadataBytes: Long = 32L shl 20
) : HttpTransport {

    private val etagCache = ConcurrentHashMap<String, String>()

    override fun open(
        url: String,
        headers: Map<String, String>,
        method: String,
        body: ByteArray?
    ): HttpResponse {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) { hop ->
            if (current.protocol != "https" && !(allowInsecureHttp || isLoopback(current.host))) {
                throw IOException("Refusing insecure URL $current")
            }
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.requestMethod = method
            c.setRequestProperty("User-Agent", userAgent)
            // Conditional GET: metadata is large and rarely changes between launches.
            etagCache[current.toString()]?.let { c.setRequestProperty("If-None-Match", it) }
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }

            val code = try {
                c.responseCode
            } catch (e: IOException) {
                c.disconnect()
                throw e
            }

            if (code in 301..308) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location.isNullOrBlank()) throw IOException("HTTP $code without a Location header from $current")
                val next = URL(current, location)
                if (current.protocol == "https" && next.protocol != "https" && !isLoopback(next.host)) {
                    throw IOException("Refusing redirect from HTTPS to ${next.protocol}://${next.host}")
                }
                CsLog.d("Http", "redirect $code → $next")
                current = next
                return@repeat
            }

            c.getHeaderField("ETag")?.let { etagCache[current.toString()] = it }
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: InputStream.nullInputStream())
            val headerMap = c.headerFields.filterKeys { it != null }
            return HttpResponse(code, headerMap, BoundedInputStream(stream, maxMetadataBytes)) { c.disconnect() }
        }
        throw IOException("Too many redirects for $url")
    }

    private fun isLoopback(host: String?) =
        host == "127.0.0.1" || host == "::1" || host == "localhost"

    companion object {
        private const val MAX_REDIRECTS = 6
    }
}

/**
 * Stream wrapper that refuses to read more than [limit] bytes. Metadata endpoints should never be
 * large; if one is, the launcher stops reading instead of dying with an OOM on a low-RAM phone.
 */
class BoundedInputStream(private val delegate: InputStream, private val limit: Long) : InputStream() {
    private var read = 0L

    override fun read(): Int {
        if (read >= limit) throw IOException("Response exceeded $limit bytes")
        val b = delegate.read()
        if (b >= 0) read++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (read >= limit) throw IOException("Response exceeded $limit bytes")
        val allowed = minOf(len.toLong(), limit - read).toInt()
        val n = delegate.read(b, off, allowed)
        if (n > 0) read += n
        return n
    }

    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()

    val consumed: Long get() = read
}

/** Retry helper shared by metadata fetchers (not by the download engine, which has its own). */
fun <T> retrying(
    attempts: Int = 3,
    baseDelayMs: Long = 500,
    onAttemptFailed: (Int, Exception) -> Unit = { _, _ -> },
    block: () -> T
): T {
    var last: Exception? = null
    for (attempt in 0 until attempts) {
        if (attempt > 0) Thread.sleep(baseDelayMs * (1L shl (attempt - 1)))
        try {
            return block()
        } catch (e: Exception) {
            last = e
            val transient = (e as? HttpStatusException)?.isTransient ?: (e is IOException)
            onAttemptFailed(attempt + 1, e)
            if (!transient) break
        }
    }
    throw last ?: IllegalStateException("retrying() failed without an exception")
}

/** Stable cache key for a URL, used by the on-disk metadata cache. */
fun cacheKeyFor(url: String): String =
    MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
