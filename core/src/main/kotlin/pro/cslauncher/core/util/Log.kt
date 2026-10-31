package pro.cslauncher.core.util

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogRecord(
    val time: Instant,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val error: Throwable?
)

/**
 * Central logger. The core never decides where logs go: the Android layer adds a sink that
 * forwards to logcat and to a rotating file, the desktop tests add a sink that prints.
 *
 * Invariant: a [LogRecord] **always** carries a redacted message. Redaction happens on the way in,
 * not on the way out, so a secret cannot reach a sink, a crash report or the UI ring buffer.
 */
object CsLog {

    private val sinks = CopyOnWriteArrayList<(LogRecord) -> Unit>()

    @Volatile
    var minLevel: LogLevel = LogLevel.DEBUG

    /** Keep a bounded in-memory window so a crash report can include recent context. */
    private const val RING = 500
    private val ring = ArrayDeque<LogRecord>(RING)

    fun addSink(sink: (LogRecord) -> Unit) {
        sinks += sink
    }

    fun removeSink(sink: (LogRecord) -> Unit) {
        sinks -= sink
    }

    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null) {
        val rec = LogRecord(
            Instant.now(),
            level,
            tag,
            Redactor.redact(message),
            error?.let { SanitizedThrowable(it) }
        )
        synchronized(ring) {
            if (ring.size >= RING) ring.removeFirst()
            ring.addLast(rec)
        }
        if (level.ordinal < minLevel.ordinal) return
        for (sink in sinks) {
            // A broken sink must never break logging.
            runCatching { sink(rec) }
        }
    }

    fun d(tag: String, msg: String) = log(LogLevel.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(LogLevel.INFO, tag, msg)
    fun w(tag: String, msg: String, e: Throwable? = null) = log(LogLevel.WARN, tag, msg, e)
    fun e(tag: String, msg: String, e: Throwable? = null) = log(LogLevel.ERROR, tag, msg, e)

    fun recent(limit: Int = RING): List<LogRecord> = synchronized(ring) { ring.takeLast(limit) }

    fun dump(limit: Int = RING): String = recent(limit).joinToString("\n") { r ->
        val level = r.level.name.padEnd(5)
        "[${r.time}] $level ${r.tag}: ${r.message}" + (r.error?.let { " | ${it::class.java.simpleName}: ${it.message}" } ?: "")
    }

    fun clear() = synchronized(ring) { ring.clear() }
}

/** A throwable whose message and cause chain are already redacted and safe to persist. */
class SanitizedThrowable(original: Throwable) : RuntimeException(
    original.message?.let { Redactor.redact(it) } ?: original::class.java.simpleName
)

/**
 * Removes credentials from anything that can be logged, written to a crash report or shown in the
 * UI: Minecraft access tokens, Microsoft/MSA refresh tokens, Ely.by tokens, Authorization headers,
 * JWTs and the values that follow the launcher's own credential arguments.
 */
object Redactor {

    private const val MASK = "«redacted»"

    private val patterns = listOf(
        // Authorization: Bearer <token> / Authorization: <token>
        Regex("(?i)(authorization\\s*[=:]\\s*(?:bearer\\s+)?)[^\\s,;\"']+"),
        // access_token=..., accessToken":"...", refresh_token=...
        Regex("(?i)((?:access|refresh|client)[_-]?token\\s*[=:]\\s*[\"']?)([^\\s,\"'&}]+)"),
        // JSON fields: "accessToken":"..."
        Regex("(?i)(\"(?:accessToken|refreshToken|clientToken|session|token)\"\\s*:\\s*\")[^\"]*\""),
        // Our own command line flags, when the value ended up on the same string
        Regex("(?i)(--accessToken|--access-token|--clientId|--authSession)(\\s+|\\\\u0020)(\\S+)"),
        // JWTs (header.payload.signature, base64url)
        Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
        // Microsoft Live/MSA tokens are short opaque strings prefixed with these
        Regex("(?i)\\b(Msa|M.C5|Ew)([A-Za-z0-9]{48,})")
    )

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        for (p in patterns) {
            out = p.replace(out) { m ->
                val g = m.groupValues
                when (g.size) {
                    // (prefix)(value) — keep the label, hide the secret.
                    3 -> g[1] + MASK
                    // (prefix) only — the whole match is the credential.
                    2 -> if (g[1].isEmpty()) MASK else g[1] + MASK
                    else -> MASK
                }
            }
        }
        return out
    }

    /**
     * Redacts an argument list where the **value is a separate element** (`--accessToken`, `<token>`).
     * This is the shape that actually reaches the process launcher, so a per-string regex is not enough.
     */
    fun redactArgs(args: List<String>): List<String> {
        val secretFlags = setOf(
            "--accessToken", "--access-token", "--clientId", "--clientid", "--authSession",
            "--xuid", "--session", "--token"
        )
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            val a = args[i]
            out += redact(a)
            if (a in secretFlags && i + 1 < args.size) {
                out += MASK
                i += 2
                continue
            }
            i++
        }
        return out
    }

    /** Redacts any value that is still a placeholder the launcher substitutes late. */
    fun redactPlaceholders(text: String, placeholder: String): String = text.replace(placeholder, MASK)
}
