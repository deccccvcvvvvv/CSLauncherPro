package pro.cslauncher.core.component

import kotlinx.serialization.Serializable
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.install.SafeZip
import pro.cslauncher.core.meta.MavenCoordinate
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import pro.cslauncher.core.util.Integrity
import java.nio.file.Files
import java.nio.file.Path

/**
 * A file inside a component pack, with the hash that must match before the pack is used.
 * Sizes are recorded so a truncated download is caught even when the hash format changes.
 */
@Serializable
data class ComponentFile(val path: String, val sha256: String, val size: Long)

/**
 * The `cs-component.json` inside every component pack.
 *
 * Android native components (LWJGL 3 built for bionic, renderer libraries such as GL4ES /
 * Mesa-OSMesa+Zink / ANGLE) ship as these packs. The manifest is the whole contract: what is inside,
 * what it provides, which ABI it is built for and under which licence — so the UI can be honest
 * about what it installs.
 */
@Serializable
data class ComponentManifest(
    val formatVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    /** `lwjgl`, `renderer` or `bridge`. */
    val kind: String,
    /** e.g. `arm64-v8a`. A pack is only usable on the ABI it was built for. */
    val abi: String,
    val license: String,
    /** Where the binaries were built from, shown in the licences screen. */
    val source: String,
    /** Capabilities, e.g. `["lwjgl:3.3.3", "lib:libgl4es.so"]`. */
    val provides: List<String> = emptyList(),
    /** Jars the component adds to the game's classpath, relative to the pack root. */
    val classpathJars: List<String> = emptyList(),
    val libraryDir: String = "lib",
    val files: List<ComponentFile>
) {
    val lwjglVersion: String? get() = provides.firstOrNull { it.startsWith("lwjgl:") }?.removePrefix("lwjgl:")
    val providers: Map<String, String> get() = provides.associateBy { it.substringBefore(':') }
}

data class InstalledComponent(val manifest: ComponentManifest, val dir: Path) {
    val libDir: Path get() = dir.resolve(manifest.libraryDir)

    fun libraries(): Map<String, Path> {
        val d = libDir
        if (!Files.isDirectory(d)) return emptyMap()
        return Files.list(d).use { s ->
            s.filter { it.fileName.toString().endsWith(".so") }
                .toList()
                .associateBy { it.fileName.toString() }
        }
    }

    val lwjglVersion: String? get() = manifest.lwjglVersion

    fun jars(): List<Path> = manifest.classpathJars.map { dir.resolve(it) }

    /** The `.so` paths as strings, ready for the linker search path. */
    fun libraryPaths(): List<String> = libraries().values.map { it.toAbsolutePath().toString() }

    fun describe(): String = "${manifest.name} ${manifest.version} (${manifest.abi})"
}

/**
 * Registry of installed component packs under `<root>/components/<id>`.
 *
 * Import is **fail-closed**: a pack is staged, every declared file is hashed, every file present must
 * be declared, and only then is it moved into place. Native libraries are exactly the kind of file
 * that must never be half-installed, and an undeclared file is a hidden payload.
 */
class ComponentManager(private val dirs: LauncherDirs) {

    companion object {
        const val MANIFEST = "cs-component.json"
        const val FORMAT = 1
        private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }

    fun list(): List<InstalledComponent> {
        val root = dirs.componentRoot
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { s ->
            s.toList()
                .filter { Files.isRegularFile(it.resolve(MANIFEST)) }
                .mapNotNull { dir ->
                    runCatching {
                        val m = CsJson.decodeFromString(ComponentManifest.serializer(), FsUtil.readText(dir.resolve(MANIFEST)))
                        InstalledComponent(m, dir)
                    }.onFailure { CsLog.w("Component", "Ignoring unreadable component in $dir: ${it.message}") }
                        .getOrNull()
                }
        }.sortedBy { it.manifest.id }
    }

    fun byId(id: String): InstalledComponent? = list().firstOrNull { it.manifest.id == id }

    /** All native libraries available from installed packs for [abi], by file name. */
    fun availableLibraries(abi: String): Map<String, Path> =
        list().filter { it.manifest.abi == abi }.flatMap { it.libraries().entries }.associate { it.key to it.value }

    /**
     * The LWJGL pack to run a game built against [gameLwjgl] (e.g. `3.3.3`):
     * the oldest installed build that is at least as new in major.minor, so a 3.3 game does not get
     * pulled onto 3.4 behaviour needlessly. Without a suitable pack the newest is returned and
     * [LwjglCompat.problem] explains the mismatch.
     */
    fun lwjgl(abi: String, gameLwjgl: String? = null): InstalledComponent? {
        val all = list()
            .filter { it.manifest.abi == abi && it.lwjglVersion != null }
            .sortedBy { LwjglCompat.order(it.lwjglVersion!!) }
        if (all.isEmpty()) return null
        if (gameLwjgl == null) return all.first()
        val need = LwjglCompat.majorMinor(gameLwjgl)
        return all.firstOrNull { LwjglCompat.majorMinor(it.lwjglVersion!!) >= need } ?: all.last()
    }

    /** All renderer packs for [abi], newest first. */
    fun rendererPacks(abi: String): List<InstalledComponent> =
        list().filter { it.manifest.abi == abi && it.manifest.kind == "renderer" }
            .sortedByDescending { LwjglCompat.order(it.manifest.version) }

    /** Empty when the pack is intact; otherwise one human-readable problem per bad file. */
    fun verify(c: InstalledComponent): List<String> = c.manifest.files.mapNotNull { f ->
        val p = runCatching { SafeZip.safeTarget(c.dir, f.path) }.getOrNull()
        if (p == null) {
            "${f.path}: path escapes the component directory"
        } else {
            Integrity(sha256 = f.sha256, size = f.size).check(p)?.let { "${f.path}: $it" }
        }
    }

    /** Imports a pack. Throws (and leaves nothing behind) when anything does not verify. */
    fun import(pack: Path, expectedAbi: String): InstalledComponent {
        Files.createDirectories(dirs.componentRoot)
        val staging = dirs.componentRoot.resolve(".staging-${System.nanoTime()}")
        try {
            SafeZip.extract(pack, staging, maxTotalBytes = 1L shl 30)
            val mf = staging.resolve(MANIFEST)
            require(Files.isRegularFile(mf)) { "Not a CS Launcher Pro component pack ($MANIFEST is missing)" }
            val m = CsJson.decodeFromString(ComponentManifest.serializer(), FsUtil.readText(mf))
            require(m.formatVersion == FORMAT) { "Unsupported component format ${m.formatVersion}" }
            require(ID_PATTERN.matches(m.id)) { "Invalid component id '${m.id}'" }
            require(m.abi == expectedAbi) { "${m.name} is built for ${m.abi}, this device needs $expectedAbi" }
            require(m.files.isNotEmpty()) { "${m.name} declares no files" }

            val c = InstalledComponent(m, staging)
            val problems = verify(c)
            require(problems.isEmpty()) { "Component verification failed: ${problems.take(3).joinToString()}" }

            // Nothing undeclared may exist: an extra file is either a packaging bug or a hidden payload.
            val declared = m.files.map { it.path.replace('\\', '/') }.toSet() + MANIFEST
            Files.walk(staging).use { s ->
                s.filter { Files.isRegularFile(it) }.toList().forEach {
                    val rel = staging.relativize(it).toString().replace('\\', '/')
                    require(rel in declared) { "Undeclared file in component: $rel" }
                }
            }

            val target = dirs.componentRoot.resolve(m.id)
            if (Files.exists(target)) FsUtil.deleteTreeWithin(dirs.componentRoot, target)
            Files.move(staging, target)
            CsLog.i("Component", "Installed ${m.name} ${m.version} (${m.abi}, ${m.files.size} files)")
            return InstalledComponent(m, target)
        } finally {
            if (Files.exists(staging)) runCatching { FsUtil.deleteTreeWithin(dirs.componentRoot, staging) }
        }
    }

    fun remove(id: String): Boolean {
        val target = dirs.componentRoot.resolve(id)
        if (!Files.isDirectory(target)) return false
        // Refuse to follow a manifest id that points outside the component root.
        return runCatching { FsUtil.deleteTreeWithin(dirs.componentRoot, target) }.isSuccess
    }

    /** True when every installed pack still matches its manifest. Used by the "verify" action. */
    fun health(): List<String> = list().flatMap { c -> verify(c).map { "${c.manifest.id}: $it" } }

    fun totalBytes(): Long = list().sumOf { FsUtil.sizeOf(it.dir) }
}

/**
 * Whether the bundled Android LWJGL build can stand in for the LWJGL the game was built against.
 *
 * This is the check that turns "the game crashed in GLFW" into "this version needs LWJGL 3.4 with
 * SDL3, which is not available for this CPU" — an actionable message instead of a stack trace.
 */
object LwjglCompat {

    /** First LWJGL release whose Android build includes the SDL3 bridge. */
    const val SDL_BRIDGE_MIN = "3.4"

    fun majorMinor(v: String): Int = v.split('.', '-').take(2)
        .map { it.toIntOrNull() ?: 0 }
        .let { (it.getOrElse(0) { 0 } shl 16) + it.getOrElse(1) { 0 } }

    fun order(v: String): Long = v.split('.', '-').take(3)
        .map { it.toLongOrNull() ?: 0L }
        .let { (it.getOrElse(0) { 0L } shl 32) + (it.getOrElse(1) { 0L } shl 16) + it.getOrElse(2) { 0L } }

    /** The game's LWJGL version from its library list (null for LWJGL 2 / unknown). */
    fun gameVersion(gameLibraries: List<MavenCoordinate>): String? =
        gameLibraries.firstOrNull { it.group == "org.lwjgl" && it.artifact == "lwjgl" }?.version

    /** Minecraft 26.3+ creates its window with SDL3 instead of GLFW. */
    fun usesSdl(gameLibraries: List<MavenCoordinate>): Boolean =
        gameLibraries.any { it.group == "org.lwjgl" && it.artifact == "lwjgl-sdl" }

    fun problem(gameLibraries: List<MavenCoordinate>, componentVersion: String?): String? {
        val lw = gameLibraries.filter { it.group == "org.lwjgl" }
        if (lw.isEmpty() || componentVersion == null) return null
        if (usesSdl(lw) && majorMinor(componentVersion) < majorMinor(SDL_BRIDGE_MIN)) {
            val game = lw.firstOrNull { it.artifact == "lwjgl-sdl" }?.version ?: "3.4"
            return "This Minecraft version uses LWJGL $game with SDL3 windowing. The LWJGL 3.4 Android " +
                "build is not available for this device's CPU (bundled: LWJGL $componentVersion); " +
                "use Minecraft 1.21.11 or older."
        }
        val game = gameVersion(lw) ?: return null
        return if (majorMinor(game) > majorMinor(componentVersion)) {
            "This Minecraft version needs LWJGL $game; the bundled Android LWJGL for this device's CPU is $componentVersion."
        } else null
    }

    /** Short label for the home screen, e.g. "LWJGL 3.3.3 · GL4ES". */
    fun describe(lwjgl: InstalledComponent?, renderer: InstalledComponent?): String {
        val parts = mutableListOf<String>()
        lwjgl?.lwjglVersion?.let { parts += "LWJGL $it" }
        renderer?.manifest?.name?.let { parts += it }
        return if (parts.isEmpty()) "No native components installed" else parts.joinToString(" · ")
    }
}
