package pro.cslauncher.core.activity

import kotlinx.serialization.Serializable
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.CsJsonCompact
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Serializable
enum class ActivityKind { INSTALLED, LAUNCHED, CRASHED, MOD_IMPORTED, DOWNLOAD_FAILED, REPAIRED, RECORDING }

/**
 * One line in the activity feed. Deliberately flat and serialisable: the feed is written on every
 * launch and install, so it must never grow into a second database.
 */
@Serializable
data class ActivityItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val kind: ActivityKind,
    val atMs: Long = System.currentTimeMillis(),
    val title: String,
    val detail: String = "",
    val profileId: String? = null,
    val versionId: String? = null,
    val success: Boolean = true
)

/**
 * Append-only JSON-lines activity log.
 *
 * Written as JSON lines rather than a single JSON array so a crash mid-write can only ever damage the
 * last line — the rest of the history stays readable. The file is trimmed by count, not by date, so a
 * burst of activity cannot make it unbounded.
 */
class ActivityLog(
    private val file: Path,
    private val maxItems: Int = 500
) {
    private val lock = Any()

    fun items(limit: Int = 100): List<ActivityItem> = synchronized(lock) {
        if (!Files.isRegularFile(file)) return emptyList()
        runCatching {
            Files.readAllLines(file)
                .asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { CsJson.decodeFromString(ActivityItem.serializer(), line) }.getOrNull()
                }
                .toList()
                // Reversed first, then a *stable* sort: entries written in the same millisecond keep
                // newest-first order instead of coming back in an arbitrary one.
                .asReversed()
                .sortedByDescending { it.atMs }
                .take(limit)
        }.getOrElse {
            CsLog.w("Activity", "Activity log unreadable: ${it.message}")
            emptyList()
        }
    }

    fun record(item: ActivityItem): ActivityItem = synchronized(lock) {
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                // One line per item: the reader splits on newlines and pretty-printed JSON would
                // corrupt the log with a parse failure per record.
                CsJsonCompact.encodeToString(ActivityItem.serializer(), item) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
            trimIfNeeded()
        }.onFailure { CsLog.w("Activity", "Could not record activity: ${it.message}") }
        item
    }

    fun installed(versionId: String, profileId: String? = null) = record(
        ActivityItem(kind = ActivityKind.INSTALLED, title = "Installed $versionId", profileId = profileId, versionId = versionId)
    )

    fun launched(versionId: String, profileId: String, detail: String = "") = record(
        ActivityItem(kind = ActivityKind.LAUNCHED, title = "Launched $versionId", detail = detail, profileId = profileId, versionId = versionId)
    )

    fun crashed(versionId: String, profileId: String, reason: String) = record(
        ActivityItem(kind = ActivityKind.CRASHED, title = "Minecraft $versionId crashed", detail = reason, profileId = profileId, versionId = versionId, success = false)
    )

    fun downloadFailed(what: String, reason: String) = record(
        ActivityItem(kind = ActivityKind.DOWNLOAD_FAILED, title = "Download failed: $what", detail = reason, success = false)
    )

    fun repaired(versionId: String, detail: String) = record(
        ActivityItem(kind = ActivityKind.REPAIRED, title = "Repaired $versionId", detail = detail, versionId = versionId)
    )

    fun modImported(name: String, profileId: String) = record(
        ActivityItem(kind = ActivityKind.MOD_IMPORTED, title = "Added $name", profileId = profileId)
    )

    fun recordingSaved(path: String, profileId: String?) = record(
        ActivityItem(kind = ActivityKind.RECORDING, title = "Recording saved", detail = path, profileId = profileId)
    )

    fun clear() = synchronized(lock) {
        runCatching { Files.deleteIfExists(file) }.onFailure { CsLog.w("Activity", "Could not clear activity: ${it.message}") }
    }

    /** Keeps the newest [maxItems] entries; nothing is rewritten unless the file actually overflows. */
    private fun trimIfNeeded() {
        val lines: List<String> = runCatching { Files.readAllLines(file).filter { it.isNotBlank() } }
            .getOrElse { return }
        // The rate feeds the whole install/launch path, so trimming is amortised; but the file must
        // never be allowed to grow unbounded on a device with little storage.
        if (lines.size <= maxItems + maxItems / 4) return
        val keep = lines.takeLast(maxItems)
        FsUtil.writeAtomic(file, keep.joinToString("\n", postfix = "\n"))
    }
}
