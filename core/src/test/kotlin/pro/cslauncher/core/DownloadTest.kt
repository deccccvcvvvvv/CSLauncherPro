package pro.cslauncher.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.cslauncher.core.download.DownloadTask
import pro.cslauncher.core.download.TaskState
import pro.cslauncher.core.util.Hashing
import pro.cslauncher.core.util.Integrity
import java.nio.file.Files

class DownloadTest {

    @Test
    fun downloadsAndVerifies() = runBlocking {
        val body = "hello launcher".toByteArray()
        val http = FakeTransport().putBytes("https://example.invalid/a.txt", body)
        val dm = http.downloads()
        val target = Files.createTempDirectory("dl").resolve("a.txt")

        val result = dm.downloadOne(
            DownloadTask("https://example.invalid/a.txt", target, Integrity(sha1 = Hashing.sha1(body), size = body.size.toLong()))
        )
        assertEquals(TaskState.DONE, result.state)
        assertTrue(Files.isRegularFile(target))
        assertEquals("hello launcher", Files.readString(target))
        assertFalse("The .part file must not survive", Files.exists(target.resolveSibling("a.txt.part")))
    }

    @Test
    fun wrongHashDoesNotProduceAFile() = runBlocking {
        val http = FakeTransport().put("https://example.invalid/b.txt", "content")
        val dm = http.downloads()
        val target = Files.createTempDirectory("dl").resolve("b.txt")
        val result = dm.downloadOne(
            DownloadTask("https://example.invalid/b.txt", target, Integrity(sha1 = "0".repeat(40)))
        )
        assertEquals(TaskState.FAILED, result.state)
        assertFalse("A file that failed verification must never appear at its destination", Files.exists(target))
    }

    @Test
    fun existingIntactFileIsSkipped() = runBlocking {
        val body = "already here".toByteArray()
        val http = FakeTransport().putBytes("https://example.invalid/c.txt", body)
        val dm = http.downloads()
        val target = Files.createTempDirectory("dl").resolve("c.txt")
        Files.write(target, body)
        val result = dm.downloadOne(
            DownloadTask("https://example.invalid/c.txt", target, Integrity(sha1 = Hashing.sha1(body)))
        )
        assertEquals(TaskState.SKIPPED, result.state)
        assertEquals("A complete file must not be downloaded again", 0, http.requestCount.get())
    }

    @Test
    fun corruptedExistingFileIsReplaced() = runBlocking {
        val body = "correct".toByteArray()
        val http = FakeTransport().putBytes("https://example.invalid/d.txt", body)
        val dm = http.downloads()
        val target = Files.createTempDirectory("dl").resolve("d.txt")
        Files.writeString(target, "corrupt")
        val result = dm.downloadOne(
            DownloadTask("https://example.invalid/d.txt", target, Integrity(sha1 = Hashing.sha1(body)))
        )
        assertEquals(TaskState.DONE, result.state)
        assertEquals("correct", Files.readString(target))
    }

    @Test
    fun batchReportsFailureWithoutThrowing() = runBlocking {
        val http = FakeTransport().put("https://example.invalid/ok", "ok")
        val dm = http.downloads()
        val dir = Files.createTempDirectory("dl")
        val result = dm.downloadAll(
            "test",
            listOf(
                DownloadTask("https://example.invalid/ok", dir.resolve("ok")),
                DownloadTask("https://example.invalid/missing", dir.resolve("missing"))
            )
        )
        assertFalse(result.ok)
        assertEquals(1, result.failed.size)
        assertEquals("missing", result.failed.first().task.destination.fileName.toString())
        assertTrue(result.summary().contains("failed"))
    }
}
