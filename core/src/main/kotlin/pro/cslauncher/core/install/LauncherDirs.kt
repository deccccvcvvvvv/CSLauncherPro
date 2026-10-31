package pro.cslauncher.core.install

import java.nio.file.Files
import java.nio.file.Path

/**
 * Directory layout of a CS Launcher Pro installation.
 *
 * Two storage classes are deliberately separated:
 * * **Shared, content-addressed data** (`libraries`, `assets`, `versions`) can be shared by every
 *   profile, because the game reads it read-only.
 * * **Per-profile data** (`instances/<id>`) is a complete game directory, so a mod in one profile
 *   can never break another.
 *
 * On Android [nativeRoot] and [runtimeRoot] **must** live in internal storage: `dlopen` is not
 * permitted from app-specific external storage, and a runtime outside internal storage simply
 * cannot be loaded.
 */
class LauncherDirs(
    val root: Path,
    val nativeRoot: Path = root.resolve("natives"),
    val runtimeRoot: Path = root.resolve("runtimes"),
    val componentRoot: Path = root.resolve("components")
) {
    val versions: Path = root.resolve("versions")
    val libraries: Path = root.resolve("libraries")
    val assets: Path = root.resolve("assets")
    val assetIndexes: Path = assets.resolve("indexes")
    val assetObjects: Path = assets.resolve("objects")
    val logConfigs: Path = assets.resolve("log_configs")
    val instances: Path = root.resolve("instances")
    val sharedGameDir: Path = root.resolve("game")
    val config: Path = root.resolve("launcher-config")
    val cache: Path = root.resolve("cache")
    val backups: Path = root.resolve("backups")
    val sessions: Path = root.resolve("sessions")
    val recordings: Path = root.resolve("recordings")

    fun versionDir(id: String): Path = versions.resolve(checkId(id))
    fun versionJson(id: String): Path = versionDir(id).resolve("$id.json")
    fun versionJar(id: String): Path = versionDir(id).resolve("$id.jar")

    fun nativesDir(versionId: String): Path = nativeRoot.resolve(checkId(versionId))

    fun assetIndexFile(indexId: String): Path = assetIndexes.resolve("${checkId(indexId)}.json")

    fun assetObjectPath(relativeObjectPath: String): Path {
        val p = assetObjects.resolve(relativeObjectPath).normalize()
        require(p.startsWith(assetObjects.normalize()) && !relativeObjectPath.contains("..")) {
            "Unsafe asset path $relativeObjectPath"
        }
        return p
    }

    /**
     * Resolves a Maven-style library path. Remote metadata supplies this path, so it is validated
     * here: a library entry pointing at `../../config/profiles.json` must never be written.
     */
    fun libraryFile(relPath: String): Path {
        val p = libraries.resolve(relPath).normalize()
        require(p.startsWith(libraries.normalize()) && !relPath.contains("..")) { "Unsafe library path $relPath" }
        return p
    }

    fun logConfigFile(fileId: String): Path = logConfigs.resolve(checkId(fileId))

    /** Everything a profile's game directory needs, created up front. */
    fun instanceDir(profileId: String): Path = instances.resolve(checkId(profileId))

    fun createBase() {
        listOf(
            versions, libraries, assetIndexes, assetObjects, logConfigs, instances, sharedGameDir,
            config, cache, backups, sessions, recordings, nativeRoot, runtimeRoot, componentRoot
        ).forEach { Files.createDirectories(it) }
    }

    fun createGameSubdirs(gameDir: Path) {
        GAME_SUBDIRS.forEach { Files.createDirectories(gameDir.resolve(it)) }
    }

    companion object {
        /**
         * Every id ends up as a directory name, so the character class is deliberately narrow: no
         * path separators, no drive letters, nothing the filesystem would interpret.
         */
        val checkIdPattern: Regex = Regex("^[A-Za-z0-9._+ -]{1,96}$")
        private val ID = checkIdPattern

        /** Version ids, index ids and profile ids all end up as directory names. */
        fun checkId(id: String): String {
            require(id.isNotEmpty() && ID.matches(id) && id != "." && id != ".." && !id.contains("..")) {
                "Invalid identifier '$id'"
            }
            return id
        }

        fun isValidId(id: String): Boolean =
            runCatching { checkId(id) }.isSuccess

        val GAME_SUBDIRS = listOf(
            "mods", "resourcepacks", "shaderpacks", "saves", "screenshots",
            "logs", "crash-reports", "config", "saves"
        ).distinct()
    }
}
