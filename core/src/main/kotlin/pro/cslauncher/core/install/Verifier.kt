package pro.cslauncher.core.install

import pro.cslauncher.core.download.DownloadTask
import pro.cslauncher.core.meta.AssetIndex
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import pro.cslauncher.core.util.Integrity
import java.nio.file.Files
import java.nio.file.Path

/** Which part of an installation a problem belongs to. Mirrors the install stages. */
enum class Component {
    METADATA, CLIENT, LIBRARY, ASSET_INDEX, ASSET, LOGGING_CONFIG, NATIVES, RUNTIME, RENDERER, GAME_DIRECTORY
}

/**
 * A verified problem, with the task that fixes it when one exists.
 * `repair` is null for problems the launcher cannot fix by downloading (e.g. no client download
 * declared in the metadata), which is exactly the distinction the repair UI needs.
 */
data class InstallIssue(
    val component: Component,
    val name: String,
    val problem: String,
    val repair: DownloadTask?
)

data class InstallReport(
    val versionId: String,
    val checkedFiles: Int,
    val issues: List<InstallIssue>,
    val notes: List<String>
) {
    val complete get() = issues.isEmpty()
    val repairable get() = issues.isNotEmpty() && issues.all { it.repair != null }
    val repairTasks get() = issues.mapNotNull { it.repair }
    val blockingIssues get() = issues.filter { it.component != Component.ASSET }

    fun summary(): String = if (complete) {
        "Installation of $versionId verified ($checkedFiles files)"
    } else {
        val byComponent = issues.groupingBy { it.component }.eachCount()
            .entries.joinToString(", ") { "${it.key.name.lowercase()}: ${it.value}" }
        "${issues.size} problems in $versionId ($byComponent)"
    }

    companion object {
        fun complete(versionId: String, checked: Int) = InstallReport(versionId, checked, emptyList(), emptyList())
    }
}

/**
 * Checks an installation against its metadata.
 *
 * Two modes, both used in practice:
 * * `fullHash = false` — launched from the Play button. Size and presence only: hashing 20 000
 *   assets before every launch would add minutes to a phone's startup.
 * * `fullHash = true` — repair. Everything is hashed, because the point is to find the one corrupt
 *   file, not to be fast.
 */
class Verifier(
    private val dirs: LauncherDirs,
    private val installer: Installer,
    private val platform: PlatformInfo
) {

    fun verify(rv: ResolvedVersion, fullHash: Boolean): InstallReport {
        val issues = mutableListOf<InstallIssue>()
        val notes = mutableListOf<String>()
        var checked = 0

        fun check(component: Component, task: DownloadTask?, name: String, integrity: Integrity, file: Path) {
            checked++
            val problem = integrity.check(file, fullHash)
            if (problem != null) {
                // A missing file that the metadata can re-download is repairable; anything else is not.
                issues += InstallIssue(component, name, problem, task)
            }
        }

        // 1. Metadata chain
        for (id in rv.chain) {
            checked++
            if (!Files.isRegularFile(dirs.versionJson(id))) {
                issues += InstallIssue(Component.METADATA, id, "metadata missing", null)
            }
        }

        // 2. Client jar
        val client = installer.clientTask(rv)
        if (client != null) {
            check(Component.CLIENT, client, "client ${rv.jarVersionId}", client.integrity, client.destination)
        } else {
            notes += "This version declares no client download; it relies on a loader's own jar"
        }

        // 3. Logging config (1.12+)
        installer.loggingTask(rv)?.let {
            check(Component.LOGGING_CONFIG, it, it.label, it.integrity, it.destination)
        }

        // 4. Libraries
        for (rl in rv.libraries) {
            val d = rl.download
            val task = d?.takeIf { it.url.isNotBlank() }?.let {
                DownloadTask(it.url, rl.file, it.integrity, rl.library.name)
            }
            check(Component.LIBRARY, task, rl.library.name, d?.integrity ?: Integrity(), rl.file)
        }
        if (rv.desktopNativesReplaced.isNotEmpty()) {
            notes += "${rv.desktopNativesReplaced.size} desktop native libraries are supplied by the Android component pack"
        }

        // 5. Asset index and assets
        val indexTask = installer.assetIndexTask(rv)
        if (indexTask != null) {
            check(Component.ASSET_INDEX, indexTask, indexTask.label, indexTask.integrity, indexTask.destination)
            if (Files.isRegularFile(indexTask.destination)) {
                runCatching { AssetIndex.parse(FsUtil.readText(indexTask.destination)) }
                    .onSuccess { index ->
                        var missing = 0
                        for (obj in index.uniqueObjects()) {
                            checked++
                            val file = dirs.assetObjectPath(obj.relativeObjectPath)
                            val integrity = Integrity.of(sha1 = obj.hash, size = obj.size.takeIf { s -> s >= 0 })
                            val problem = integrity.check(file, fullHash)
                            if (problem != null) {
                                missing++
                                issues += InstallIssue(
                                    Component.ASSET,
                                    obj.name,
                                    problem,
                                    DownloadTask(obj.url(installerAssetBase()),
                                        file, integrity, obj.name)
                                )
                            }
                        }
                        if (missing == 0) notes += "${index.objects.size} assets present"
                        checked += 0
                    }
                    .onFailure {
                        issues += InstallIssue(Component.ASSET_INDEX, indexTask.label, "unreadable: ${it.message}", indexTask)
                    }
            }
        }

        // 6. Natives (desktop only: Android loads them from the component pack)
        if (!platform.isAndroid) {
            checked++
            val nativesDir = dirs.nativesDir(rv.meta.id)
            val hasFiles = Files.isDirectory(nativesDir) &&
                Files.list(nativesDir).use { it.count() } > 0
            if (!hasFiles && rv.libraries.any { it.isNative }) {
                issues += InstallIssue(Component.NATIVES, rv.meta.id, "natives not extracted", null)
            }
        }

        return InstallReport(rv.meta.id, checked, issues, notes.distinct())
    }

    /**
     * The asset base URL lives on the installer so a mirror is configured in one place; the
     * verifier needs it to build repair tasks.
     */
    private var installerAssetBase: String = pro.cslauncher.core.meta.AssetObject.DEFAULT_BASE

    fun withAssetBase(base: String): Verifier {
        installerAssetBase = base
        return this
    }

    private fun installerAssetBase(): String = installerAssetBase

    /** Sizes the repair: how many bytes would be fetched. Drives the "needs 400 MB" warning. */
    fun repairSizeBytes(report: InstallReport): Long =
        report.repairTasks.sumOf { it.integrity.size ?: 0L }

    fun repairSummary(report: InstallReport): String {
        if (report.complete) return "Nothing to repair"
        val bytes = repairSizeBytes(report)
        return "${report.issues.size} problems, ${FsUtil.human(bytes)} to download"
    }

    fun logReport(report: InstallReport) {
        CsLog.i("Verifier", report.summary())
        report.issues.take(20).forEach { CsLog.w("Verifier", "${it.component}/${it.name}: ${it.problem}") }
    }
}
