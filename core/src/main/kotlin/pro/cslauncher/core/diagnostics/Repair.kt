package pro.cslauncher.core.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.cslauncher.core.download.DownloadManager
import pro.cslauncher.core.install.Installer
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.install.Verifier
import pro.cslauncher.core.meta.VersionManifest
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import java.nio.file.Files
import java.nio.file.Path

/** What a repair would touch, shown before it runs. */
data class RepairPlan(
    val action: RepairAction,
    val affectedPaths: List<String>,
    val requiresConfirmation: Boolean,
    val explanation: String
)

data class RepairResult(
    val action: RepairAction,
    val success: Boolean,
    val changed: List<String>,
    val verification: String
)

/**
 * Runs the non-destructive repairs that [CrashAnalyzer] suggests.
 *
 * The contract that makes this safe to offer as a button:
 * * a repair never touches user content (mods, worlds, options) — that is what [RepairAction.destructive]
 *   is for, and those are rejected here;
 * * every repair is planned first, so the UI can show exactly which paths will change;
 * * every repair ends with a **verification**, not an assumption: "repaired" without a check is a lie.
 */
class RepairEngine(
    private val dirs: LauncherDirs,
    private val installer: Installer,
    private val downloads: DownloadManager,
    private val platform: PlatformInfo
) {

    suspend fun plan(action: RepairAction, versionId: String): RepairPlan = when (action) {
        is RepairAction.RedownloadFiles -> {
            val rv = runCatching { installer.resolve(versionId, null) }.getOrNull()
            if (rv == null) {
                RepairPlan(action, emptyList(), false, "The version metadata could not be read, so there is nothing to verify yet.")
            } else {
                val report = Verifier(dirs, installer, platform).verify(rv, fullHash = true)
                RepairPlan(
                    action = action,
                    affectedPaths = report.repairTasks.map { it.destination.toString() },
                    requiresConfirmation = false,
                    explanation = if (report.complete) {
                        "All files verified; nothing to download."
                    } else {
                        "${report.repairTasks.size} file(s) will be re-downloaded: " +
                            report.issues.take(5).joinToString { it.name }
                    }
                )
            }
        }

        RepairAction.RebuildNatives -> RepairPlan(
            action,
            listOf(dirs.nativesDir(versionId).toString()),
            false,
            "The launcher-owned natives folder for $versionId is regenerated from the verified libraries."
        )

        RepairAction.RemoveTempFiles -> RepairPlan(
            action,
            launcherOwned().map { "$it/**/*.part" },
            false,
            "Only unfinished downloads ending in .part inside launcher-managed folders are deleted."
        )

        else -> RepairPlan(action, emptyList(), action.destructive, action.description)
    }

    suspend fun execute(
        action: RepairAction,
        versionId: String,
        manifest: VersionManifest? = null
    ): RepairResult = withContext(Dispatchers.IO) {
        require(!action.destructive) { "Destructive repairs must be performed explicitly by the user" }
        when (action) {
            is RepairAction.RedownloadFiles -> {
                val rv = installer.resolve(versionId, manifest)
                val before = Verifier(dirs, installer, platform).verify(rv, fullHash = true)
                val changed = mutableListOf<String>()
                if (before.repairTasks.isNotEmpty()) {
                    val result = downloads.downloadAll("repair $versionId", before.repairTasks)
                    changed += result.results.filter { it.ok }.map { it.task.destination.toString() }
                    if (!result.ok) {
                        return@withContext RepairResult(
                            action, false, changed,
                            "Some files still failed to download: " + result.summary()
                        )
                    }
                }
                if (!platform.isAndroid) installer.extractNatives(rv)
                val after = Verifier(dirs, installer, platform).verify(rv, fullHash = true)
                RepairResult(
                    action,
                    after.complete,
                    changed,
                    if (after.complete) "All ${after.checkedFiles} files verified" else after.summary()
                )
            }

            RepairAction.RebuildNatives -> {
                val rv = installer.resolve(versionId, manifest)
                val target = dirs.nativesDir(versionId)
                if (Files.exists(target)) FsUtil.deleteTreeWithin(dirs.nativeRoot, target)
                installer.extractNatives(rv)
                val count = if (Files.isDirectory(target)) {
                    Files.list(target).use { it.count() }
                } else 0L
                RepairResult(
                    action,
                    count > 0 || platform.isAndroid,
                    listOf(target.toString()),
                    if (count > 0) "$count native libraries rebuilt" else "Natives come from the Android component pack"
                )
            }

            RepairAction.RemoveTempFiles -> {
                var removed = 0L
                for (root in launcherOwned()) {
                    removed += runCatching { downloads.cleanPartialFiles(root) }.getOrDefault(0)
                }
                RepairResult(action, true, launcherOwned().map { it.toString() }, "$removed unfinished files removed")
            }

            else -> RepairResult(action, false, emptyList(), "This action needs the user to make a choice")
        }
    }

    /** Only folders the launcher fully owns may be cleaned without asking. */
    private fun launcherOwned(): List<Path> = listOf(
        dirs.cache, dirs.assets, dirs.libraries, dirs.versions,
        dirs.nativeRoot, dirs.runtimeRoot, dirs.componentRoot
    ).filter { Files.isDirectory(it) }

    /**
     * Full health check used by the Diagnostics screen: verifies every installed version and reports
     * the parts that would be repaired.
     */
    suspend fun healthReport(
        versionIds: List<String>,
        manifest: VersionManifest?
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        versionIds.map { id ->
            val text = runCatching {
                val rv = installer.resolve(id, manifest)
                val report = Verifier(dirs, installer, platform).verify(rv, fullHash = true)
                if (report.complete) "OK: ${report.checkedFiles} files verified" else report.summary()
            }.getOrElse { "Unreadable: ${it.message}" }
            CsLog.i("Repair", "$id: $text")
            id to text
        }
    }
}
