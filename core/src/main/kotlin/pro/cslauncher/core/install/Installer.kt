package pro.cslauncher.core.install

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.cslauncher.core.download.BatchProgress
import pro.cslauncher.core.download.DownloadFailedException
import pro.cslauncher.core.download.DownloadManager
import pro.cslauncher.core.download.DownloadTask
import pro.cslauncher.core.download.InsufficientStorageException
import pro.cslauncher.core.meta.AssetIndex
import pro.cslauncher.core.meta.AssetObject
import pro.cslauncher.core.meta.Download
import pro.cslauncher.core.meta.LaunchFeatures
import pro.cslauncher.core.meta.Library
import pro.cslauncher.core.meta.NativeClassifier
import pro.cslauncher.core.meta.VersionManifest
import pro.cslauncher.core.meta.VersionMetadata
import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import pro.cslauncher.core.util.Integrity
import pro.cslauncher.core.util.MetadataException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

enum class InstallStage(val title: String) {
    RESOLVE("Resolving version"),
    DIRECTORIES("Creating directories"),
    CLIENT("Downloading client"),
    LIBRARIES("Downloading libraries"),
    ASSETS("Downloading assets"),
    NATIVES("Preparing native libraries"),
    LOADER("Installing mod loader"),
    VERIFY("Verifying installation")
}

data class InstallEvent(val stage: InstallStage, val message: String, val progress: BatchProgress? = null)

class InstallException(val stage: InstallStage, message: String, cause: Throwable? = null) :
    Exception("[${stage.title}] $message", cause)

/** A library resolved for this platform, with the file it must end up at. */
data class ResolvedLibrary(
    val library: Library,
    val file: Path,
    val download: Download?,
    val isNative: Boolean,
    val onClasspath: Boolean
)

data class ResolvedVersion(
    val meta: VersionMetadata,
    val chain: List<String>,
    val jarVersionId: String,
    val libraries: List<ResolvedLibrary>,
    val skippedByRules: List<String>,
    /** glibc natives from the metadata that Android cannot load; a component supplies them instead. */
    val desktopNativesReplaced: List<String>
) {
    val classpathFiles: List<Path> get() = libraries.filter { it.onClasspath }.map { it.file }
    val nativeLibraries: List<ResolvedLibrary> get() = libraries.filter { it.isNative }
}

/**
 * Resolves a version and installs it.
 *
 * Design rules:
 * * **Resolve is pure metadata work** — it never writes files, so the UI can show exactly what a
 *   version needs before anything is downloaded.
 * * **Re-installing is cheap** — the download engine skips files whose integrity matches, so
 *   `install()` on an intact version performs no network traffic.
 * * **Natives on Android come from the launcher, not from Mojang.** Mojang's native classifiers are
 *   glibc builds for desktop Linux; they cannot be loaded on Bionic. They are recorded in
 *   [ResolvedVersion.desktopNativesReplaced] and kept off the classpath, and the Android component
 *   pack supplies the real ones.
 * * **Every failure is stage-tagged** ([InstallException.stage]) so the UI can point at what broke.
 */
class Installer(
    private val dirs: LauncherDirs,
    private val http: HttpTransport,
    private val downloads: DownloadManager,
    val platform: PlatformInfo,
    private val assetBaseUrl: String = AssetObject.DEFAULT_BASE,
    private val features: LaunchFeatures = LaunchFeatures()
) {

    /** Reads (or fetches) the version JSON chain and builds the file list for this platform. */
    suspend fun resolve(versionId: String, manifest: VersionManifest?): ResolvedVersion = withContext(Dispatchers.IO) {
        val chain = mutableListOf<String>()
        var meta = loadOrFetchJson(versionId, manifest)
        chain += meta.id
        var current = meta
        var jarId = if (current.clientDownload != null) current.id else versionId

        while (current.inheritsFrom != null) {
            val parentId = current.inheritsFrom!!
            if (parentId in chain) throw MetadataException("Circular inheritsFrom chain: ${chain + parentId}")
            val parent = loadOrFetchJson(parentId, manifest)
            chain += parent.id
            if (meta.clientDownload == null && parent.clientDownload != null) jarId = parent.id
            meta = VersionMetadata.merge(meta, parent)
            current = parent
        }
        if (meta.mainClass.isBlank()) throw MetadataException("Resolved version $versionId has no main class")
        if (Files.isRegularFile(dirs.versionJar(jarId))) {
            // The jar may be named after the child while coming from the parent; keep the real one.
        }
        resolveLibraries(meta, chain, jarId)
    }

    private suspend fun loadOrFetchJson(id: String, manifest: VersionManifest?): VersionMetadata {
        val file = dirs.versionJson(id)
        val entry = manifest?.find(id)
        if (Files.isRegularFile(file)) {
            val text = FsUtil.readText(file)
            val valid = entry?.sha1 == null || runCatching { pro.cslauncher.core.util.Hashing.sha1(file) }
                .getOrNull()?.equals(entry.sha1, ignoreCase = true) == true
            if (valid) {
                return runCatching { VersionMetadata.parse(text) }.getOrElse {
                    CsLog.w("Installer", "Cached metadata for $id is unusable, re-fetching: ${it.message}")
                    fetchJson(id, entry?.url ?: throw MetadataException(it.message ?: "unreadable metadata"), file)
                }
            }
        }
        val url = entry?.url
            ?: throw MetadataException("Version $id is not installed and is not in the version list")
        return fetchJson(id, url, file)
    }

    private suspend fun fetchJson(id: String, url: String, file: Path): VersionMetadata {
        CsLog.i("Installer", "Fetching metadata for $id")
        val text = withContext(Dispatchers.IO) { http.getText(url) }
        // Validate before writing: a broken JSON must not replace a good cached copy.
        val meta = VersionMetadata.parse(text)
        FsUtil.writeAtomic(file, text)
        return meta
    }

    /**
     * Chooses libraries, natives and classpath entries for [platform].
     *
     * Android specifics:
     * * Mojang's `natives-linux` classifiers are glibc; loading one on Bionic crashes the process
     *   with an unrelated error, so they are skipped and reported instead.
     * * LWJGL natives are supplied by the launcher's component pack, so the desktop ones must not be
     *   extracted on top of them.
     */
    fun resolveLibraries(meta: VersionMetadata, chain: List<String>, jarId: String): ResolvedVersion {
        val out = mutableListOf<ResolvedLibrary>()
        val skipped = mutableListOf<String>()
        val replaced = mutableListOf<String>()

        for (lib in meta.libraries) {
            if (!pro.cslauncher.core.meta.Rule.allows(lib.rules, platform, features)) {
                skipped += lib.name
                continue
            }

            // Native classifiers (LWJGL/JInput style, or modern "natives-*" coordinates).
            if (lib.natives.isNotEmpty()) {
                val chosen = lib.nativeDownload(platform)
                if (chosen == null) {
                    skipped += lib.name
                    continue
                }
                if (platform.isAndroid) {
                    // glibc build: a launcher component replaces it, so it is never installed here.
                    replaced += lib.name
                    if (lib.onClasspath(platform, features)) {
                        out += ResolvedLibrary(
                            lib,
                            dirs.libraryFile(lib.artifact!!.path ?: lib.coordinate.path()),
                            lib.artifact,
                            isNative = false,
                            onClasspath = true
                        )
                    }
                    continue
                }
                val (classifier, download) = chosen
                out += ResolvedLibrary(
                    lib,
                    dirs.libraryFile(download.path ?: lib.coordinate.withClassifier(classifier).path()),
                    download,
                    isNative = true,
                    onClasspath = false
                )
                if (lib.onClasspath(platform, features)) {
                    out += ResolvedLibrary(
                        lib,
                        dirs.libraryFile(lib.artifact!!.path ?: lib.coordinate.path()),
                        lib.artifact,
                        isNative = false,
                        onClasspath = true
                    )
                }
                continue
            }

            if (lib.isNativeClassifierCoordinate) {
                // Modern style: the coordinate itself is the native jar.
                if (platform.isAndroid) {
                    replaced += lib.name
                    continue
                }
                val a = lib.artifact ?: continue
                out += ResolvedLibrary(lib, dirs.libraryFile(a.path ?: lib.coordinate.path()), a, true, false)
                continue
            }

            val a = lib.artifact ?: continue
            out += ResolvedLibrary(lib, dirs.libraryFile(a.path ?: lib.coordinate.path()), a, false, true)
        }

        val dedup = out.distinctBy { it.file.toString() + it.isNative }
        return ResolvedVersion(meta, chain, jarId, dedup, skipped.distinct(), replaced.distinct())
    }

    // ---------------------------------------------------------------- tasks

    fun clientTask(rv: ResolvedVersion): DownloadTask? {
        val d = rv.meta.clientDownload ?: return null
        return DownloadTask(d.url, dirs.versionJar(rv.jarVersionId), d.integrity, "${rv.jarVersionId}.jar")
    }

    fun libraryTasks(rv: ResolvedVersion): List<DownloadTask> = rv.libraries.mapNotNull { rl ->
        rl.download?.takeIf { it.url.isNotBlank() }
            ?.let { DownloadTask(it.url, rl.file, it.integrity, rl.library.name) }
    }

    fun assetIndexTask(rv: ResolvedVersion): DownloadTask? = rv.meta.assetIndex?.let {
        DownloadTask(
            it.url,
            dirs.assetIndexFile(it.id),
            Integrity.of(sha1 = it.sha1, size = it.size),
            "asset index ${it.id}"
        )
    }

    fun assetTasks(index: AssetIndex): List<DownloadTask> = index.uniqueObjects().map {
        DownloadTask(
            it.url(assetBaseUrl),
            dirs.assetObjectPath(it.relativeObjectPath),
            Integrity.of(sha1 = it.hash, size = it.size.takeIf { s -> s >= 0 }),
            it.name
        )
    }

    fun loggingTask(rv: ResolvedVersion): DownloadTask? = rv.meta.logging?.let {
        DownloadTask(it.download.url, dirs.logConfigFile(it.fileId), it.download.integrity, it.fileId)
    }

    // ---------------------------------------------------------------- install

    suspend fun install(
        versionId: String,
        manifest: VersionManifest?,
        onEvent: (InstallEvent) -> Unit = {}
    ): InstallReport {
        fun stage(s: InstallStage, msg: String, p: BatchProgress? = null) = onEvent(InstallEvent(s, msg, p))

        stage(InstallStage.RESOLVE, "Reading metadata for $versionId")
        val rv = try {
            resolve(versionId, manifest)
        } catch (e: Exception) {
            throw InstallException(InstallStage.RESOLVE, e.message ?: "metadata error", e)
        }

        stage(InstallStage.DIRECTORIES, "Preparing directories")
        withContext(Dispatchers.IO) { dirs.createBase() }

        suspend fun batch(s: InstallStage, name: String, tasks: List<DownloadTask>) {
            if (tasks.isEmpty()) return
            val res = try {
                downloads.downloadAll(name, tasks) { stage(s, it.currentFile ?: name, it) }
            } catch (e: InsufficientStorageException) {
                throw InstallException(s, e.message!!, e)
            }
            if (!res.ok) throw InstallException(s, res.summary(), DownloadFailedException(res))
        }

        batch(InstallStage.CLIENT, "$versionId client", listOfNotNull(clientTask(rv), loggingTask(rv)))
        batch(InstallStage.LIBRARIES, "$versionId libraries", libraryTasks(rv))

        rv.meta.assetIndex?.let { ref ->
            val idxTask = assetIndexTask(rv)!!
            batch(InstallStage.ASSETS, "$versionId asset index", listOf(idxTask))
            val index = withContext(Dispatchers.IO) { AssetIndex.parse(FsUtil.readText(idxTask.destination)) }
            batch(InstallStage.ASSETS, "$versionId assets", assetTasks(index))
            withContext(Dispatchers.IO) { materializeVirtualAssets(rv, index) }
            CsLog.i("Installer", "Asset index ${ref.id}: ${index.objects.size} objects")
        }

        stage(InstallStage.NATIVES, if (platform.isAndroid) "Natives come from the Android runtime component" else "Extracting natives")
        withContext(Dispatchers.IO) { extractNatives(rv) }

        stage(InstallStage.VERIFY, "Verifying files")
        val report = Verifier(dirs, this, platform).verify(rv, fullHash = true)
        stage(InstallStage.VERIFY, report.summary())
        return report
    }

    /** Legacy "virtual" asset indexes need a name-based copy under `assets/virtual/<index>`. */
    fun materializeVirtualAssets(rv: ResolvedVersion, index: AssetIndex) {
        if (!index.virtual) return
        val id = rv.meta.assetIndex?.id ?: return
        val root = dirs.assets.resolve("virtual").resolve(LauncherDirs.checkId(id))
        for (o in index.objects) {
            val target = SafeZip.safeTarget(root, o.name)
            if (Files.isRegularFile(target) && Files.size(target) == o.size) continue
            val source = dirs.assetObjectPath(o.relativeObjectPath)
            if (!Files.isRegularFile(source)) continue
            Files.createDirectories(target.parent)
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Pre-1.6 `map_to_resources`: the game reads assets from `<gameDir>/resources`. */
    fun materializeResources(rv: ResolvedVersion, gameDir: Path) {
        val id = rv.meta.assetIndex?.id ?: return
        val file = dirs.assetIndexFile(id)
        if (!Files.isRegularFile(file)) return
        val index = AssetIndex.parse(FsUtil.readText(file))
        if (!index.mapToResources) return
        val root = gameDir.resolve("resources")
        for (o in index.objects) {
            val target = SafeZip.safeTarget(root, o.name)
            if (Files.isRegularFile(target) && Files.size(target) == o.size) continue
            Files.createDirectories(target.parent)
            Files.copy(dirs.assetObjectPath(o.relativeObjectPath), target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Extracts desktop natives into `natives/<version>` (Android gets its natives from a component
     * pack instead). Extraction starts from an empty directory: a half-written `.so` is a launch
     * crash that looks like a Java problem, which is the worst kind of bug to debug.
     */
    fun extractNatives(rv: ResolvedVersion) {
        if (platform.isAndroid) return
        val out = dirs.nativesDir(rv.meta.id)
        if (Files.exists(out)) FsUtil.deleteTreeWithin(dirs.nativeRoot, out)
        Files.createDirectories(out)

        val nativeExt = Regex("\\.(so|dll|dylib|jnilib)$")
        for (rl in rv.libraries.filter { it.isNative }) {
            if (!Files.isRegularFile(rl.file)) {
                CsLog.w("Installer", "Native jar missing for ${rl.library.name}")
                continue
            }
            val excludes = rl.library.extractExclude.ifEmpty { listOf("META-INF/") }
            runCatching {
                SafeZip.extract(rl.file, out, filter = { name ->
                    excludes.none { name.startsWith(it) } && nativeExt.containsMatchIn(name)
                })
            }.onFailure {
                CsLog.e("Installer", "Cannot extract natives from ${rl.file.fileName}: ${it.message}")
            }
        }

        // Modern natives live in nested directories (linux/x64/org/lwjgl/…); java.library.path only
        // looks at one level, so the libraries are flattened into the natives root.
        runCatching {
            Files.walk(out).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.parent != out && nativeExt.containsMatchIn(it.fileName.toString()) }
                    .toList()
                    .forEach { src -> Files.move(src, out.resolve(src.fileName.toString()), StandardCopyOption.REPLACE_EXISTING) }
            }
        }.onFailure { CsLog.w("Installer", "Could not flatten natives: ${it.message}") }
    }

    /** Convenience for the UI: is this version usable on this device without downloading? */
    suspend fun status(versionId: String, manifest: VersionManifest? = null): InstallReport = try {
        Verifier(dirs, this, platform).verify(resolve(versionId, manifest), fullHash = false)
    } catch (e: Exception) {
        InstallReport(
            versionId,
            0,
            listOf(InstallIssue(Component.METADATA, versionId, e.message ?: "unresolvable", null)),
            emptyList()
        )
    }

    /** Reads a native jar's shared-library names without extracting (diagnostics/component matching). */
    fun nativeJarLibraries(jar: Path): List<String> = runCatching {
        ZipFile(jar.toFile()).use { zf ->
            zf.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".so") }
                .map { it.name.substringAfterLast('/') }
                .toList()
        }
    }.getOrDefault(emptyList())
}
