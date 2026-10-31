package pro.cslauncher.core.content

import pro.cslauncher.core.profile.LoaderType
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import pro.cslauncher.core.util.parseJsonObject
import pro.cslauncher.core.util.arr
import pro.cslauncher.core.util.obj
import pro.cslauncher.core.util.str
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

data class ModDependency(val modId: String, val versionRange: String, val required: Boolean)

data class ModInfo(
    val file: Path,
    val enabled: Boolean,
    val loaders: Set<LoaderType>,
    val modId: String?,
    val name: String,
    val version: String?,
    val description: String?,
    val minecraftRange: String?,
    val javaRange: String?,
    val dependencies: List<ModDependency>,
    val breaks: List<String>,
    val hasNativeCode: Boolean,
    val sizeBytes: Long,
    val metadataError: String? = null,
    val iconPath: String? = null
) {
    val displayName: String get() = name.ifBlank { file.fileName.toString() }

    fun describe(): String = buildList {
        add(version?.let { "v$it" } ?: "unknown version")
        if (loaders.isNotEmpty()) add(loaders.joinToString("/") { it.displayName })
        if (hasNativeCode) add("native code")
    }.joinToString(" · ")
}

enum class Certainty { CERTAIN, LIKELY, UNKNOWN }

data class CompatFinding(
    val file: Path,
    val severity: String,
    val message: String,
    val certainty: Certainty
)

/**
 * Reads mod metadata without loading any mod code.
 *
 * Three formats matter in the wild, and all three are supported because a mod that shows as "unknown
 * file" is a mod the user cannot reason about:
 * * `fabric.mod.json` (Fabric, Quilt);
 * * `quilt.mod.json` (Quilt);
 * * `META-INF/mods.toml` and `MANIFEST.MF` (Forge / NeoForge).
 *
 * A malformed file never throws: it becomes a [ModInfo.metadataError] so the list still renders and
 * the user learns which file is broken.
 */
object ModInspector {

    fun inspect(file: Path): ModInfo {
        val size = runCatching { Files.size(file) }.getOrDefault(0L)
        val base = ModInfo(
            file = file,
            enabled = !file.fileName.toString().endsWith(".disabled"),
            loaders = emptySet(),
            modId = null,
            name = file.fileName.toString().removeSuffix(".disabled").removeSuffix(".jar"),
            version = null,
            description = null,
            minecraftRange = null,
            javaRange = null,
            dependencies = emptyList(),
            breaks = emptyList(),
            hasNativeCode = false,
            sizeBytes = size
        )
        if (!file.fileName.toString().removeSuffix(".disabled").endsWith(".jar")) return base

        return runCatching {
            ZipFile(file.toFile()).use { zip ->
                val hasNative = zip.entries().asSequence()
                    .any { it.name.endsWith(".so") || it.name.endsWith(".dll") || it.name.endsWith(".dylib") }
                when {
                    zip.getEntry("fabric.mod.json") != null -> {
                        val text = zip.getInputStream(zip.getEntry("fabric.mod.json")).use { String(it.readBytes(), Charsets.UTF_8) }
                        fromFabricJson(base, text, hasNative, setOf(LoaderType.FABRIC))
                    }
                    zip.getEntry("quilt.mod.json") != null -> {
                        val text = zip.getInputStream(zip.getEntry("quilt.mod.json")).use { String(it.readBytes(), Charsets.UTF_8) }
                        fromQuiltJson(base, text, hasNative)
                    }
                    zip.getEntry("META-INF/neoforge.mods.toml") != null || zip.getEntry("META-INF/mods.toml") != null -> {
                        val entry = zip.getEntry("META-INF/neoforge.mods.toml") ?: zip.getEntry("META-INF/mods.toml")
                        val text = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
                        val loader = if (entry.name.contains("neoforge")) LoaderType.NEOFORGE else LoaderType.FORGE
                        fromToml(base, text, hasNative, setOf(loader))
                    }
                    else -> base.copy(
                        hasNativeCode = hasNative,
                        metadataError = "No recognisable mod metadata (not a Fabric, Quilt, Forge or NeoForge mod)"
                    )
                }
            }
        }.getOrElse { e ->
            base.copy(metadataError = "Unreadable jar: ${e.message}")
        }
    }

    private fun fromFabricJson(base: ModInfo, text: String, hasNative: Boolean, loaders: Set<LoaderType>): ModInfo {
        val root = parseJsonObject(text, "fabric.mod.json")
        val deps = mutableListOf<ModDependency>()
        root.obj("depends")?.forEach { (id, v) ->
            if (id == "minecraft" || id == "java" || id == "fabricloader") return@forEach
            deps += ModDependency(id, versionText(v), required = true)
        }
        root.obj("recommends")?.keys?.forEach { deps += ModDependency(it, "*", required = false) }
        val breaks = root.obj("breaks")?.keys?.toList().orEmpty()
        val contact = root.obj("contact")
        return base.copy(
            loaders = loaders,
            modId = root.str("id"),
            name = root.str("name") ?: base.name,
            version = root.str("version"),
            description = root.str("description") ?: contact?.str("homepage"),
            minecraftRange = root.obj("depends")?.get("minecraft")?.let { versionText(it) },
            javaRange = root.obj("depends")?.get("java")?.let { versionText(it) },
            dependencies = deps,
            breaks = breaks,
            hasNativeCode = hasNative,
            iconPath = root.str("icon")
        )
    }

    /** Quilt keeps Fabric's schema under `quilt_loader`. */
    private fun fromQuiltJson(base: ModInfo, text: String, hasNative: Boolean): ModInfo {
        val root = parseJsonObject(text, "quilt.mod.json")
        val loader = root.obj("quilt_loader")
            ?: return base.copy(metadataError = "quilt.mod.json has no quilt_loader section")
        val metadata = loader.obj("metadata")
        val deps = mutableListOf<ModDependency>()
        (loader["depends"] as? kotlinx.serialization.json.JsonArray)?.forEach { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
            val id = o.str("id") ?: return@forEach
            if (id == "minecraft" || id == "java" || id == "quilt_loader") return@forEach
            deps += ModDependency(id, o.str("versions") ?: "*", required = true)
        }
        return base.copy(
            loaders = setOf(LoaderType.QUILT),
            modId = loader.str("id"),
            name = metadata?.str("name") ?: base.name,
            version = loader.str("version"),
            description = metadata?.str("description"),
            dependencies = deps,
            hasNativeCode = hasNative,
            iconPath = metadata?.str("icon")
        )
    }

    /**
     * A deliberately small TOML reader: it extracts `modId`, `version`, `displayName`, `description`
     * and the `[[dependencies.<modid>]]` blocks. Pulling in a full TOML parser for four fields would be
     * a dependency this core does not need.
     */
    private fun fromToml(base: ModInfo, text: String, hasNative: Boolean, loaders: Set<LoaderType>): ModInfo {
        val values = LinkedHashMap<String, String>()
        val deps = mutableListOf<ModDependency>()
        var inDependencyBlock = false
        var currentDependency = LinkedHashMap<String, String>()

        fun flushDependency() {
            if (currentDependency.isEmpty()) return
            val id = currentDependency["modId"] ?: return
            if (id != "minecraft" && id != "forge" && id != "neoforge") {
                deps += ModDependency(
                    id,
                    currentDependency["versionRange"] ?: "*",
                    required = currentDependency["mandatory"]?.equals("true", true) ?: true
                )
            }
            currentDependency = LinkedHashMap()
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[[dependencies")) {
                flushDependency()
                inDependencyBlock = true
                continue
            }
            if (line.startsWith("[")) {
                flushDependency()
                inDependencyBlock = false
                continue
            }
            val key = line.substringBefore('=', "").trim()
            val value = line.substringAfter('=', "").trim().trim('"')
            if (key.isEmpty()) continue
            if (inDependencyBlock) currentDependency[key] = value else values.putIfAbsent(key, value)
        }
        flushDependency()

        val mcRange = run {
            // Re-read the minecraft dependency range, which was skipped above.
            Regex("""modId\s*=\s*"minecraft"[\s\S]{0,200}?versionRange\s*=\s*"([^"]*)"""")
                .find(text)?.groupValues?.get(1)
        }

        return base.copy(
            loaders = loaders,
            modId = values["modId"],
            name = values["displayName"] ?: base.name,
            version = values["version"],
            description = values["description"],
            minecraftRange = mcRange,
            dependencies = deps,
            hasNativeCode = hasNative
        )
    }

    private fun versionText(el: kotlinx.serialization.json.JsonElement): String = when (el) {
        is kotlinx.serialization.json.JsonPrimitive -> el.content
        is kotlinx.serialization.json.JsonArray -> el.joinToString(" || ") { versionText(it) }
        is kotlinx.serialization.json.JsonObject -> el.str("version") ?: "*"
        else -> "*"
    }

    /** The mod's icon bytes, bounded so a huge image cannot be loaded into a list row. */
    fun readIcon(file: Path, iconPath: String?, maxBytes: Int = 1 shl 20): ByteArray? = runCatching {
        ZipFile(file.toFile()).use { zip ->
            val entry = zip.getEntry(iconPath ?: "icon.png") ?: return null
            if (entry.size > maxBytes) return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }.getOrNull()
}

/**
 * The `mods` folder.
 *
 * Enabling/disabling is done by renaming to `.disabled` (what every launcher does, and what the game
 * ignores), never by moving files out — a user who disables a mod expects to find it again in the
 * same list.
 */
class ModManager(private val gameDir: Path) {

    val dir: Path get() = gameDir.resolve("mods")

    fun list(): List<ModInfo> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.toList()
                .filter { it.fileName.toString().let { n -> n.endsWith(".jar") || n.endsWith(".jar.disabled") } }
                .map { ModInspector.inspect(it) }
        }.sortedBy { it.displayName.lowercase() }
    }

    fun import(source: Path, overwrite: Boolean = false): ModInfo {
        val name = source.fileName.toString()
        require(name.endsWith(".jar") || name.endsWith(".jar.disabled")) { "Only .jar files can be added as mods" }
        Files.createDirectories(dir)
        val target = dir.resolve(FsUtil.safeFileName(name))
        if (Files.exists(target) && !overwrite) {
            throw IllegalArgumentException("A file called '$name' is already in this profile's mods folder")
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        val info = ModInspector.inspect(target)
        // An archive with no metadata is almost always not a mod: keep the folder clean and say why.
        if (info.metadataError != null && info.modId == null) {
            Files.deleteIfExists(target)
            throw IllegalArgumentException("'$name' is not a mod: ${info.metadataError}")
        }
        return info
    }

    fun setEnabled(mod: ModInfo, enabled: Boolean): Path {
        val name = mod.file.fileName.toString()
        val wantsDisabled = !enabled
        val isDisabled = name.endsWith(".disabled")
        if (wantsDisabled == isDisabled) return mod.file
        val target = if (wantsDisabled) {
            mod.file.resolveSibling(name + ".disabled")
        } else {
            mod.file.resolveSibling(name.removeSuffix(".disabled"))
        }
        require(!Files.exists(target)) { "Cannot rename: '${target.fileName}' already exists" }
        Files.move(mod.file, target)
        return target
    }

    /** Moves a mod to the trash instead of deleting it, so a mistaken removal is recoverable. */
    fun remove(mod: ModInfo, trash: Path): Path {
        Files.createDirectories(trash)
        val target = trash.resolve("${System.currentTimeMillis()}-${FsUtil.safeFileName(mod.file.fileName.toString())}")
        Files.move(mod.file, target, StandardCopyOption.REPLACE_EXISTING)
        CsLog.i("Mods", "Moved ${mod.file.fileName} to the trash")
        return target
    }

    /**
     * Flags mods that will not work on this profile. Everything here is advisory: versions ranges are
     * parsed conservatively, and a finding is only [Certainty.CERTAIN] when the metadata says so
     * outright.
     */
    fun checkCompatibility(mcVersion: String, loader: LoaderType?, javaMajor: Int): List<CompatFinding> {
        val out = mutableListOf<CompatFinding>()
        val mods = list()
        for (mod in mods) {
            if (mod.metadataError != null) {
                out += CompatFinding(mod.file, "error", "Cannot read metadata: ${mod.metadataError}", Certainty.CERTAIN)
                continue
            }
            if (loader != null && mod.loaders.isNotEmpty() && loader !in mod.loaders) {
                out += CompatFinding(
                    mod.file, "error",
                    "${mod.displayName} is built for ${mod.loaders.joinToString("/") { it.displayName }} and will not load with ${loader.displayName}",
                    Certainty.CERTAIN
                )
            }
            val range = mod.minecraftRange
            if (range != null && !range.isBlank() && !matchesVersion(range, mcVersion)) {
                out += CompatFinding(
                    mod.file, "warning",
                    "${mod.displayName} declares Minecraft $range but this profile is $mcVersion",
                    Certainty.LIKELY
                )
            }
            val javaRange = mod.javaRange
            if (javaRange != null && !javaRange.isBlank() && !matchesVersion(javaRange, javaMajor.toString())) {
                out += CompatFinding(
                    mod.file, "warning",
                    "${mod.displayName} requires Java $javaRange but the profile uses Java $javaMajor",
                    Certainty.LIKELY
                )
            }
            if (mod.hasNativeCode) {
                out += CompatFinding(
                    mod.file, "info",
                    "${mod.displayName} contains native code, which may not support this device's CPU",
                    Certainty.UNKNOWN
                )
            }
        }
        // Missing dependencies: only reported when the mod is not installed and the dependency is required.
        val installedIds = mods.mapNotNull { it.modId }.toSet()
        val installedNames = mods.map { it.file.fileName.toString().lowercase() }.toSet()
        for (mod in mods) {
            for (dep in mod.dependencies.filter { it.required }) {
                if (dep.modId in installedIds) continue
                val present = installedNames.any { it.startsWith(dep.modId.lowercase().replace('_', '-')) }
                if (!present) {
                    out += CompatFinding(
                        mod.file, "error",
                        "${mod.displayName} requires '${dep.modId}' (${dep.versionRange}), which is not installed",
                        Certainty.CERTAIN
                    )
                }
            }
        }
        return out
    }

    /**
     * Evaluates the common Maven/Fabric version-range syntax: `1.20.x`, `>=1.20 <1.21`, `~1.20`,
     * `1.20 || 1.21`, `*`. Deliberately lenient — a false warning is worse than a missing one.
     */
    fun matchesVersion(range: String, version: String): Boolean = runCatching {
        val r = range.trim()
        if (r.isEmpty() || r == "*" || r.equals("any", true)) return true
        r.split("||").any { alternative ->
            alternative.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.all { token -> matchesToken(token, version) }
        }
    }.getOrDefault(true)

    private fun matchesToken(token: String, version: String): Boolean {
        val t = token.trim()
        return when {
            t.startsWith(">=") -> compare(version, t.removePrefix(">=")) >= 0
            t.startsWith("<=") -> compare(version, t.removePrefix("<=")) <= 0
            t.startsWith(">") -> compare(version, t.removePrefix(">")) > 0
            t.startsWith("<") -> compare(version, t.removePrefix("<")) < 0
            t.startsWith("~") || t.startsWith("^") -> {
                val base = t.drop(1).trim()
                val parts = base.split('.').take(2)
                compare(version, base) >= 0 && version.split('.').take(parts.size) == parts
            }
            t.contains('x') || t.contains('X') || t.contains('*') -> {
                val wanted = t.lowercase().split('.')
                val actual = version.lowercase().split('.')
                wanted.indices.all { i ->
                    val w = wanted[i]
                    (w == "x" || w == "*") || (i < actual.size && actual[i] == w)
                }
            }
            t.contains(',') -> t.split(',').all { matchesToken(it, version) }
            else -> compare(version, t) == 0 || version.startsWith(t)
        }
    }

    private fun compare(a: String, b: String): Int {
        val pa = a.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun count(): Int = list().count { it.enabled }
}
