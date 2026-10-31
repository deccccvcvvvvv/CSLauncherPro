package pro.cslauncher.core.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import pro.cslauncher.core.net.HttpStatusException
import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import pro.cslauncher.core.util.Hashing
import pro.cslauncher.core.util.Integrity
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/** One file to fetch. [mirrors] are tried in order after [url] fails with a retryable error. */
data class DownloadTask(
    val url: String,
    val destination: Path,
    val integrity: Integrity = Integrity(),
    val label: String = destination.fileName.toString(),
    val mirrors: List<String> = emptyList()
)

enum class TaskState { QUEUED, RUNNING, VERIFYING, DONE, SKIPPED, FAILED, CANCELLED }

data class TaskResult(
    val task: DownloadTask,
    val state: TaskState,
    val bytes: Long,
    val error: String? = null,
    val attempts: Int = 0
) {
    val ok get() = state == TaskState.DONE || state == TaskState.SKIPPED
}

data class BatchProgress(
    val batchName: String,
    val totalFiles: Int,
    val finishedFiles: Int,
    val failedFiles: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val bytesPerSecond: Long,
    val currentFile: String?,
    val etaSeconds: Long?,
    val state: TaskState
) {
    val fraction: Float
        get() = when {
            totalFiles <= 0 -> 0f
            finishedFiles >= totalFiles -> 1f
            totalBytes > 0 -> (downloadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
            else -> (finishedFiles.toDouble() / totalFiles).toFloat().coerceIn(0f, 1f)
        }
}

class BatchResult(val results: List<TaskResult>) {
    val failed get() = results.filter { it.state == TaskState.FAILED }
    val ok get() = failed.isEmpty() && results.none { it.state == TaskState.CANCELLED }
    val bytes get() = results.sumOf { it.bytes }

    fun summary(): String = if (ok) {
        "${results.size} files ready (${FsUtil.human(bytes)})"
    } else {
        "${failed.size} of ${results.size} files failed. First error: " +
            (failed.firstOrNull()?.let { "${it.task.label}: ${it.error}" } ?: "cancelled")
    }
}

class DownloadFailedException(val result: BatchResult) : IOException(result.summary())

class InsufficientStorageException(val requiredBytes: Long, val freeBytes: Long) :
    IOException("Not enough space: need ${FsUtil.human(requiredBytes)}, ${FsUtil.human(freeBytes)} free")

data class DownloadHistoryEntry(
    val batchName: String,
    val finishedAtMs: Long,
    val files: Int,
    val failed: Int,
    val bytes: Long,
    val firstError: String?
)

/**
 * Shared download engine.
 *
 * Guarantees the installer relies on:
 * * **Hash-gated**: bytes stream into `<name>.part`; the file only appears at its destination after
 *   the integrity check passed and an atomic move succeeded. An interrupted download therefore can
 *   never be mistaken for an installed one.
 * * **Resumable**: an existing `.part` is continued with an HTTP `Range` request. If the origin
 *   answers `200` instead of `206`, the partial file is discarded and the download restarts, because
 *   splicing two responses together would produce a corrupt file that then fails the hash anyway.
 * * **Bounded concurrency**: mobile data and low-end devices do not appreciate 50 parallel streams.
 * * **De-duplicated**: the same destination requested by two batches at once is downloaded once.
 * * **Deterministic results**: every task ends in exactly one [TaskState]; nothing throws out of a
 *   batch except a genuine cancellation or a full disk.
 */
class DownloadManager(
    private val http: HttpTransport,
    @Volatile private var maxConcurrency: Int = 6,
    @Volatile private var maxAttempts: Int = 3,
    private val retryBaseDelayMs: Long = 600
) {
    fun configure(concurrency: Int, attempts: Int) {
        maxConcurrency = concurrency.coerceIn(1, 16)
        maxAttempts = attempts.coerceIn(1, 8)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<Path, kotlinx.coroutines.Deferred<TaskResult>>()

    private val _active = MutableStateFlow<Map<String, BatchProgress>>(emptyMap())
    val activeBatches: StateFlow<Map<String, BatchProgress>> = _active.asStateFlow()

    private val _history = MutableStateFlow<List<DownloadHistoryEntry>>(emptyList())
    val history: StateFlow<List<DownloadHistoryEntry>> = _history.asStateFlow()

    suspend fun downloadAll(
        batchName: String,
        tasks: List<DownloadTask>,
        onProgress: (BatchProgress) -> Unit = {}
    ): BatchResult = coroutineScope {
        if (tasks.isEmpty()) return@coroutineScope BatchResult(emptyList())

        val unique = tasks.distinctBy { it.destination.toAbsolutePath().normalize() }
        checkDiskSpace(unique)

        val knownTotal = if (unique.all { (it.integrity.size ?: -1) >= 0 }) unique.sumOf { it.integrity.size!! } else -1L
        val done = AtomicInteger()
        val failed = AtomicInteger()
        val bytes = AtomicLong()
        val current = AtomicReference<String?>(null)
        val start = System.nanoTime()
        val sem = Semaphore(maxConcurrency)

        fun snapshot(state: TaskState): BatchProgress {
            val elapsed = (System.nanoTime() - start) / 1e9
            val bps = if (elapsed > 0.5) (bytes.get() / elapsed).toLong() else 0L
            val eta = if (knownTotal > 0 && bps > 0 && elapsed > 2) {
                ((knownTotal - bytes.get()).coerceAtLeast(0) / bps)
            } else null
            return BatchProgress(
                batchName = batchName,
                totalFiles = unique.size,
                finishedFiles = done.get(),
                failedFiles = failed.get(),
                totalBytes = knownTotal,
                downloadedBytes = bytes.get(),
                bytesPerSecond = bps,
                currentFile = current.get(),
                etaSeconds = eta,
                state = state
            )
        }

        fun publish(state: TaskState) {
            val p = snapshot(state)
            _active.value = _active.value + (batchName to p)
            onProgress(p)
        }

        publish(TaskState.RUNNING)
        val ticker: Job = launch {
            while (isActive) {
                delay(500)
                publish(TaskState.RUNNING)
            }
        }

        val results = try {
            unique.map { task ->
                async {
                    sem.withPermit {
                        current.set(task.label)
                        val r = runDeduplicated(task) { n -> bytes.addAndGet(n) }
                        if (r.state == TaskState.FAILED) failed.incrementAndGet()
                        done.incrementAndGet()
                        publish(TaskState.RUNNING)
                        r
                    }
                }
            }.awaitAll()
        } finally {
            ticker.cancel()
        }

        val br = BatchResult(results)
        val fin = snapshot(if (br.ok) TaskState.DONE else TaskState.FAILED)
        _active.value = _active.value - batchName
        onProgress(fin)
        _history.value = (_history.value + DownloadHistoryEntry(
            batchName = batchName,
            finishedAtMs = System.currentTimeMillis(),
            files = results.size,
            failed = br.failed.size,
            bytes = br.bytes,
            firstError = br.failed.firstOrNull()?.let { "${it.task.label}: ${it.error}" }
        )).takeLast(50)
        br
    }

    /** The same destination must never be downloaded twice concurrently. */
    private suspend fun runDeduplicated(task: DownloadTask, onBytes: (Long) -> Unit): TaskResult = coroutineScope {
        val key = task.destination.toAbsolutePath().normalize()
        val mine = async(start = kotlinx.coroutines.CoroutineStart.LAZY) { downloadOne(task, onBytes) }
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) {
            return@coroutineScope try {
                existing.await()
            } catch (e: Exception) {
                mine.start()
                mine.await()
            }
        }
        try {
            mine.start()
            mine.await()
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private fun checkDiskSpace(tasks: List<DownloadTask>) {
        val need = tasks.sumOf { maxOf(0L, it.integrity.size ?: 0L) }
        val dir = tasks.first().destination.toAbsolutePath().parent
        val free = FsUtil.usableSpace(dir).takeIf { it > 0 } ?: return
        // Keep a little headroom so the launcher itself can still write its own state files.
        if (need > 0 && free < need + (8L shl 20)) throw InsufficientStorageException(need, free)
    }

    suspend fun downloadOne(task: DownloadTask, onBytes: (Long) -> Unit = {}): TaskResult = withContext(Dispatchers.IO) {
        val dest = task.destination
        var attempts = 0

        // 1. Already present and intact?
        val existingProblem = task.integrity.check(dest, fullHash = task.integrity.hasChecksum)
        if (existingProblem == null) {
            return@withContext TaskResult(task, TaskState.SKIPPED, Files.size(dest))
        }

        // 2. A file that exists but is wrong must not survive next to a fresh download.
        if (Files.isRegularFile(dest)) {
            CsLog.w("Download", "Replacing ${dest.fileName}: $existingProblem")
            runCatching { Files.delete(dest) }
        }
        Files.createDirectories(dest.parent)

        val part = dest.resolveSibling(dest.fileName.toString() + ".part")
        var lastError: String? = null
        val urls = (listOf(task.url) + task.mirrors).filter { it.isNotBlank() }

        while (attempts < maxAttempts) {
            attempts++
            val url = urls[(attempts - 1) % urls.size]
            try {
                val written = fetch(url, task, part, onBytes)
                if (!Files.isRegularFile(part)) {
                    lastError = "download produced no file"
                    continue
                }
                val problem = task.integrity.check(part, fullHash = true)
                if (problem != null) {
                    lastError = problem
                    Files.deleteIfExists(part)
                    CsLog.w("Download", "${task.label}: $problem (attempt $attempts/$maxAttempts)")
                    continue
                }
                FsUtil.writeAtomic(dest, Files.readAllBytes(part)).also { Files.deleteIfExists(part) }
                return@withContext TaskResult(task, TaskState.DONE, written, attempts = attempts)
            } catch (cancelled: CancellationException) {
                Files.deleteIfExists(part)
                throw cancelled
            } catch (e: HttpStatusException) {
                lastError = "HTTP ${e.status}"
                if (!e.isTransient) break
                CsLog.w("Download", "${task.label}: ${e.message} (attempt $attempts/$maxAttempts)")
            } catch (e: IOException) {
                lastError = e.message ?: e::class.java.simpleName
                CsLog.w("Download", "${task.label}: $lastError (attempt $attempts/$maxAttempts)")
            } catch (e: Exception) {
                lastError = e.message ?: e::class.java.simpleName
            }
            if (attempts < maxAttempts) delay(retryBaseDelayMs * (1L shl (attempts - 1)))
        }
        Files.deleteIfExists(part)
        TaskResult(task, TaskState.FAILED, 0, lastError ?: "failed", attempts)
    }

    /** Streams [url] into [part], resuming when the server honours `Range`. */
    private fun fetch(url: String, task: DownloadTask, part: Path, onBytes: (Long) -> Unit): Long {
        var resumeFrom = if (Files.isRegularFile(part)) Files.size(part) else 0L
        val headers = if (resumeFrom > 0) mapOf("Range" to "bytes=$resumeFrom-") else emptyMap()

        http.open(url, headers).use { response ->
            if (response.code == 416 && resumeFrom > 0) {
                // Our partial file is not a valid prefix of the remote one: start over.
                Files.deleteIfExists(part)
                resumeFrom = 0
                return fetch(url, task, part, onBytes)
            }
            if (!response.isSuccess) {
                throw HttpStatusException(url, response.code)
            }
            if (resumeFrom > 0 && !response.isPartial) {
                // Server ignored Range and sent the whole body: a splice would corrupt the file.
                Files.deleteIfExists(part)
                resumeFrom = 0
            }

            RandomAccessFile(part.toFile(), "rw").use { raf ->
                if (resumeFrom > 0) raf.seek(resumeFrom) else raf.setLength(0)
                response.body.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        raf.write(buf, 0, read)
                        onBytes(read.toLong())
                    }
                }
            }
            val expected = task.integrity.size
            val actual = Files.size(part)
            if (expected != null && expected >= 0 && actual != expected) {
                // Size mismatch is handled by the integrity check; just make the reason specific.
                throw IOException("incomplete download ($actual of $expected bytes)")
            }
            return actual
        }
    }

    /** Removes `*.part` files left behind by an interrupted run. Returns how many were removed. */
    fun cleanPartialFiles(root: Path): Int {
        if (!Files.isDirectory(root)) return 0
        var removed = 0
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".part") }
                .forEach { if (runCatching { Files.deleteIfExists(it) }.getOrDefault(false)) removed++ }
        }
        return removed
    }

    fun cancelAll() {
        inFlight.keys.toList().forEach { inFlight.remove(it)?.cancel() }
    }
}
