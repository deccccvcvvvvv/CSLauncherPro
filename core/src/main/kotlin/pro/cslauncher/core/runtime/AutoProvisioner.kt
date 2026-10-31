package pro.cslauncher.core.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pro.cslauncher.core.component.ComponentManager
import pro.cslauncher.core.component.LwjglCompat
import pro.cslauncher.core.download.DownloadManager
import pro.cslauncher.core.download.DownloadTask
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.launch.Provisioner
import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.Hashing
import pro.cslauncher.core.util.Integrity
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A runtime shipped inside the APK (`assets/bundled/<abi>/index.json`). */
@Serializable
data class BundledRuntime(
    val id: String,
    val major: Int,
    val version: String,
    val file: String,
    val sha256: String,
    val size: Long,
    val source: String = ""
)

/** A native component pack shipped inside the APK. */
@Serializable
data class BundledComponent(
    val id: String,
    val version: String,
    val file: String,
    val sha256: String,
    val size: Long
)

@Serializable
data class BundleIndex(
    val formatVersion: Int = 1,
    val abi: String,
    val runtimes: List<BundledRuntime> = emptyList(),
    val components: List<BundledComponent> = emptyList()
)

/** Where bundled payloads come from: the APK's assets, or nothing at all on desktop. */
interface BundleSource {
    /** The index for [abi], or null when nothing is bundled for it. */
    fun index(abi: String): BundleIndex?
    fun open(abi: String, file: String): InputStream
}

class ProvisionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Installs Java runtimes and native component packs automatically, in this order of preference:
 *
 * 1. **from the APK** — instant and works offline, which matters because the first launch on a phone
 *    often happens without Wi-Fi;
 * 2. **downloaded** — an Android OpenJDK build for this ABI, verified against the SHA-256 digest that
 *    the release publishes. Without a published checksum nothing is installed: an unverified `libjvm`
 *    is arbitrary native code, and this launcher does not run that.
 *
 * Nothing is activated before its checksum matches, and every step is idempotent, so pressing Play
 * twice or resuming an interrupted first launch cannot produce two runtimes.
 */
class AutoProvisioner(
    private val dirs: LauncherDirs,
    private val platform: PlatformInfo,
    private val runtimes: RuntimeManager,
    private val components: ComponentManager,
    private val bundle: BundleSource?,
    private val http: HttpTransport?,
    private val downloads: DownloadManager?
) : Provisioner {

    companion object {
        /** Majors published by the Android OpenJDK project. */
        val DOWNLOADABLE_MAJORS = listOf(8, 17, 21, 25)
        private const val RELEASE_API =
            "https://api.github.com/repos/AngelAuraMC/angelauramc-openjdk-build/releases/tags/download_jre"

        fun parseIndex(text: String): BundleIndex = CsJson.decodeFromString(BundleIndex.serializer(), text)
    }

    private val abi: String
        get() = platform.arch.androidAbi ?: throw ProvisionException("Not an Android platform")

    private val lock = Any()

    /** Smallest supplied major that satisfies [required]. Java 8 stays 8: old Forge/LWJGL setups need it. */
    fun targetMajor(required: Int, offered: List<Int>): Int? = offered.sorted().firstOrNull { it >= required }

    override fun ensureRuntime(major: Int, progress: (String) -> Unit) {
        synchronized(lock) {
            val installed = runtimes.list().filter { it.isAndroidBuild && it.libjvm() != null }
            val idx = bundle?.index(abi)
            val bundled = idx?.runtimes.orEmpty()
            val target = targetMajor(major, (bundled.map { it.major } + DOWNLOADABLE_MAJORS).distinct())
                ?: throw ProvisionException("No Java $major build exists for this device")
            if (installed.any { it.major == target }) return

            Files.createDirectories(dirs.runtimeRoot)
            val staging = dirs.runtimeRoot.resolve(".incoming")
            Files.createDirectories(staging)
            val archive = staging.resolve("jre$target.tar.xz")
            try {
                val fromApk = bundled.filter { it.major == target }.maxByOrNull { it.version }
                if (fromApk != null) {
                    progress("Preparing Java $target (first launch only)…")
                    val src = bundle
                    if (src == null) throw ProvisionException("Bundled runtime source disappeared")
                    src.open(abi, fromApk.file).use { Files.copy(it, archive, StandardCopyOption.REPLACE_EXISTING) }
                    progress("Unpacking Java $target…")
                    runtimes.install(archive, "jre$target", fromApk.sha256, "bundled Java $target", androidBuild = true)
                } else {
                    val (url, sha) = resolveDownload(target)
                    progress("Downloading Java $target…")
                    val dm = downloads ?: throw ProvisionException("Downloads are unavailable")
                    val result = runBlocking {
                        dm.downloadOne(DownloadTask(url, archive, Integrity(sha256 = sha), "Java $target runtime"))
                    }
                    if (!result.ok) throw ProvisionException("Java $target download failed: ${result.error}")
                    progress("Unpacking Java $target…")
                    runtimes.install(archive, "jre$target", sha, "downloaded Java $target", androidBuild = true)
                }
                CsLog.i("Provision", "Java $target ready for $abi (${if (fromApk != null) "bundled" else "downloaded"})")
            } finally {
                Files.deleteIfExists(archive)
            }
        }
    }

    /** Asset name used by the Android OpenJDK releases for this device. */
    fun releaseArch(): String = when (abi) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> throw ProvisionException("Unsupported ABI $abi")
    }

    private fun resolveDownload(major: Int): Pair<String, String> {
        val h = http ?: throw ProvisionException("No network transport available")
        val name = "jre$major-android-${releaseArch()}.tar.xz"
        val json = CsJson.parseToJsonElement(
            h.getText("$RELEASE_API$major", mapOf("Accept" to "application/vnd.github+json"))
        ).jsonObject
        val assets = json["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val asset = assets.firstOrNull { it["name"]?.jsonPrimitive?.content == name }
            ?: throw ProvisionException("No Java $major build is published for ${releaseArch()}")
        val digest = asset["digest"]?.jsonPrimitive?.content?.removePrefix("sha256:")
            ?: throw ProvisionException("The Java $major build has no published checksum; refusing to install it")
        val url = asset["browser_download_url"]?.jsonPrimitive?.content
            ?: throw ProvisionException("The Java $major build has no download URL")
        return url to digest
    }

    override fun ensureComponents(progress: (String) -> Unit) {
        synchronized(lock) {
            val src = bundle ?: return
            val idx = src.index(abi) ?: return
            val installed = components.list().associateBy { it.manifest.id }
            for (c in idx.components) {
                val have = installed[c.id]
                if (have != null && have.manifest.version == c.version && components.verify(have).isEmpty()) continue
                progress("Installing ${c.id} ${c.version}…")
                Files.createDirectories(dirs.componentRoot)
                val staging = dirs.componentRoot.resolve(".incoming")
                Files.createDirectories(staging)
                val file = staging.resolve(c.id + ".zip")
                try {
                    src.open(abi, c.file).use { Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING) }
                    val actual = Hashing.sha256(file)
                    if (!actual.equals(c.sha256, ignoreCase = true)) {
                        throw ProvisionException("Bundled ${c.id} is corrupt (checksum mismatch)")
                    }
                    components.import(file, abi)
                    CsLog.i("Provision", "Installed bundled component ${c.id} ${c.version}")
                } finally {
                    Files.deleteIfExists(file)
                }
            }
        }
    }

    /** What the APK provides for this device, for the settings screen. */
    fun describeBundle(): String {
        val idx = runCatching { bundle?.index(abi) }.getOrNull()
            ?: return "Nothing is bundled for $abi; Java is downloaded automatically when needed"
        val parts = idx.runtimes.map { "Java ${it.major}" } + idx.components.map { it.id }
        return if (parts.isEmpty()) "The bundle for $abi is empty" else "Bundled for $abi: ${parts.joinToString()}"
    }

    /**
     * The single question the launch path needs answered before it can offer the SDL3-era versions:
     * what LWJGL the installed packs can run.
     */
    fun lwjglCapability(gameLibraries: List<pro.cslauncher.core.meta.MavenCoordinate>): String? =
        runCatching { components.lwjgl(abi, LwjglCompat.gameVersion(gameLibraries)) }.getOrNull()?.lwjglVersion
}
