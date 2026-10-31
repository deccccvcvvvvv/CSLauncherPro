package pro.cslauncher.core.content

import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import pro.cslauncher.core.util.parseJsonObject
import pro.cslauncher.core.util.int
import pro.cslauncher.core.util.obj
import pro.cslauncher.core.util.str
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

data class PackInfo(
    val path: Path,
    val name: String,
    val description: String?,
    val packFormat: Int?,
    val hasIcon: Boolean,
    val valid: Boolean,
    val problem: String?,
    val sizeBytes: Long
) {
    val enabled: Boolean get() = !path.fileName.toString().endsWith(".disabled")

    /** `pack.mcmeta` pack_format for the version the pack was built for, used for the "outdated" hint. */
    fun formatLabel(): String = packFormat?.let { "format $it" } ?: "no pack format"
}

/**
 * Resource and shader packs (folders or zips) in a game directory.
 *
 * `pack.mcmeta` is the whole contract, so it is read the way the game reads it — including the older
 * `pack_format` at the root, which is what a 1.6-era pack has.
 */
class PackManager(private val gameDir: Path) {

    enum class Kind(val folder: String) { RESOURCE("resourcepacks"), SHADER("shaderpacks") }

    fun dir(kind: Kind): Path = gameDir.resolve(kind.folder)

    fun list(kind: Kind): List<PackInfo> {
        val dir = dir(kind)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.toList()
                .filter { it.fileName.toString().let { n -> n.endsWith(".zip") || n.endsWith(".zip.disabled") || Files.isDirectory(it) } }
                .map { inspect(kind, it) }
        }.sortedBy { it.name.lowercase() }
    }

    fun inspect(kind: Kind, path: Path): PackInfo {
        val size = FsUtil.sizeOf(path)
        val name = path.fileName.toString().removeSuffix(".disabled")
        return try {
            val (metaText, hasIcon) = if (Files.isDirectory(path)) {
                val meta = path.resolve("pack.mcmeta")
                (if (Files.isRegularFile(meta)) FsUtil.readText(meta) else null) to Files.isRegularFile(path.resolve("pack.png"))
            } else {
                ZipFile(path.toFile()).use { zip ->
                    val meta = zip.getEntry("pack.mcmeta")?.let { e ->
                        zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
                    }
                    val icon = zip.entries().asSequence().any { it.name == "pack.png" }
                    meta to icon
                }
            }
            if (metaText == null) {
                return PackInfo(path, name, null, null, hasIcon, false, "pack.mcmeta is missing", size)
            }
            val root = parseJsonObject(metaText, "pack.mcmeta")
            val pack = root.obj("pack")
            val format = pack?.int("pack_format")
            val description = pack?.str("description") ?: root.obj("pack")?.get("description")?.toString()?.trim('"')
            PackInfo(path, name, description, format, hasIcon, true, null, size)
        } catch (e: Exception) {
            PackInfo(path, name, null, null, false, false, "Unreadable: ${e.message}", size)
        }
    }

    fun import(kind: Kind, source: Path): PackInfo {
        val raw = source.fileName.toString()
        require(raw.endsWith(".zip") || Files.isDirectory(source)) { "Packs must be .zip files or folders" }
        val target = dir(kind).resolve(FsUtil.safeFileName(raw))
        Files.createDirectories(dir(kind))
        if (Files.exists(target)) throw IllegalArgumentException("'$raw' is already installed")
        if (Files.isDirectory(source)) {
            Files.walk(source).use { walk ->
                walk.forEach { src ->
                    val dst = target.resolve(source.relativize(src).toString())
                    if (Files.isDirectory(src)) Files.createDirectories(dst)
                    else Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } else {
            Files.copy(source, target)
        }
        val info = inspect(kind, target)
        if (!info.valid) {
            // A pack without metadata is not a pack; leaving it in the folder just confuses the game.
            FsUtil.deleteTreeWithin(dir(kind), target)
            throw IllegalArgumentException("'$raw' is not a valid pack: ${info.problem}")
        }
        CsLog.i("Packs", "Installed ${kind.folder} pack $raw")
        return info
    }

    fun setEnabled(pack: PackInfo, enabled: Boolean): Path {
        val name = pack.path.fileName.toString()
        val isDisabled = name.endsWith(".disabled")
        if (!enabled == isDisabled) return pack.path
        val target = if (!enabled) {
            pack.path.resolveSibling(name + ".disabled")
        } else {
            pack.path.resolveSibling(name.removeSuffix(".disabled"))
        }
        require(!Files.exists(target)) { "Cannot rename: '${target.fileName}' already exists" }
        Files.move(pack.path, target)
        return target
    }

    fun remove(pack: PackInfo, trash: Path): Path {
        Files.createDirectories(trash)
        val target = trash.resolve("${System.currentTimeMillis()}-${FsUtil.safeFileName(pack.path.fileName.toString())}")
        Files.move(pack.path, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    /** The pack format Minecraft expects for a version id, for the "outdated pack" hint. */
    fun expectedFormat(versionId: String): Int? = when {
        versionId.startsWith("1.6") -> 1
        versionId.startsWith("1.7") || versionId.startsWith("1.8") -> 2
        versionId.startsWith("1.9") || versionId.startsWith("1.10") -> 3
        versionId.startsWith("1.11") || versionId.startsWith("1.12") -> 4
        versionId.startsWith("1.13") || versionId.startsWith("1.14") -> 5
        versionId.startsWith("1.15") -> 6
        versionId.startsWith("1.16.1") || versionId.startsWith("1.16.0") -> 6
        versionId.startsWith("1.16") -> 7
        versionId.startsWith("1.17") -> 8
        versionId.startsWith("1.18") -> 9
        versionId.startsWith("1.19") -> 9
        versionId.startsWith("1.20") -> 15
        versionId.startsWith("1.21") -> 34
        else -> null
    }

    fun iconBytes(pack: PackInfo, maxBytes: Int = 1 shl 20): ByteArray? = runCatching {
        if (Files.isDirectory(pack.path)) {
            val icon = pack.path.resolve("pack.png")
            if (Files.isRegularFile(icon) && Files.size(icon) <= maxBytes) Files.readAllBytes(icon) else null
        } else {
            ZipFile(pack.path.toFile()).use { zip ->
                val entry = zip.getEntry("pack.png") ?: return null
                if (entry.size > maxBytes) return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }
    }.getOrNull()
}
