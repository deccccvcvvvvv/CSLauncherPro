package pro.cslauncher.core.util

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * SHA-1 / SHA-256 / SHA-512 helpers.
 *
 * Every downloaded artefact in the launcher passes through here: a file only replaces an existing
 * one after its hash matched, so a truncated or tampered download can never masquerade as installed.
 */
object Hashing {

    fun digest(path: Path, algorithm: String): String =
        Files.newInputStream(path).use { digest(it, algorithm) }

    fun digest(input: InputStream, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        val buf = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            if (read > 0) md.update(buf, 0, read)
        }
        return md.digest().toHex()
    }

    fun sha1(path: Path) = digest(path, "SHA-1")
    fun sha256(path: Path) = digest(path, "SHA-256")
    fun sha512(path: Path) = digest(path, "SHA-512")

    fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).toHex()
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    fun sha512(bytes: ByteArray): String = MessageDigest.getInstance("SHA-512").digest(bytes).toHex()

    fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            out[i * 2] = digits[v ushr 4]
            out[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(out)
    }
}

/**
 * Expected integrity of a remote file. Any subset of the hashes may be present; an empty
 * [Integrity] means "no published checksum", which the downloader treats as size-only.
 */
data class Integrity(
    val sha1: String? = null,
    val sha256: String? = null,
    val sha512: String? = null,
    val size: Long? = null
) {
    val hasChecksum get() = sha1 != null || sha256 != null || sha512 != null

    /**
     * Returns null when [path] satisfies every known constraint, otherwise a short reason the
     * install report can show. [fullHash] = false does the cheap size-only pass, which is what a
     * pre-launch check uses; a repair always hashes.
     */
    fun check(path: Path, fullHash: Boolean = true): String? {
        if (!Files.isRegularFile(path)) return "file missing"
        val actualSize = try {
            Files.size(path)
        } catch (e: Exception) {
            return "unreadable: ${e.message}"
        }
        if (size != null && size >= 0 && actualSize != size) {
            if (actualSize < size) return "incomplete ($actualSize of $size bytes)"
            return "wrong size ($actualSize, expected $size)"
        }
        if (actualSize == 0L && (size == null || size > 0)) return "file is empty"
        if (!fullHash) return null
        sha1?.takeIf { it.isNotBlank() }?.let {
            val actual = Hashing.sha1(path)
            if (!actual.equals(it, ignoreCase = true)) return "sha1 $actual != expected $it"
        }
        sha256?.takeIf { it.isNotBlank() }?.let {
            val actual = Hashing.sha256(path)
            if (!actual.equals(it, ignoreCase = true)) return "sha256 mismatch"
        }
        sha512?.takeIf { it.isNotBlank() }?.let {
            val actual = Hashing.sha512(path)
            if (!actual.equals(it, ignoreCase = true)) return "sha512 mismatch"
        }
        return null
    }

    companion object {
        val NONE = Integrity()

        /** Mojang publishes SHA-1 only; loaders and modpacks often publish SHA-256/SHA-512. */
        fun of(sha1: String? = null, sha256: String? = null, sha512: String? = null, size: Long? = null) =
            Integrity(
                sha1 = sha1?.takeIf { it.isNotBlank() },
                sha256 = sha256?.takeIf { it.isNotBlank() },
                sha512 = sha512?.takeIf { it.isNotBlank() },
                size = size?.takeIf { it >= 0 }
            )

        /** Modrinth/CurseForge style: "sha1:abc123" or bare hex strings of varying length. */
        fun fromHashString(value: String?): Integrity {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return NONE
            val (algo, hex) = if (v.contains(':')) v.substringBefore(':') to v.substringAfter(':') else "sha512" to v
            return when (algo.lowercase()) {
                "sha1" -> Integrity(sha1 = hex)
                "sha256" -> Integrity(sha256 = hex)
                "sha512" -> Integrity(sha512 = hex)
                "md5" -> NONE
                else -> when (hex.length) {
                    40 -> Integrity(sha1 = hex)
                    64 -> Integrity(sha256 = hex)
                    128 -> Integrity(sha512 = hex)
                    else -> NONE
                }
            }
        }
    }
}
