package pro.cslauncher.core.content

import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.meta.VersionMetadata
import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.profile.LoaderType
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.FsUtil
import pro.cslauncher.core.util.bool
import pro.cslauncher.core.util.obj
import pro.cslauncher.core.util.str
import java.net.URLEncoder

data class LoaderVersion(val version: String, val stable: Boolean)

data class LoaderSupport(val supported: Boolean, val reason: String)

/**
 * Installs Fabric and Quilt, which publish a complete, ready-made version profile.
 *
 * Forge and NeoForge are **deliberately not pretended to work**: their installers download and run
 * Java "processor" steps that patch the client jar, which is a whole subsystem (and one that cannot
 * run under a normal Android app sandbox). Reporting that honestly is better than a version that
 * installs and then fails at launch with an unrelated error.
 */
class LoaderInstaller(private val http: HttpTransport, private val dirs: LauncherDirs) {

    private fun metaBase(type: LoaderType): String? = when (type) {
        LoaderType.FABRIC -> "https://meta.fabricmc.net/v2"
        LoaderType.QUILT -> "https://meta.quiltmc.org/v3"
        else -> null
    }

    fun support(type: LoaderType): LoaderSupport = when (type) {
        LoaderType.VANILLA -> LoaderSupport(true, "No loader")
        LoaderType.FABRIC, LoaderType.QUILT -> LoaderSupport(
            true, "Installed from the official ${type.name.lowercase()} metadata service"
        )
        LoaderType.FORGE, LoaderType.NEOFORGE -> LoaderSupport(
            false,
            "${type.name.lowercase().replaceFirstChar { it.uppercase() }} needs its own installer processors to patch " +
                "the game, which this launcher does not run. Use Fabric or Quilt for mods."
        )
    }

    fun supportedTypes(): List<LoaderType> = LoaderType.entries.filter { support(it).supported && it != LoaderType.VANILLA }

    /** Loader builds for a Minecraft version, newest first, stable builds first. */
    fun versions(type: LoaderType, minecraftVersion: String): List<LoaderVersion> {
        val base = metaBase(type) ?: return emptyList()
        val url = "$base/versions/loader/${URLEncoder.encode(minecraftVersion, "UTF-8")}"
        val array = CsJson.parseToJsonElement(http.getText(url)) as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val loader = o.obj("loader") ?: return@mapNotNull null
            val version = loader.str("version") ?: return@mapNotNull null
            LoaderVersion(
                version = version,
                stable = loader.bool("stable") ?: !version.contains("beta", ignoreCase = true)
            )
        }.sortedWith { a, b ->
            when {
                a.stable != b.stable -> if (a.stable) -1 else 1
                else -> compareLoaderNumbers(b.version, a.version)
            }
        }
    }

    fun latest(type: LoaderType, minecraftVersion: String, allowUnstable: Boolean = false): LoaderVersion? =
        versions(type, minecraftVersion).firstOrNull { allowUnstable || it.stable }

    /**
     * Writes the loader's version JSON next to the game's own. The profile is validated before it is
     * written: a loader profile that inherits from the wrong version, or that lists a non-HTTPS
     * library, is refused rather than stored.
     */
    fun install(type: LoaderType, minecraftVersion: String, loaderVersion: String): String {
        val base = metaBase(type) ?: throw UnsupportedOperationException(support(type).reason)
        val url = "$base/versions/loader/" +
            "${URLEncoder.encode(minecraftVersion, "UTF-8")}/" +
            "${URLEncoder.encode(loaderVersion, "UTF-8")}/profile/json"
        val text = http.getText(url)
        val parsed = VersionMetadata.parse(text)

        require(parsed.inheritsFrom == minecraftVersion) {
            "The loader profile inherits from ${parsed.inheritsFrom}, expected $minecraftVersion"
        }
        val insecure = parsed.libraries.filter { it.artifact?.url?.isNotBlank() == true && it.artifact?.url?.startsWith("https://") != true }
        require(insecure.isEmpty()) {
            "The loader profile contains non-HTTPS libraries: ${insecure.take(3).joinToString { it.name }}"
        }
        LauncherDirs.checkId(parsed.id)
        FsUtil.writeAtomic(dirs.versionJson(parsed.id), text)
        return parsed.id
    }

    /** True when the loader build is already installed and still readable. */
    fun isInstalled(loaderVersionId: String): Boolean {
        val file = dirs.versionJson(loaderVersionId)
        if (!java.nio.file.Files.isRegularFile(file)) return false
        return runCatching { VersionMetadata.parse(FsUtil.readText(file)) }.isSuccess
    }

    /**
     * Compares loader versions numerically (`0.15.11` > `0.15.9`). A plain string compare would say
     * the opposite, which is the classic "update available" bug that never goes away.
     */
    fun compareLoaderNumbers(a: String, b: String): Int {
        fun parts(v: String) = v.split('.', '-', '+').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        // Same numbers, but a pre-release sorts below the release.
        val aPre = a.contains("beta", true) || a.contains("pre", true) || a.contains("rc", true)
        val bPre = b.contains("beta", true) || b.contains("pre", true) || b.contains("rc", true)
        return when {
            aPre == bPre -> 0
            aPre -> -1
            else -> 1
        }
    }
}
