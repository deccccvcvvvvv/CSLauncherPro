package pro.cslauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.cslauncher.core.install.SafeTar
import pro.cslauncher.core.install.SafeZip
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveTest {

    private fun zip(dir: Path, vararg entries: Pair<String, String>): Path {
        val file = dir.resolve("test-${System.nanoTime()}.zip")
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            entries.forEach { (name, body) ->
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun extractsNormally() {
        val root = Files.createTempDirectory("zip")
        val dest = root.resolve("out")
        val archive = zip(root, "a.txt" to "hello", "dir/b.txt" to "world")
        val result = SafeZip.extract(archive, dest)
        assertEquals(2, result.files)
        assertEquals("hello", Files.readString(dest.resolve("a.txt")))
        assertEquals("world", Files.readString(dest.resolve("dir/b.txt")))
    }

    @Test
    fun refusesPathTraversal() {
        val root = Files.createTempDirectory("zip")
        val archive = zip(root, "../escape.txt" to "bad")
        val failed = try {
            SafeZip.extract(archive, root.resolve("out"))
            false
        } catch (_: SafeZip.UnsafeArchiveException) {
            true
        }
        assertTrue("Path traversal must be refused", failed)
        assertTrue(Files.notExists(root.resolve("escape.txt")))
    }

    @Test
    fun refusesAbsolutePaths() {
        val root = Files.createTempDirectory("zip")
        val archive = zip(root, "/etc/passwd" to "bad")
        val failed = try {
            SafeZip.extract(archive, root.resolve("out"))
            false
        } catch (_: SafeZip.UnsafeArchiveException) {
            true
        }
        assertTrue(failed)
    }

    @Test
    fun stripPrefixAndFilterWork() {
        val root = Files.createTempDirectory("zip")
        val dest = root.resolve("out")
        val archive = zip(root, "overrides/mods/a.jar" to "j", "overrides/config/b.cfg" to "c")
        SafeZip.extract(archive, dest, stripPrefix = "overrides/")
        assertTrue(Files.isRegularFile(dest.resolve("mods/a.jar")))
        assertTrue(Files.isRegularFile(dest.resolve("config/b.cfg")))
    }

    @Test
    fun tarXzIsRejectedWhenNotAnArchive() {
        val root = Files.createTempDirectory("tar")
        val notTar = root.resolve("file.tar.xz")
        Files.writeString(notTar, "definitely not an archive")
        // A malformed archive must fail rather than write a partial tree.
        runCatching { SafeTar.extract(notTar, root.resolve("out")) }
        assertTrue(Files.notExists(root.resolve("out/a")))
    }

    @Test
    fun sizeBudgetIsEnforced() {
        val root = Files.createTempDirectory("zip")
        val big = "x".repeat(200_000)
        val archive = zip(root, "big.bin" to big)
        val failed = try {
            SafeZip.extract(archive, root.resolve("out"), maxTotalBytes = 1000)
            false
        } catch (_: SafeZip.UnsafeArchiveException) {
            true
        }
        assertTrue(failed)
    }
}
