package pro.cslauncher.core.install

import org.tukaani.xz.XZInputStream
import pro.cslauncher.core.util.FsUtil
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Extracts `.tar`, `.tar.gz` and `.tar.xz` archives — the format Android JDK builds are shipped in.
 *
 * Same threat model as [SafeZip]: entry names are validated through [SafeZip.safeTarget], symbolic
 * links and hard links are **refused** (a JDK tarball has no need for them, and a link is the classic
 * way to write outside the destination), and total size is bounded while copying.
 */
object SafeTar {

    fun extract(
        archive: Path,
        destDir: Path,
        stripComponents: Int = 0,
        maxTotalBytes: Long = 4L shl 30,
        maxEntries: Int = 200_000
    ): SafeZip.ExtractResult {
        Files.createDirectories(destDir)
        var files = 0
        var bytes = 0L
        var skipped = 0
        val seen = HashSet<String>()

        openStream(archive).use { raw ->
            val input = BufferedInputStream(raw, 64 * 1024)
            val header = ByteArray(512)
            var entries = 0
            while (true) {
                if (!readFully(input, header, 512)) break
                if (header.all { it == 0.toByte() }) break      // end-of-archive marker
                entries++
                if (entries > maxEntries) throw SafeZip.UnsafeArchiveException("Archive has too many entries")

                val name = readString(header, 0, 100)
                val prefix = readString(header, 345, 155)
                val fullName = if (prefix.isEmpty()) name else "$prefix/$name"
                val sizeField = readString(header, 124, 12).trim()
                val size = parseOctal(sizeField)
                val type = header[156].toInt().toChar()
                val mode = readString(header, 100, 8).trim()
                val isLongName = type == 'L'
                val isLongLink = type == 'K'
                val padding = ((512 - (size % 512)) % 512)

                when (type) {
                    '0', '\u0000', '7' -> {
                        var relative = fullName.replace('\\', '/')
                        if (stripComponents > 0) {
                            relative = relative.split('/').drop(stripComponents).joinToString("/")
                        }
                        if (relative.isBlank()) {
                            skipFully(input, size + padding)
                            continue
                        }
                        if (!seen.add(relative)) {
                            skipped++
                            skipFully(input, size + padding)
                            continue
                        }
                        val target = SafeZip.safeTarget(destDir, relative)
                        Files.createDirectories(target.parent)
                        val remaining = maxTotalBytes - bytes
                        if (remaining <= 0) throw SafeZip.UnsafeArchiveException("Archive exceeded the size budget")
                        bytes += copyEntry(input, target, size, remaining)
                        skipFully(input, padding)
                        applyExecutableBit(target, mode)
                        files++
                    }

                    '5' -> {
                        // Directory entry: create it (validated) and continue.
                        val relative = if (stripComponents > 0) {
                            fullName.replace('\\', '/').split('/').drop(stripComponents).joinToString("/")
                        } else fullName.replace('\\', '/')
                        if (relative.isNotBlank()) {
                            Files.createDirectories(SafeZip.safeTarget(destDir, relative))
                        }
                        skipFully(input, padding)
                    }

                    '1', '2', '3', '4' -> throw SafeZip.UnsafeArchiveException(
                        "Refusing archive link entry ($fullName): links can escape the destination"
                    )

                    'x', 'g', 'L', 'K' -> {
                        // PAX headers / long names: read and ignore the payload.
                        skipFully(input, size + padding)
                        if (isLongName || isLongLink) skipped++
                    }

                    else -> skipFully(input, size + padding)
                }
            }
        }
        return SafeZip.ExtractResult(files, bytes, skipped)
    }

    private fun openStream(archive: Path): InputStream {
        val raw = BufferedInputStream(Files.newInputStream(archive), 64 * 1024)
        // Peek at the magic bytes to decide whether XZ or gzip wrapping is present.
        raw.mark(6)
        val magic = ByteArray(6)
        val n = raw.read(magic)
        raw.reset()
        if (n >= 6 && magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() && magic[2] == 0x7A.toByte()) {
            return XZInputStream(raw)
        }
        if (n >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()) {
            return java.util.zip.GZIPInputStream(raw)
        }
        return raw
    }

    private fun copyEntry(input: InputStream, target: Path, declaredSize: Long, budget: Long): Long {
        Files.newOutputStream(target).use { out ->
            val buf = ByteArray(64 * 1024)
            var written = 0L
            while (written < declaredSize) {
                val want = minOf(buf.size.toLong(), declaredSize - written).toInt()
                val read = input.read(buf, 0, want)
                if (read < 0) throw IOException("Truncated tar entry for $target")
                written += read
                if (written > budget) throw SafeZip.UnsafeArchiveException("Archive exceeded the size budget")
                out.write(buf, 0, read)
            }
            return written
        }
    }

    private fun applyExecutableBit(target: Path, mode: String) {
        val parsed = runCatching { mode.toInt(8) }.getOrDefault(0)
        if (parsed == 0) return
        val executable = parsed and 0b001_000_000 != 0 || parsed and 0b000_100_000 != 0 || parsed and 0b000_000_100 != 0
        if (!executable) return
        runCatching {
            val perms = Files.getPosixFilePermissions(target).toMutableSet()
            perms += PosixFilePermission.OWNER_EXECUTE
            perms += PosixFilePermission.GROUP_EXECUTE
            perms += PosixFilePermission.OTHERS_EXECUTE
            Files.setPosixFilePermissions(target, perms)
        }.onFailure {
            // Non-POSIX filesystem: the Android layer sets executable bits itself when needed.
            CsLogWarn("SafeTar", "Could not set the executable bit on ${target.fileName}: ${it.message}")
        }
    }

    private fun CsLogWarn(tag: String, message: String) =
        pro.cslauncher.core.util.CsLog.w(tag, message)

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() } ?: (offset + length)
        return String(header, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun parseOctal(field: String): Long {
        if (field.isEmpty()) return 0
        // GNU base-256 encoding for very large files.
        if (field[0] == '\u0080') {
            var v = 0L
            for (i in 1 until field.length) v = (v shl 8) or (field[i].code.toLong() and 0xFF)
            return v
        }
        return field.filter { it in '0'..'7' }.ifEmpty { "0" }.toLongOrNull(8) ?: 0
    }

    private fun readFully(input: InputStream, buf: ByteArray, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}
