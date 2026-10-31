package pro.cslauncher.core.util

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Filesystem primitives used everywhere else.
 *
 * Two rules are enforced here rather than at every call site:
 * 1. **Writes that matter are atomic** — temp file in the same directory, then an atomic move.
 *    A launcher that leaves a half-written `profiles.json` behind destroys user data.
 * 2. **Deletes are rooted** — [deleteTreeWithin] refuses to touch anything outside the directory
 *    it was given, so a bad relative path in metadata can never remove user files.
 */
object FsUtil {

    fun writeAtomic(target: Path, bytes: ByteArray) {
        val parent = target.parent ?: throw IOException("No parent directory for $target")
        Files.createDirectories(parent)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp-" + System.nanoTime())
        try {
            Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                // Some Android filesystems refuse ATOMIC_MOVE across mounts; fall back to a plain move.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun writeAtomic(target: Path, text: String) = writeAtomic(target, text.toByteArray(Charsets.UTF_8))

    fun readText(p: Path): String = String(Files.readAllBytes(p), Charsets.UTF_8)

    fun readTextOrNull(p: Path): String? = runCatching { readText(p) }.getOrNull()

    fun writeText(p: Path, text: CharSequence, vararg options: java.nio.file.OpenOption): Path =
        Files.write(p, text.toString().toByteArray(Charsets.UTF_8), *options)

    /** Free space in bytes, or 0 when the platform cannot answer (never throws). */
    fun usableSpace(p: Path): Long {
        var probe: java.io.File? = p.toAbsolutePath().toFile()
        while (probe != null) {
            try {
                if (probe.exists()) return probe.usableSpace
            } catch (_: Exception) {
                // keep walking up
            }
            probe = probe.parentFile
        }
        return 0L
    }

    /** Recursive size; symbolic links are not followed so a link cannot inflate the report. */
    fun sizeOf(dir: Path): Long {
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return 0L
        var total = 0L
        try {
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
            })
        } catch (_: Exception) {
            // A vanished directory is reported as 0 rather than crashing the storage screen.
        }
        return total
    }

    /**
     * Deletes [target] only when it lives inside [allowedRoot]. Symlinks are unlinked, never
     * followed, and the root itself may be deleted when it is a direct child of the allowance.
     */
    fun deleteTreeWithin(allowedRoot: Path, target: Path) {
        val root = allowedRoot.toAbsolutePath().normalize()
        val t = target.toAbsolutePath().normalize()
        require(t != root) { "Refusing to delete the root directory itself" }
        require(t.startsWith(root)) { "Refusing to delete $t outside $root" }
        if (!Files.exists(t, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        try {
            Files.walkFileTree(t, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // A symbolic link reported as a directory must be unlinked, not descended into.
                    if (Files.isSymbolicLink(dir)) {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    runCatching { Files.deleteIfExists(dir) }
                    return FileVisitResult.CONTINUE
                }
            })
            Files.deleteIfExists(t)
        } catch (e: Exception) {
            CsLog.w("FsUtil", "Could not fully delete $t: ${e.message}")
        }
    }

    fun isInside(root: Path, p: Path): Boolean =
        p.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())

    /** Resolves [relative] under [root] and refuses anything that escapes it. */
    fun resolveInside(root: Path, relative: String): Path {
        val name = relative.replace('\\', '/').trim().trimStart('/')
        require(name.isNotEmpty()) { "Empty path" }
        require(!name.split('/').contains("..")) { "Path traversal rejected: $relative" }
        val target = root.resolve(name).normalize()
        require(target.startsWith(root.toAbsolutePath().normalize()) || target.startsWith(root.normalize())) {
            "Path escapes ${root.fileName}: $relative"
        }
        return target
    }

    /** File name that is safe on every filesystem the launcher touches. */
    fun safeFileName(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trimEnd('.')
        val limited = cleaned.take(120).ifBlank { "file" }
        // Reserved DOS device names are still refused by some Android OEM kernels when copied to SD.
        val reserved = setOf("CON", "PRN", "AUX", "NUL") +
            (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }
        val base = limited.substringBefore('.').uppercase()
        return if (base in reserved) "_$limited" else limited
    }

    fun ensureDir(dir: Path): Path = Files.createDirectories(dir)

    fun ensureParent(file: Path): Path {
        file.parent?.let { Files.createDirectories(it) }
        return file
    }

    /** Human readable size for UI and logs. */
    fun human(bytes: Long): String {
        if (bytes < 0) return "unknown"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var i = 0
        while (value >= 1024.0 && i < units.lastIndex) {
            value /= 1024.0
            i++
        }
        return String.format(java.util.Locale.US, "%.1f %s", value, units[i])
    }

    /** Cross-process-safe lock file so two launcher processes cannot repair the same tree. */
    inline fun <T> withLock(lockFile: Path, block: () -> T): T {
        ensureParent(lockFile)
        FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
        ).use { channel ->
            val lock = channel.lock()
            try {
                return block()
            } finally {
                runCatching { lock.release() }
            }
        }
    }

    /** Reads at most [max] bytes from the end of a log file (crash evidence). */
    fun tail(p: Path, max: Int): String {
        if (!Files.isRegularFile(p)) return ""
        val size = Files.size(p)
        val start = (size - max).coerceAtLeast(0)
        FileChannel.open(p, StandardOpenOption.READ).use { ch ->
            val buf = ByteBuffer.allocate((size - start).toInt().coerceAtLeast(0))
            ch.position(start)
            while (buf.hasRemaining()) {
                if (ch.read(buf) < 0) break
            }
            return String(buf.array(), Charsets.UTF_8)
        }
    }
}
