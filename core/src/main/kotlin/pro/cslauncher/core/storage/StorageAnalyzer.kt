package pro.cslauncher.core.storage

import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.util.FsUtil
import java.nio.file.Files
import java.nio.file.Path

/**
 * Storage categories, split by one property that matters to the user: can the launcher delete this
 * safely? [regenerable] marks data that can be downloaded again — everything else is user content
 * and is only ever removed per item, with an explicit confirmation.
 */
enum class StorageCategory(val title: String, val regenerable: Boolean) {
    INSTALLATIONS("Game versions", true),
    LIBRARIES("Libraries", true),
    ASSETS("Assets", true),
    NATIVES("Extracted natives", true),
    RUNTIMES("Java runtimes", true),
    COMPONENTS("Native components", true),
    CACHE("Download cache & temp", true),
    SESSIONS("Launch logs", true),
    MODS("Mods", false),
    RESOURCE_PACKS("Resource packs", false),
    SHADER_PACKS("Shader packs", false),
    WORLDS("Worlds", false),
    SCREENSHOTS("Screenshots", false),
    RECORDINGS("Recordings", false),
    BACKUPS("Backups", false);

    val deletable: Boolean get() = regenerable
}

data class StorageEntry(val category: StorageCategory, val bytes: Long, val paths: List<Path>) {
    fun describe(): String = "${category.title}: ${FsUtil.human(bytes)}"
}

/**
 * Reports and reclaims disk space.
 *
 * Two rules:
 * * **never delete user content automatically** — worlds and mods are irreplaceable, so clearing a
 *   category that is not [StorageCategory.regenerable] throws instead of guessing;
 * * **a clear must actually free something** — the returned number is the measured difference, not an
 *   estimate, so the UI cannot claim to have cleaned 2 GB it did not.
 */
class StorageAnalyzer(private val dirs: LauncherDirs) {

    fun analyze(gameDirs: List<Path>): List<StorageEntry> {
        fun entry(category: StorageCategory, vararg paths: Path) =
            StorageEntry(category, paths.sumOf { FsUtil.sizeOf(it) }, paths.toList())

        fun game(sub: String): Array<Path> = gameDirs.distinct().map { it.resolve(sub) }.toTypedArray()

        return listOf(
            entry(StorageCategory.INSTALLATIONS, dirs.versions),
            entry(StorageCategory.LIBRARIES, dirs.libraries),
            entry(StorageCategory.ASSETS, dirs.assets),
            entry(StorageCategory.NATIVES, dirs.nativeRoot),
            entry(StorageCategory.RUNTIMES, dirs.runtimeRoot),
            entry(StorageCategory.COMPONENTS, dirs.componentRoot),
            entry(StorageCategory.CACHE, dirs.cache),
            entry(StorageCategory.SESSIONS, dirs.sessions),
            entry(StorageCategory.MODS, *game("mods")),
            entry(StorageCategory.RESOURCE_PACKS, *game("resourcepacks")),
            entry(StorageCategory.SHADER_PACKS, *game("shaderpacks")),
            entry(StorageCategory.WORLDS, *game("saves")),
            entry(StorageCategory.SCREENSHOTS, *game("screenshots")),
            entry(StorageCategory.RECORDINGS, dirs.recordings),
            entry(StorageCategory.BACKUPS, dirs.backups)
        ).filter { it.paths.isNotEmpty() }
    }

    fun totalBytes(gameDirs: List<Path>): Long = analyze(gameDirs).sumOf { it.bytes }

    /** The N biggest categories, for the "storage is running low" sheet. */
    fun biggest(gameDirs: List<Path>, count: Int = 4): List<StorageEntry> =
        analyze(gameDirs).sortedByDescending { it.bytes }.take(count).filter { it.bytes > 0 }

    /**
     * Frees space for [category] and returns the bytes actually freed.
     *
     * [keepSessions] keeps the newest launch logs, because those are the ones a bug report needs.
     */
    fun clear(category: StorageCategory, keepSessions: Int = 5): Long {
        require(category.deletable) {
            "${category.title} contains user content and cannot be cleared automatically"
        }
        return when (category) {
            StorageCategory.CACHE -> emptyDirectory(dirs.cache)
            StorageCategory.NATIVES -> emptyDirectory(dirs.nativeRoot)
            StorageCategory.SESSIONS -> trimSessions(keepSessions)
            else -> throw IllegalArgumentException(
                "${category.title} is removed per item (uninstall a version, runtime or component)"
            )
        }
    }

    private fun emptyDirectory(root: Path): Long {
        if (!Files.isDirectory(root)) return 0
        val before = FsUtil.sizeOf(root)
        childrenOf(root).forEach { child ->
            runCatching { FsUtil.deleteTreeWithin(root, child) }
        }
        return (before - FsUtil.sizeOf(root)).coerceAtLeast(0)
    }

    private fun trimSessions(keep: Int): Long {
        if (!Files.isDirectory(dirs.sessions)) return 0
        val before = FsUtil.sizeOf(dirs.sessions)
        childrenOf(dirs.sessions)
            .sortedByDescending { it.fileName.toString() }
            .drop(keep.coerceAtLeast(0))
            .forEach { runCatching { FsUtil.deleteTreeWithin(dirs.sessions, it) } }
        return (before - FsUtil.sizeOf(dirs.sessions)).coerceAtLeast(0)
    }

    /**
     * Leftover `.part` files and staging directories from an interrupted download or install. These are
     * pure garbage — nothing references them — and on a phone they can add up to hundreds of MB.
     */
    fun cleanLeftovers(): Long {
        var freed = 0L
        fun sweep(root: Path, predicate: (Path) -> Boolean) {
            if (!Files.isDirectory(root)) return
            runCatching {
                Files.walk(root, 4).use { stream ->
                    stream.filter { predicate(it) }.toList().forEach { path ->
                        val size = FsUtil.sizeOf(path)
                        if (runCatching { FsUtil.deleteTreeWithin(root, path) }.isSuccess) freed += size
                    }
                }
            }
        }
        val isStaging = { p: Path ->
            val n = p.fileName?.toString().orEmpty()
            n.startsWith(".staging") || n.startsWith(".incoming") || n.startsWith(".old-")
        }
        sweep(dirs.cache) { it.fileName?.toString()?.endsWith(".part") == true }
        sweep(dirs.runtimeRoot) { isStaging(it) }
        sweep(dirs.componentRoot) { isStaging(it) }
        return freed
    }

    /** True when the device is close enough to full that an install may fail mid-way. */
    fun isLowOnSpace(root: Path, requiredBytes: Long = 1L shl 30): Boolean {
        val free = FsUtil.usableSpace(root)
        return free in 1 until requiredBytes
    }

    fun summaryLine(gameDirs: List<Path>): String {
        val entries = analyze(gameDirs)
        val total = entries.sumOf { it.bytes }
        val reclaimable = entries.filter { it.category.deletable }.sumOf { it.bytes }
        return "${FsUtil.human(total)} used, ${FsUtil.human(reclaimable)} can be regenerated"
    }

    private fun childrenOf(p: Path): List<Path> =
        if (!Files.isDirectory(p)) emptyList() else Files.list(p).use { it.toList() }
}
