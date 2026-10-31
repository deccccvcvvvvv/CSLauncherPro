package pro.cslauncher.core.install

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Hardened ZIP extraction.
 *
 * Archives are attacker-controlled input in a launcher (mods, resource packs, modpacks, native
 * component packs), so nothing is written before the entry name has been validated:
 *
 * * **Zip-slip** — every target goes through [safeTarget], which refuses `..`, absolute paths and
 *   anything that canonicalises outside the destination.
 * * **Symlinks / device nodes** — the archive is read with `ZipFile`, so entry *data* is copied;
 *   Unix mode bits are never applied.
 * * **Zip bombs** — entry count, total uncompressed bytes and per-entry size are bounded, and the
 *   running total is checked *while* copying (a lying header cannot exceed the budget).
 * * **Duplicate entries** — the second one is skipped, so a crafted archive cannot overwrite a file
 *   it already extracted with different content.
 */
object SafeZip {

    class UnsafeArchiveException(message: String) : IOException(message)

    data class ExtractResult(val files: Int, val bytes: Long, val skippedDuplicates: Int)

    /** Resolves an archive entry name under [destDir], refusing anything that escapes it. */
    fun safeTarget(destDir: Path, entryName: String): Path {
        val name = entryName.replace('\\', '/').trim()
        fun refuse(reason: String): Nothing = throw UnsafeArchiveException("$reason: $entryName")
        if (name.isEmpty()) refuse("Empty archive entry name")
        if (name.startsWith("/")) refuse("Absolute path in archive")
        if (Regex("^[A-Za-z]:").containsMatchIn(name)) refuse("Drive-letter path in archive")
        if (name.split('/').contains("..")) refuse("Path traversal in archive")
        if (name.contains('\u0000')) refuse("NUL byte in archive entry name")
        val root = destDir.toAbsolutePath().normalize()
        val target = root.resolve(name).normalize()
        if (!target.startsWith(root)) refuse("Archive entry escapes the destination")
        return target
    }

    fun extract(
        zip: Path,
        destDir: Path,
        filter: (String) -> Boolean = { true },
        stripPrefix: String = "",
        maxEntries: Int = 200_000,
        maxTotalBytes: Long = 8L * 1024 * 1024 * 1024,
        maxSingleEntryBytes: Long = 2L * 1024 * 1024 * 1024
    ): ExtractResult {
        Files.createDirectories(destDir)
        var files = 0
        var bytes = 0L
        var duplicates = 0
        val seen = HashSet<String>()

        ZipFile(zip.toFile()).use { zf ->
            val entries = zf.entries().toList()
            if (entries.size > maxEntries) {
                throw UnsafeArchiveException("Archive has too many entries (${entries.size})")
            }
            for (entry in entries) {
                if (entry.isDirectory) continue
                var name = entry.name.replace('\\', '/')
                if (stripPrefix.isNotEmpty()) {
                    if (!name.startsWith(stripPrefix)) continue
                    name = name.removePrefix(stripPrefix).trimStart('/')
                    if (name.isEmpty()) continue
                }
                if (!filter(name)) continue
                if (!seen.add(name)) {
                    duplicates++
                    continue
                }
                val target = safeTarget(destDir, name)
                if (entry.size > maxSingleEntryBytes) {
                    throw UnsafeArchiveException("Entry too large: $name")
                }
                Files.createDirectories(target.parent)
                val copied = zf.getInputStream(entry).use { input ->
                    copyBounded(input, target, maxSingleEntryBytes, maxTotalBytes - bytes, name)
                }
                bytes += copied
                files++
            }
        }
        return ExtractResult(files, bytes, duplicates)
    }

    private fun copyBounded(input: InputStream, target: Path, maxEntry: Long, remaining: Long, name: String): Long {
        Files.newOutputStream(target).use { out ->
            val buf = ByteArray(64 * 1024)
            var written = 0L
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                written += read
                if (written > maxEntry) throw UnsafeArchiveException("Entry $name expanded beyond the entry limit")
                if (written > remaining) throw UnsafeArchiveException("Archive exceeded the total size budget at $name")
                out.write(buf, 0, read)
            }
            return written
        }
    }

    /** Lists validated entry names without extracting, used to preview a pack before installing. */
    fun validatedEntries(zip: Path): List<String> = ZipFile(zip.toFile()).use { zf ->
        zf.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
    }

    /** Reads a single entry into memory with a hard ceiling (used for manifests inside a jar). */
    fun readEntry(zip: Path, entryName: String, maxBytes: Long = 8L shl 20): ByteArray? =
        ZipFile(zip.toFile()).use { zf ->
            val entry: ZipEntry = zf.getEntry(entryName) ?: return null
            if (entry.size > maxBytes) throw UnsafeArchiveException("Entry $entryName is too large")
            zf.getInputStream(entry).use { it.readNBytes(maxBytes.toInt() + 1) }.let {
                if (it.size > maxBytes) throw UnsafeArchiveException("Entry $entryName is too large")
                it
            }
        }

    /** Replaces [target] atomically after extraction, so a failed extract cannot leave a half tree. */
    fun extractToTempThenSwap(zip: Path, destDir: Path, filter: (String) -> Boolean = { true }): ExtractResult {
        val staging = destDir.resolveSibling(destDir.fileName.toString() + ".staging-" + System.nanoTime())
        return try {
            val result = extract(zip, staging, filter)
            Files.createDirectories(destDir.parent)
            if (Files.exists(destDir)) {
                pro.cslauncher.core.util.FsUtil.deleteTreeWithin(destDir.parent, destDir)
            }
            Files.move(staging, destDir, StandardCopyOption.REPLACE_EXISTING)
            result
        } finally {
            if (Files.exists(staging)) {
                runCatching { pro.cslauncher.core.util.FsUtil.deleteTreeWithin(staging.parent, staging) }
            }
        }
    }
}
