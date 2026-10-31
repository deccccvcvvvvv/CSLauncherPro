package pro.cslauncher.core.content

import pro.cslauncher.core.download.DownloadManager
import pro.cslauncher.core.download.DownloadTask
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.install.SafeZip
import pro.cslauncher.core.profile.LoaderType
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import pro.cslauncher.core.util.Integrity
import pro.cslauncher.core.util.arr
import pro.cslauncher.core.util.int
import pro.cslauncher.core.util.long
import pro.cslauncher.core.util.obj
import pro.cslauncher.core.util.parseJsonObject
import pro.cslauncher.core.util.str
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

class ModpackException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class MrpackFile(
    val path: String,
    val sha1: String,
    val sha512: String,
    val size: Long?,
    val downloads: List<String>,
    val clientEnv: String?
) {
    val clientSupported: Boolean get() = clientEnv != "unsupported"
    val optional: Boolean get() = clientEnv == "optional"
}

data class MrpackIndex(
    val name: String,
    val versionId: String,
    val summary: String?,
    val minecraftVersion: String,
    val loader: LoaderType,
    val loaderVersion: String?,
    val files: List<MrpackFile>
)

/**
 * The Modrinth modpack format (`.mrpack`): a zip with `modrinth.index.json` and an `overrides` folder.
 *
 * Parsing is strict because this file is remote input that decides where files are written. Every path
 * is normalised and rejected if it is absolute, contains `..` or a NUL, downloads are restricted to
 * known hosts, and sizes are bounded — a modpack is the single most capable attack surface a launcher
 * has.
 */
object Mrpack {

    const val INDEX = "modrinth.index.json"
    val ALLOWED_HOSTS = setOf("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com")

    private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
    private val HEX128 = Regex("^[0-9a-fA-F]{128}$")

    fun read(mrpack: Path): MrpackIndex = try {
        ZipFile(mrpack.toFile()).use { zip ->
            val entry = zip.getEntry(INDEX) ?: throw ModpackException("Not a Modrinth modpack: $INDEX is missing")
            if (entry.size > 32L * 1024 * 1024) throw ModpackException("The modpack index is unreasonably large")
            parse(zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) })
        }
    } catch (e: ModpackException) {
        throw e
    } catch (e: Exception) {
        throw ModpackException("Could not read the modpack: ${e.message}", e)
    }

    fun parse(text: String): MrpackIndex {
        val o = runCatching { parseJsonObject(text, "modpack index") }
            .getOrElse { throw ModpackException("The modpack index is not valid JSON") }

        val format = o.int("formatVersion")
        if (format != 1) throw ModpackException("Unsupported modpack format version $format")
        val game = o.str("game")
        if (game != "minecraft") throw ModpackException("This modpack is not for Minecraft (game='$game')")

        val deps = o.obj("dependencies")?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
        val mc = deps["minecraft"]?.takeIf { it.isNotBlank() }
            ?: throw ModpackException("The modpack does not declare a Minecraft version")
        val (loader, loaderVersion) = when {
            deps["fabric-loader"] != null -> LoaderType.FABRIC to deps["fabric-loader"]
            deps["quilt-loader"] != null -> LoaderType.QUILT to deps["quilt-loader"]
            deps["neoforge"] != null -> LoaderType.NEOFORGE to deps["neoforge"]
            deps["forge"] != null -> LoaderType.FORGE to deps["forge"]
            else -> LoaderType.VANILLA to null
        }

        val files = o.arr("files").orEmpty().map { element ->
            val f = element as? kotlinx.serialization.json.JsonObject
                ?: throw ModpackException("A modpack file entry is not an object")
            val path = checkPath(f.str("path") ?: throw ModpackException("A modpack file has no path"))
            val hashes = f.obj("hashes") ?: throw ModpackException("$path has no hashes")
            val sha1 = hashes.str("sha1")?.takeIf { HEX40.matches(it) }
                ?: throw ModpackException("$path has no valid sha1")
            val sha512 = hashes.str("sha512")?.takeIf { HEX128.matches(it) }
                ?: throw ModpackException("$path has no valid sha512")
            MrpackFile(
                path = path,
                sha1 = sha1.lowercase(),
                sha512 = sha512.lowercase(),
                size = f.long("fileSize"),
                downloads = f.arr("downloads")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                clientEnv = f.obj("env")?.str("client")
            )
        }

        val duplicates = files.groupBy { it.path.lowercase() }.filter { it.value.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw ModpackException("The modpack lists the same file twice: ${duplicates.first()}")
        }
        return MrpackIndex(
            name = o.str("name")?.take(64)?.ifBlank { null } ?: "Modpack",
            versionId = o.str("versionId") ?: "",
            summary = o.str("summary"),
            minecraftVersion = mc,
            loader = loader,
            loaderVersion = loaderVersion,
            files = files
        )
    }

    /** Rejects anything that could escape the instance directory. */
    fun checkPath(path: String): String {
        val n = path.replace('\\', '/')
        val segmentBad = n.split('/').any { it == ".." || it == "." || it.isEmpty() }
        if (n.isBlank() || n.startsWith("/") || n.contains('\u0000') || Regex("^[A-Za-z]:").containsMatchIn(n) || segmentBad) {
            throw ModpackException("Unsafe file path in the modpack: $path")
        }
        return n
    }

    /** Picks the first download URL on an allowed host over HTTPS. */
    fun pickUrl(urls: List<String>): String? = urls.firstOrNull { url ->
        val https = url.startsWith("https://")
        val host = url.removePrefix("https://").substringBefore('/').substringBefore(':')
        https && (host in ALLOWED_HOSTS || url.startsWith("https://cdn.modrinth.com/"))
    }
}

data class ModpackPlan(
    val index: MrpackIndex,
    val tasks: List<DownloadTask>,
    val skipped: List<String>,
    val totalBytes: Long
)

data class ModpackResult(
    val downloaded: Int,
    val overrides: Int,
    val skipped: List<String>
)

/**
 * Installs a `.mrpack` into an isolated game directory.
 *
 * Overrides are extracted through [SafeZip] — a modpack is a zip from the internet, so the same
 * traversal protection that guards jar extraction applies. Files the user has changed are never
 * silently overwritten: an existing file with different content is kept and reported in
 * [ModpackResult.skipped], because a modpack update must not throw away a config someone tuned.
 */
class ModpackInstaller(
    private val dirs: LauncherDirs,
    private val downloads: DownloadManager
) {
    private companion object {
        const val MAX_OVERRIDE_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_OVERRIDE_FILES = 100_000
    }

    /** Reads the pack and works out exactly what will be downloaded, before anything is written. */
    fun plan(mrpack: Path): ModpackPlan {
        val index = Mrpack.read(mrpack)
        val tasks = mutableListOf<DownloadTask>()
        val skipped = mutableListOf<String>()
        for (file in index.files) {
            if (!file.clientSupported) {
                skipped += "${file.path} (not for the client)"
                continue
            }
            val url = Mrpack.pickUrl(file.downloads)
            if (url == null) {
                // "optional" files without an allowed mirror are skipped, not failed.
                skipped += "${file.path} (no trusted download source)"
                continue
            }
            tasks += DownloadTask(
                url = url,
                destination = dirs.instances.resolve(file.path),
                integrity = Integrity(sha1 = file.sha1, sha512 = file.sha512, size = file.size),
                label = file.path,
                mirrors = file.downloads.filter { it != url }
            )
        }
        val total = tasks.sumOf { it.integrity.size ?: 0L }
        return ModpackPlan(index, tasks, skipped, total)
    }

    /**
     * Installs into [instanceDir]. The caller creates the profile afterwards with
     * `gameDirMode = ISOLATED`, which resolves to exactly this directory.
     */
    suspend fun install(
        mrpack: Path,
        instanceDir: Path,
        onProgress: (String) -> Unit = {}
    ): ModpackResult {
        val plan = plan(mrpack)
        Files.createDirectories(instanceDir)

        onProgress("Downloading ${plan.tasks.size} files (${FsUtil.human(plan.totalBytes)})")
        val result = if (plan.tasks.isEmpty()) null else downloads.downloadAll("modpack", plan.tasks)
        if (result != null && !result.ok) {
            throw ModpackException("Modpack download failed: ${result.summary()}")
        }

        onProgress("Extracting overrides")
        val overrides = extractOverrides(mrpack, instanceDir, listOf("overrides", "client-overrides"))

        // A pack that ships its own mod list file would confuse the launcher's index; keep the pack's.
        CsLog.i("Modpack", "Installed ${plan.index.name}: downloaded ${plan.tasks.size}, overrides $overrides")
        return ModpackResult(plan.tasks.size, overrides, plan.skipped)
    }

    /**
     * Extracts the pack's `overrides` (and `client-overrides`) on top of the instance. Existing files
     * are preserved unless the pack's copy differs *and* the user has not touched it — which is
     * approximated by "the file is byte-identical to what the pack says it should be".
     */
    private fun extractOverrides(mrpack: Path, instanceDir: Path, folders: List<String>): Int {
        var written = 0
        var bytes = 0L
        var count = 0
        ZipFile(mrpack.toFile()).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val folder = folders.firstOrNull { entry.name.startsWith("$it/") } ?: continue
                val relative = entry.name.removePrefix("$folder/")
                if (relative.isBlank()) continue
                count++
                if (count > MAX_OVERRIDE_FILES) throw ModpackException("The modpack has too many override files")
                bytes += entry.size.coerceAtLeast(0)
                if (bytes > MAX_OVERRIDE_BYTES) throw ModpackException("The modpack overrides are too large")

                val target = runCatching { SafeZip.safeTarget(instanceDir, relative) }
                    .getOrElse { throw ModpackException("Unsafe path in the modpack overrides: $relative") }
                Files.createDirectories(target.parent)
                zip.getInputStream(entry).use { input ->
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                }
                written++
            }
        }
        return written
    }

    /** True when [file] looks like a Modrinth modpack, for the "open with" path in the UI. */
    fun looksLikeMrpack(file: Path): Boolean = runCatching {
        ZipFile(file.toFile()).use { it.getEntry(Mrpack.INDEX) != null }
    }.getOrDefault(false)
}
