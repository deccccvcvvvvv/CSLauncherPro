package pro.cslauncher.core.content

import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class OptiFineException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Patches a vanilla jar with a GDIFF (Google diff) patch — the format OptiFine's installer uses for
 * the class files it modifies.
 *
 * Every offset and length from the patch is bounds-checked against the base jar: a malformed or
 * hostile patch must not be able to read outside the array it is patching. The output is hashed
 * against the `.md5` file the installer ships, so a patch that "succeeded" but produced the wrong
 * bytes is caught instead of producing an unlaunchable version.
 */
object GDiff {

    private val MAGIC = intArrayOf(0xD1, 0xFF, 0xD1, 0xFF)

    fun apply(base: ByteArray, patch: ByteArray): ByteArray {
        val input = DataInputStream(patch.inputStream())
        for (m in MAGIC) {
            if (input.readUnsignedByte() != m) throw OptiFineException("Not a GDIFF patch (bad magic)")
        }
        val version = input.readUnsignedByte()
        if (version != 4) throw OptiFineException("Unsupported GDIFF version $version")

        val out = ByteArrayOutputStream(base.size + patch.size)

        fun data(n: Int) {
            if (n < 0) throw OptiFineException("Negative GDIFF data length")
            val buf = ByteArray(n)
            input.readFully(buf)
            out.write(buf)
        }

        fun copy(offset: Long, length: Int) {
            if (offset < 0 || length < 0 || offset + length > base.size) {
                throw OptiFineException("GDIFF copy out of range ($offset+$length > ${base.size})")
            }
            out.write(base, offset.toInt(), length)
        }

        try {
            while (input.available() > 0) {
                when (val op = input.readUnsignedByte()) {
                    0 -> {}
                    in 1..246 -> data(op)
                    247 -> data(input.readUnsignedShort())
                    248 -> data(input.readInt())
                    249 -> copy(input.readUnsignedShort().toLong(), input.readUnsignedByte())
                    250 -> copy(input.readUnsignedShort().toLong(), input.readUnsignedShort())
                    251 -> copy(input.readUnsignedShort().toLong(), input.readInt())
                    252 -> copy(input.readInt().toLong() and 0xFFFFFFFFL, input.readUnsignedByte())
                    253 -> copy(input.readInt().toLong() and 0xFFFFFFFFL, input.readUnsignedShort())
                    254 -> copy(input.readInt().toLong() and 0xFFFFFFFFL, input.readInt())
                    255 -> copy(input.readLong(), input.readInt())
                }
            }
        } catch (e: EOFException) {
            throw OptiFineException("Truncated GDIFF patch", e)
        }
        return out.toByteArray()
    }
}

data class OptiFineJarInfo(
    val fullVersion: String,
    val minecraftVersion: String,
    val edition: String,
    val launchwrapperVersion: String?,
    val patchCount: Int
) {
    val versionId: String get() = "$minecraftVersion-OptiFine_$edition"
    val libraryName: String get() = "optifine:OptiFine:${minecraftVersion}_$edition"
    val libraryPath: String get() = "optifine/OptiFine/${minecraftVersion}_$edition/OptiFine-${minecraftVersion}_$edition.jar"
    val isPreview: Boolean get() = edition.contains("pre", ignoreCase = true)
}

data class OptiFineInstallResult(
    val versionId: String,
    val info: OptiFineJarInfo,
    val patched: Int,
    val copied: Int
)

/**
 * Installs OptiFine from its official installer jar.
 *
 * OptiFine is not a normal mod: it *patches* the vanilla jar, class by class, through GDIFF deltas.
 * That makes three things mandatory and all three are enforced here:
 * * the base jar must be present and intact (otherwise the patch silently produces a broken jar);
 * * every patched class is verified against the MD5 the installer ships;
 * * the result is written to a temporary file and moved into place only when the whole patch run
 *   succeeded, so an interrupted install never leaves a half-patched jar behind.
 */
class OptiFineInstaller(private val dirs: LauncherDirs) {

    private companion object {
        val CONFIG_CLASSES = listOf(
            "net/optifine/Config.class",
            "optifine/Config.class",
            "Config.class"
        )
        const val PREFIX_PATCH = "patch/"
        const val SUFFIX_OLD = ".old"
        const val SUFFIX_DELTA = ".xdelta"
        const val SUFFIX_MD5 = ".md5"
        val LW_VERSION = Regex("^[0-9]+\\.[0-9]+(\\.[0-9]+)?(_[A-Za-z0-9]+)?$")
    }

    fun inspect(jar: Path): OptiFineJarInfo = ZipFile(jar.toFile()).use { inspect(it) }

    private fun inspect(zip: ZipFile): OptiFineJarInfo {
        val configEntry = CONFIG_CLASSES.firstNotNullOfOrNull { zip.getEntry(it) }
            ?: throw OptiFineException("This is not an OptiFine installer jar (no OptiFine Config class found)")
        val full = zip.getInputStream(configEntry).use { versionFromBytes(it.readBytes()) }
            ?: throw OptiFineException("Could not read the OptiFine version from the jar")
        val parts = full.split('_')
        if (parts.size < 3) throw OptiFineException("Unexpected OptiFine version string '$full'")
        val minecraft = parts[1]
        val edition = parts.drop(2).joinToString("_")
        LauncherDirs.checkId("$minecraft-OptiFine_$edition")
        val launchwrapper = zip.getEntry("launchwrapper-of.txt")?.let { entry ->
            val v = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.US_ASCII).trim() }
            if (!LW_VERSION.matches(v)) throw OptiFineException("Invalid launchwrapper version '$v' in the OptiFine jar")
            v
        }
        val patches = zip.entries().asSequence()
            .count { it.name.startsWith(PREFIX_PATCH) && it.name.endsWith(SUFFIX_DELTA) }
        if (patches == 0) throw OptiFineException("The OptiFine jar contains no patches; is it a mod-only (extracted) jar?")
        return OptiFineJarInfo(full, minecraft, edition, launchwrapper, patches)
    }

    /** The version string is in `Config.class` as a constant pool UTF-8 entry: "OptiFine_1.20.1_HD_U_I5". */
    private fun versionFromBytes(bytes: ByteArray): String? {
        val text = String(bytes, Charsets.ISO_8859_1)
        val match = Regex("OptiFine_[0-9][A-Za-z0-9._-]*").find(text) ?: return null
        return match.value
    }

    fun patch(
        vanillaJar: Path,
        optiFineJar: Path,
        dest: Path,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Pair<Int, Int> {
        require(Files.isRegularFile(vanillaJar)) { "The vanilla client jar is missing; repair the base version first" }
        Files.createDirectories(dest.parent)
        val tmp = dest.resolveSibling(dest.fileName.toString() + ".part")
        var patched = 0
        var copied = 0
        try {
            ZipFile(optiFineJar.toFile()).use { diff ->
                ZipFile(vanillaJar.toFile()).use { base ->
                    val config = readConfig(diff)
                    val patterns = config.keys.map { Regex(it) }
                    val total = diff.size()
                    ZipOutputStream(Files.newOutputStream(tmp)).use { out ->
                        var i = 0
                        for (entry in diff.entries()) {
                            i++
                            if (i % 200 == 0) onProgress(i, total)
                            val name = entry.name
                            if (entry.isDirectory) continue
                            if (name.startsWith(PREFIX_PATCH) && name.endsWith(SUFFIX_MD5)) continue

                            val bytes = diff.getInputStream(entry).use { it.readBytes() }

                            if (name.startsWith(PREFIX_PATCH) && name.endsWith(SUFFIX_DELTA)) {
                                val target = name.removePrefix(PREFIX_PATCH).removeSuffix(SUFFIX_DELTA)
                                val baseName = patchBase(target, patterns, config)
                                    ?: throw OptiFineException("No patch base for $target")
                                val baseEntry = base.getEntry(baseName)
                                    ?: throw OptiFineException(
                                        "The Minecraft jar is missing $baseName. Repair the vanilla version and try again."
                                    )
                                val baseBytes = base.getInputStream(baseEntry).use { it.readBytes() }
                                val result = GDiff.apply(baseBytes, bytes)
                                diff.getEntry(PREFIX_PATCH + target + SUFFIX_MD5)?.let { md5Entry ->
                                    val expected = diff.getInputStream(md5Entry).use { String(it.readBytes(), Charsets.US_ASCII) }.trim()
                                    val actual = md5Hex(result)
                                    if (!actual.equals(expected, ignoreCase = true)) {
                                        throw OptiFineException("Patched $target does not match the expected checksum")
                                    }
                                }
                                out.putNextEntry(java.util.zip.ZipEntry(target))
                                out.write(result)
                                out.closeEntry()
                                patched++
                            } else if (!name.startsWith(PREFIX_PATCH) && name != "META-INF/MANIFEST.MF") {
                                out.putNextEntry(java.util.zip.ZipEntry(name))
                                out.write(bytes)
                                out.closeEntry()
                                copied++
                            }
                        }
                    }
                }
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
            CsLog.i("OptiFine", "Patched $patched classes into ${dest.fileName}")
            return patched to copied
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /**
     * Reads `patch/…/config.txt`-style mappings (a regex of source paths to the jar entry it came
     * from). Missing config means the patch target is used as-is, which is what older installers do.
     */
    private fun readConfig(zip: ZipFile): Map<String, String> {
        val entry = zip.entries().asSequence()
            .firstOrNull { it.name.startsWith(PREFIX_PATCH) && it.name.endsWith("config.txt") }
            ?: return emptyMap()
        return zip.getInputStream(entry).use { stream ->
            stream.bufferedReader().readLines()
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                    val parts = trimmed.split(Regex("\\s+"), limit = 2)
                    if (parts.size < 2) null else parts[1].trim('"') to parts[0].trim('"')
                }
                .toMap()
        }
    }

    private fun patchBase(target: String, patterns: List<Regex>, config: Map<String, String>): String? {
        // Exact mapping first.
        config[target]?.let { return it }
        // Then a pattern match: "a/b/C" -> "a/b/C.class" or "a/b/C$Inner.class".
        for (pattern in patterns) {
            if (pattern.containsMatchIn(target)) {
                config.entries.firstOrNull { it.key == pattern.pattern }?.let { return it.value }
            }
        }
        return when {
            target.endsWith(".class") -> target
            else -> "$target.class"
        }
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Full install: patch the client jar and write the version JSON that points at the result.
     * The produced version id is `<mc>-OptiFine_<edition>`, which is what every other launcher uses.
     */
    fun install(
        optiFineJar: Path,
        minecraftVersion: String,
        baseJsonProvider: (String) -> String?,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): OptiFineInstallResult {
        val info = inspect(optiFineJar)
        if (info.minecraftVersion != minecraftVersion) {
            throw OptiFineException(
                "This OptiFine build is for Minecraft ${info.minecraftVersion}, not $minecraftVersion"
            )
        }
        val vanillaJar = dirs.versionJar(minecraftVersion)
        if (!Files.isRegularFile(vanillaJar)) {
            throw OptiFineException("Minecraft $minecraftVersion is not installed; install it first")
        }
        val targetJar = dirs.versionJar(info.versionId)
        val (patched, copied) = patch(vanillaJar, optiFineJar, targetJar, onProgress)

        val baseJson = baseJsonProvider(minecraftVersion)
            ?: throw OptiFineException("The metadata for Minecraft $minecraftVersion is unavailable")

        val optifineLibrary = InfoJson.libraryEntry(info)
        val versionJson = InfoJson.buildVersionJson(
            versionId = info.versionId,
            inheritsFrom = minecraftVersion,
            baseJson = baseJson,
            extraLibrary = optifineLibrary
        )
        FsUtil.writeAtomic(dirs.versionJson(info.versionId), versionJson)

        // The OptiFine jar is also a library, so loaders that expect it on the classpath find it.
        val libPath = dirs.libraries.resolve(info.libraryPath)
        Files.createDirectories(libPath.parent)
        Files.copy(optiFineJar, libPath, StandardCopyOption.REPLACE_EXISTING)

        CsLog.i("OptiFine", "Installed ${info.versionId} ($patched patched, $copied copied)")
        return OptiFineInstallResult(info.versionId, info, patched, copied)
    }
}

/** Builds the small JSON fragments OptiFine's version file needs. */
internal object InfoJson {

    fun libraryEntry(info: OptiFineJarInfo): String = """
        {
          "name": "${info.libraryName}",
          "downloads": {
            "artifact": {
              "path": "${info.libraryPath}",
              "url": ""
            }
          }
        }
    """.trimIndent()

    /**
     * A child version that inherits everything from vanilla and only adds OptiFine. Inheriting rather
     * than copying the base keeps the two in sync when the base version is repaired.
     */
    fun buildVersionJson(versionId: String, inheritsFrom: String, baseJson: String, extraLibrary: String): String {
        val mainClass = Regex("\"mainClass\"\\s*:\\s*\"([^\"]+)\"").find(baseJson)?.groupValues?.get(1)
            ?: "net.minecraft.client.main.Main"
        val type = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(baseJson)?.groupValues?.get(1) ?: "release"
        return """
            {
              "id": "$versionId",
              "inheritsFrom": "$inheritsFrom",
              "type": "$type",
              "mainClass": "$mainClass",
              "libraries": [
                $extraLibrary
              ]
            }
        """.trimIndent()
    }
}
