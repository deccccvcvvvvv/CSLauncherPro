package pro.cslauncher.core.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import pro.cslauncher.core.download.DownloadManager
import pro.cslauncher.core.download.DownloadTask
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import java.nio.file.Files
import java.nio.file.Path

/**
 * A piece of content the launcher installed from a catalogue, so the user can later see *what* a file
 * is and where it came from. Without this, a mods folder becomes an anonymous pile of jars and
 * updating anything means guessing.
 */
@Serializable
data class IndexedContent(
    val source: String,
    val projectId: String,
    val title: String,
    val fileName: String,
    val kind: String,
    val versionName: String,
    /** Set when this file was installed as a dependency of another project. */
    val dependencyOf: String? = null,
    val atMs: Long = System.currentTimeMillis()
) {
    val key: String get() = ContentIndex.key(source, projectId)
}

@Serializable
private data class ContentIndexFile(val version: Int = 1, val items: List<IndexedContent> = emptyList())

/**
 * Records catalogue installs next to the game directory.
 *
 * The index is advisory, never authoritative: entries whose file has disappeared are filtered out on
 * read, so a user who deletes a jar by hand does not end up with a ghost row.
 */
class ContentIndex(private val gameDir: Path) {

    private val file: Path get() = gameDir.resolve(".cs-content.json")

    private fun readAll(): List<IndexedContent> {
        if (!Files.isRegularFile(file)) return emptyList()
        return runCatching {
            CsJson.decodeFromString(ContentIndexFile.serializer(), FsUtil.readText(file)).items
        }.getOrElse {
            CsLog.w("Content", "Content index unreadable, starting a new one: ${it.message}")
            emptyList()
        }
    }

    fun load(): List<IndexedContent> = readAll().filter { entry ->
        val kind = runCatching { ProjectKind.valueOf(entry.kind) }.getOrNull() ?: return@filter false
        if (kind == ProjectKind.MODPACK) return@filter true
        Files.isRegularFile(gameDir.resolve(dirFor(kind)).resolve(entry.fileName))
    }

    fun installed(kind: ProjectKind): Map<String, IndexedContent> =
        load().filter { it.kind == kind.name }.associateBy { it.key }

    fun forProject(source: String, projectId: String): IndexedContent? =
        load().firstOrNull { it.key == key(source, projectId) }

    @Synchronized
    fun record(items: List<IndexedContent>) {
        if (items.isEmpty()) return
        val keys = items.map { it.key }.toSet()
        val merged = load().filterNot { it.key in keys } + items
        Files.createDirectories(gameDir)
        FsUtil.writeAtomic(file, CsJson.encodeToString(ContentIndexFile.serializer(), ContentIndexFile(items = merged)))
    }

    @Synchronized
    fun forget(source: String, projectId: String) {
        val remaining = load().filterNot { it.key == key(source, projectId) }
        FsUtil.writeAtomic(file, CsJson.encodeToString(ContentIndexFile.serializer(), ContentIndexFile(items = remaining)))
    }

    companion object {
        fun key(source: String, projectId: String) = "$source:$projectId"

        fun dirFor(kind: ProjectKind): String = when (kind) {
            ProjectKind.MOD -> "mods"
            ProjectKind.RESOURCE_PACK -> "resourcepacks"
            ProjectKind.SHADER -> "shaderpacks"
            ProjectKind.DATAPACK -> "datapacks"
            ProjectKind.MODPACK -> throw IllegalArgumentException("Modpacks are installed as profiles, not into a folder")
        }

        private val VERSION_START = Regex("^(.+?)[-_+]v?\\d")

        /** `create-0.5.1-fabric.jar` → `create`, so an updated file can replace the old one. */
        fun baseName(fileName: String): String {
            val n = fileName.lowercase()
                .removeSuffix(".disabled")
                .removeSuffix(".jar")
                .removeSuffix(".zip")
            return VERSION_START.find(n)?.groupValues?.get(1) ?: n
        }
    }
}

data class CatalogInstallResult(
    val installed: List<IndexedContent>,
    val skipped: List<String>,
    val failed: List<String>,
    val warnings: List<String>
) {
    val ok: Boolean get() = failed.isEmpty()
}

/**
 * Installs catalogue content into a game directory.
 *
 * The two behaviours that matter:
 * * **dependencies are resolved**, because a mod that needs Fabric API and does not get it is a crash
 *   the user cannot explain;
 * * **an older version of the same mod is replaced**, not piled up next to the new one — two versions
 *   of one mod is the classic "Mixin apply failed" crash. The replacement is identified by
 *   [ContentIndex.baseName] from the file name.
 */
class CatalogInstaller(
    private val downloads: DownloadManager,
    private val maxDependencies: Int = 30
) {

    suspend fun install(
        catalog: ContentCatalog,
        file: CatalogFile,
        kind: ProjectKind,
        gameDir: Path,
        gameVersion: String?,
        loader: String?,
        withDependencies: Boolean = true,
        projectTitle: String = file.projectId
    ): CatalogInstallResult = withContext(Dispatchers.IO) {
        val index = ContentIndex(gameDir)
        val targetDir = gameDir.resolve(ContentIndex.dirFor(kind))
        Files.createDirectories(targetDir)

        val installed = mutableListOf<IndexedContent>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val queue = ArrayDeque<Pair<String, String?>>()   // projectId to (dependencyOf)
        queue.add(file.projectId to null)
        val visited = mutableSetOf<String>()

        var primary: CatalogFile? = file
        while (queue.isNotEmpty() && visited.size < maxDependencies) {
            val (projectId, dependencyOf) = queue.removeFirst()
            if (!visited.add(projectId)) continue

            val chosen = if (projectId == file.projectId && primary != null) {
                primary
            } else {
                runCatching { catalog.bestFile(projectId, gameVersion, loader) }
                    .onFailure { warnings += "Could not read files for $projectId: ${it.message}" }
                    .getOrNull()
            }
            if (chosen == null) {
                if (dependencyOf != null) skipped += "$projectId (no build for this version)"
                primary = null
                continue
            }
            primary = null

            // Remove an older build of the same project so two versions never coexist.
            val old = index.forProject(chosen.source, chosen.projectId)
            val oldBase = old?.let { ContentIndex.baseName(it.fileName) }
            if (old != null && old.fileName != chosen.fileName) {
                runCatching { Files.deleteIfExists(targetDir.resolve(old.fileName)) }
            }

            val task = chosen.task(targetDir)
            val result = downloads.downloadOne(task)
            if (!result.ok) {
                if (dependencyOf != null) {
                    skipped += "${chosen.fileName}: ${result.error}"
                } else {
                    failed += "${chosen.fileName}: ${result.error}"
                }
                continue
            }

            // A same-named file from another version would be shadowed by the game; drop it.
            if (oldBase != null && ContentIndex.baseName(chosen.fileName) == oldBase && old?.fileName != chosen.fileName) {
                runCatching { Files.deleteIfExists(targetDir.resolve(old!!.fileName)) }
            }

            val entry = IndexedContent(
                source = chosen.source,
                projectId = chosen.projectId,
                title = if (chosen.projectId == file.projectId) projectTitle else chosen.projectId,
                fileName = task.destination.fileName.toString(),
                kind = kind.name,
                versionName = chosen.versionName,
                dependencyOf = dependencyOf
            )
            installed += entry
            index.record(listOf(entry))
            CsLog.i("Content", "Installed ${entry.title} ${entry.versionName} (${entry.fileName})")

            if (withDependencies) {
                chosen.requiredDependencies.forEach { dep ->
                    if (dep !in visited) queue.add(dep to chosen.projectId)
                }
            }
        }

        if (visited.size >= maxDependencies) {
            warnings += "Stopped after $maxDependencies dependencies; some may still be missing"
        }
        CatalogInstallResult(installed, skipped, failed, warnings)
    }

    /** Downloads a list of files (used by the modpack installer too). */
    suspend fun installTasks(batchName: String, tasks: List<DownloadTask>): Pair<Int, List<String>> {
        if (tasks.isEmpty()) return 0 to emptyList()
        val result = downloads.downloadAll(batchName, tasks)
        return result.results.count { it.ok } to result.failed.map { "${it.task.label}: ${it.error}" }
    }
}
