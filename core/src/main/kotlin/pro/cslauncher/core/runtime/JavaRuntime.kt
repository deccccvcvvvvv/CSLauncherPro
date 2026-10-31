package pro.cslauncher.core.runtime

import kotlinx.serialization.Serializable
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.install.SafeTar
import pro.cslauncher.core.platform.PlatformInfo.Arch
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import pro.cslauncher.core.util.Hashing
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class RuntimeSource { BUNDLED, IMPORTED, DOWNLOADED, HOST }

enum class Compatibility { EXACT, COMPATIBLE, RISKY, INCOMPATIBLE }

/**
 * One installed Java runtime.
 *
 * On Android a runtime is only ever used in-process (its `libjvm.so` is loaded through JNI); the
 * `bin/java` executable is never launched, because Android refuses to exec app-data binaries. On
 * desktop the executable is what gets started.
 */
data class JavaRuntime(
    val id: String,
    val home: Path,
    val major: Int,
    val version: String,
    val arch: Arch,
    val vendor: String?,
    val source: RuntimeSource,
    val isAndroidBuild: Boolean
) {
    val javaExecutable: Path
        get() = home.resolve("bin").resolve(if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java")

    /**
     * Locates `libjvm.so`. Java 8 ships it under `lib/<arch>/server`, Java 9+ under `lib/server`, and a
     * few vendors use `client`. All of them are checked so an imported runtime is not rejected for
     * being laid out differently.
     */
    fun libjvm(): Path? {
        val archDirs = when (arch) {
            Arch.ARM64 -> listOf("aarch64", "arm64")
            Arch.ARM32 -> listOf("aarch32", "arm")
            Arch.X86 -> listOf("i386", "x86")
            Arch.X86_64 -> listOf("amd64", "x86_64")
        }
        val candidates = buildList {
            add("lib/server")
            add("lib/client")
            add("jre/lib/server")
            archDirs.forEach {
                add("lib/$it/server")
                add("jre/lib/$it/server")
                add("lib/$it/client")
                add("jre/lib/$it/client")
            }
        }
        return candidates.map { home.resolve(it).resolve("libjvm.so") }.firstOrNull { Files.isRegularFile(it) }
    }

    val is64Bit: Boolean get() = arch.bits == 64

    /** `java.home` as the JVM expects it: the directory containing `lib` (and `bin`). */
    val javaHome: Path get() = libjvm()?.let { it.parent.parent.parent } ?: home

    fun describe(): String = "Java $major ($version, ${arch.mojangName}${if (isAndroidBuild) ", Android" else ""})"

    fun validate(): List<String> {
        val out = mutableListOf<String>()
        if (!Files.isDirectory(home)) out += "runtime directory is missing"
        if (libjvm() == null && !Files.isRegularFile(javaExecutable)) out += "no libjvm.so and no java executable"
        if (version.isBlank()) out += "version is unknown"
        return out
    }
}

object RuntimeProbe {

    /** Reads the standard JDK `release` file. Returns null when [home] is not a Java runtime. */
    fun probe(home: Path, source: RuntimeSource, isAndroidBuild: Boolean): JavaRuntime? {
        val release = home.resolve("release")
        if (!Files.isRegularFile(release)) return null
        val props = runCatching {
            Files.readAllLines(release).mapNotNull { l ->
                val i = l.indexOf('=')
                if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim().trim('"')
            }.toMap()
        }.getOrNull() ?: return null
        val ver = props["JAVA_VERSION"] ?: return null
        val major = parseMajor(ver) ?: return null
        val arch = props["OS_ARCH"]?.let { Arch.fromJvm(it) } ?: Arch.fromJvm(System.getProperty("os.arch").orEmpty())
        return JavaRuntime(
            id = home.fileName?.toString() ?: "runtime",
            home = home,
            major = major,
            version = ver,
            arch = arch,
            vendor = props["IMPLEMENTOR"] ?: props["JAVA_VENDOR"],
            source = source,
            isAndroidBuild = isAndroidBuild
        )
    }

    fun parseMajor(v: String): Int? {
        val s = v.trim()
        if (s.startsWith("1.")) return s.substring(2).takeWhile { it.isDigit() }.toIntOrNull()
        return s.takeWhile { it.isDigit() }.toIntOrNull()
    }
}

data class RuntimeChoice(
    val runtime: JavaRuntime?,
    val compatibility: Compatibility,
    val reason: String,
    val requiredMajor: Int
) {
    val usable: Boolean get() = runtime != null && compatibility != Compatibility.INCOMPATIBLE
}

/**
 * Chooses a runtime for a version.
 *
 * Policy, in order of importance:
 *  1. never select something that cannot start the game (wrong ABI, non-bionic build on Android, too
 *     old a JVM) — that produces a crash report the user cannot act on;
 *  2. prefer an exact major match;
 *  3. a *newer* JVM is acceptable for modern versions but is flagged risky where mods commonly break;
 *  4. Java 8 + LWJGL 2 must stay Java 8, because launchwrapper and old Forge fail on newer JVMs.
 */
object RuntimeSelector {

    fun evaluate(required: Int, usesLwjgl3: Boolean, rt: JavaRuntime, platform: PlatformInfo): Pair<Compatibility, String> {
        if (rt.arch != platform.arch) {
            val ok32on64 = platform.arch == Arch.ARM64 && rt.arch == Arch.ARM32
            if (!ok32on64) {
                return Compatibility.INCOMPATIBLE to "Runtime is ${rt.arch.mojangName}, device is ${platform.arch.mojangName}"
            }
        }
        if (platform.isAndroid && !rt.isAndroidBuild) {
            return Compatibility.INCOMPATIBLE to "Not an Android (bionic) build of Java"
        }
        if (platform.isAndroid && rt.libjvm() == null) {
            return Compatibility.INCOMPATIBLE to "Runtime has no libjvm.so"
        }
        if (rt.major < required) {
            return Compatibility.INCOMPATIBLE to "Requires Java $required, runtime is Java ${rt.major}"
        }
        if (rt.major == required) return Compatibility.EXACT to "Java ${rt.major} matches the version requirement"
        return when {
            required <= 8 && !usesLwjgl3 -> Compatibility.INCOMPATIBLE to "LWJGL 2 era versions need Java 8"
            required <= 8 -> Compatibility.RISKY to "Requires Java 8; Java ${rt.major} usually works for vanilla but some mods fail"
            required == 16 && rt.major == 17 -> Compatibility.COMPATIBLE to "Java 17 is compatible with Java 16 requirements"
            rt.major - required <= 4 -> Compatibility.COMPATIBLE to "Newer Java ${rt.major} is normally compatible"
            else -> Compatibility.RISKY to "Java ${rt.major} is much newer than required Java $required"
        }
    }

    fun select(
        required: Int,
        usesLwjgl3: Boolean,
        runtimes: List<JavaRuntime>,
        platform: PlatformInfo,
        preferredId: String? = null
    ): RuntimeChoice {
        val evaluated = runtimes
            .filter { it.validate().isEmpty() }
            .map { it to evaluate(required, usesLwjgl3, it, platform) }
            .filter { it.second.first != Compatibility.INCOMPATIBLE }

        if (evaluated.isEmpty()) {
            return RuntimeChoice(null, Compatibility.INCOMPATIBLE, "No Java $required runtime for ${platform.arch.mojangName}. Install one to play.", required)
        }

        if (preferredId != null) {
            evaluated.firstOrNull { it.first.id == preferredId || it.first.major.toString() == preferredId }?.let {
                return RuntimeChoice(it.first, it.second.first, "Selected by the profile: ${it.second.second}", required)
            }
            // A stale preferred id must not silently fall back to a risky runtime; note it in the reason.
            CsLog.w("Runtime", "Preferred runtime '$preferredId' is not installed; choosing automatically")
        }

        val rank = mapOf(
            Compatibility.EXACT to 0,
            Compatibility.COMPATIBLE to 1,
            Compatibility.RISKY to 2
        )
        val best = evaluated.sortedWith(
            compareBy(
                { rank.getValue(it.second.first) },
                { it.first.major },          // prefer the closest major
                { -it.first.version.length }, // then a stable, fully specified version
                { it.first.source == RuntimeSource.HOST }
            )
        ).first()

        val reason = if (preferredId != null) {
            "Preferred runtime not available; using ${best.first.id}: ${best.second.second}"
        } else best.second.second
        return RuntimeChoice(best.first, best.second.first, reason, required)
    }

    /** The lowest Java major a version needs, with the well-known exceptions in Mojang's metadata. */
    fun requiredMajorFor(metaMajor: Int): Int = if (metaMajor <= 0) 8 else metaMajor
}

/** Persisted record of a runtime the launcher installed itself (imported ones are simply re-probed). */
@Serializable
data class RuntimeRecord(
    val id: String,
    val directory: String,
    val major: Int,
    val version: String,
    val sha256: String? = null,
    val source: String = RuntimeSource.DOWNLOADED.name,
    val installedAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class RuntimeIndexFile(val schemaVersion: Int = 1, val runtimes: List<RuntimeRecord> = emptyList())

/**
 * Installs, lists and removes Java runtimes under `<root>/runtimes/<id>`.
 *
 * Install is transactional: the archive is verified, unpacked into a temporary directory, probed, and
 * only then moved into its final name. A failed install therefore never leaves a half-unpacked
 * runtime that `libjvm()` would happily find and crash on.
 */
class RuntimeManager(private val dirs: LauncherDirs, private val platform: PlatformInfo) {

    companion object {
        const val INDEX_FILE = "runtimes.json"
    }

    private val lock = Any()
    private val indexFile get() = dirs.runtimeRoot.resolve(INDEX_FILE)

    fun readIndex(): RuntimeIndexFile = runCatching {
        if (!Files.isRegularFile(indexFile)) RuntimeIndexFile()
        else CsJson.decodeFromString(RuntimeIndexFile.serializer(), FsUtil.readText(indexFile))
    }.getOrElse {
        CsLog.w("Runtime", "Runtime index unreadable, rebuilding from disk: ${it.message}")
        RuntimeIndexFile()
    }

    private fun writeIndex(idx: RuntimeIndexFile) =
        FsUtil.writeAtomic(indexFile, CsJson.encodeToString(RuntimeIndexFile.serializer(), idx))

    /**
     * Every runtime the device can use: installed Android/desktop builds plus, on desktop, the JVM the
     * launcher itself runs on (always present, never removable).
     */
    fun list(): List<JavaRuntime> = synchronized(lock) {
        val out = mutableListOf<JavaRuntime>()
        if (Files.isDirectory(dirs.runtimeRoot)) {
            val idx = readIndex().runtimes.associateBy { it.directory }
            Files.list(dirs.runtimeRoot).use { stream ->
                stream.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
                    .forEach { dir ->
                        val record = idx[dir.fileName.toString()]
                        val isAndroid = record != null || platform.isAndroid
                        RuntimeProbe.probe(dir, record?.let { RuntimeSource.valueOf(it.source) } ?: RuntimeSource.IMPORTED, isAndroid)
                            ?.let { out += it.copy(id = record?.id ?: it.id) }
                    }
            }
        }
        // Recorded runtimes whose directory vanished must not be advertised.
        val stale = readIndex().runtimes.filterNot { Files.isDirectory(dirs.runtimeRoot.resolve(it.directory)) }
        if (stale.isNotEmpty()) {
            writeIndex(readIndex().let { it.copy(runtimes = it.runtimes - stale.toSet()) })
        }
        if (!platform.isAndroid) hostRuntime()?.let { out += it }
        out.sortedBy { it.major }
    }

    private fun hostRuntime(): JavaRuntime? {
        val home = System.getProperty("java.home") ?: return null
        val path = Path.of(home)
        return RuntimeProbe.probe(path, RuntimeSource.HOST, isAndroidBuild = false)
            ?.copy(id = "host-java${RuntimeProbe.probe(path, RuntimeSource.HOST, false)?.major ?: 0}")
    }

    fun byId(id: String): JavaRuntime? = list().firstOrNull { it.id == id }

    fun default(): JavaRuntime? =
        RuntimeSelector.select(if (platform.isAndroid) 17 else 8, true, list(), platform).runtime

    /**
     * Installs a runtime from a `.tar.xz` / `.tar.gz` / plain `.tar` archive.
     *
     * [expectedSha256] is mandatory in spirit: it is what makes the download trustworthy, so a
     * mismatch aborts before anything is unpacked.
     */
    fun install(
        archive: Path,
        directoryName: String,
        expectedSha256: String?,
        label: String,
        androidBuild: Boolean = platform.isAndroid
    ): JavaRuntime = synchronized(lock) {
        require(Files.isRegularFile(archive)) { "Runtime archive $archive is missing" }
        if (expectedSha256 != null) {
            val actual = Hashing.sha256(archive)
            require(actual.equals(expectedSha256, ignoreCase = true)) {
                "Runtime archive checksum mismatch (expected $expectedSha256, got $actual)"
            }
        }
        Files.createDirectories(dirs.runtimeRoot)
        val staging = dirs.runtimeRoot.resolve(".staging-" + UUID.randomUUID())
        try {
            Files.createDirectories(staging)
            SafeTar.extract(archive, staging, stripComponents = 0, maxTotalBytes = 6L shl 30)

            // Archives usually contain one top-level directory; accept both layouts.
            val entries = Files.list(staging).use { it.toList() }
            val probeRoot = when {
                entries.size == 1 && Files.isDirectory(entries[0]) -> entries[0]
                else -> staging
            }
            val probed = RuntimeProbe.probe(probeRoot, RuntimeSource.DOWNLOADED, androidBuild)
                ?: findNested(probeRoot, androidBuild)
                ?: error("The archive does not contain a usable Java runtime (no release file)")

            val target = dirs.runtimeRoot.resolve(LauncherDirs.checkId(directoryName))
            if (Files.exists(target)) {
                // Replace only after the new copy is proven good.
                val old = dirs.runtimeRoot.resolve(".old-" + UUID.randomUUID())
                Files.move(target, old, StandardCopyOption.ATOMIC_MOVE)
                Files.move(probeRoot, target, StandardCopyOption.ATOMIC_MOVE)
                FsUtil.deleteTreeWithin(dirs.runtimeRoot, old)
            } else {
                Files.move(probeRoot, target, StandardCopyOption.ATOMIC_MOVE)
            }

            val finalRuntime = RuntimeProbe.probe(target, RuntimeSource.DOWNLOADED, androidBuild)
                ?: error("Runtime did not survive installation")
            val record = RuntimeRecord(
                id = directoryName,
                directory = target.fileName.toString(),
                major = finalRuntime.major,
                version = finalRuntime.version,
                sha256 = expectedSha256,
                source = RuntimeSource.DOWNLOADED.name
            )
            val idx = readIndex()
            writeIndex(idx.copy(runtimes = idx.runtimes.filterNot { it.directory == record.directory } + record))
            CsLog.i("Runtime", "Installed $label as ${finalRuntime.describe()} in $target")
            finalRuntime.copy(id = directoryName, source = RuntimeSource.DOWNLOADED)
        } finally {
            runCatching { if (Files.exists(staging)) FsUtil.deleteTreeWithin(dirs.runtimeRoot, staging) }
        }
    }

    private fun findNested(root: Path, androidBuild: Boolean): JavaRuntime? {
        val candidates = Files.walk(root, 3).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString() == "release" }
                .map { it.parent }
                .toList()
        }
        return candidates.firstNotNullOfOrNull { RuntimeProbe.probe(it, RuntimeSource.DOWNLOADED, androidBuild) }
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val rt = byId(id) ?: return@synchronized false
        if (rt.source == RuntimeSource.HOST) return@synchronized false
        val ok = runCatching { FsUtil.deleteTreeWithin(dirs.runtimeRoot, rt.home) }.isSuccess
        val idx = readIndex()
        writeIndex(idx.copy(runtimes = idx.runtimes.filterNot { it.id == id || it.directory == rt.home.fileName.toString() }))
        ok
    }

    /** Re-probes the index; used by the settings screen's "verify" button. */
    fun verify(): List<String> {
        val problems = mutableListOf<String>()
        for (rt in list()) {
            rt.validate().forEach { problems += "${rt.id}: $it" }
        }
        return problems
    }

    /** Total size on disk, for the storage screen. */
    fun totalBytes(): Long = list().filter { it.source != RuntimeSource.HOST }.sumOf { FsUtil.sizeOf(it.home) }
}
