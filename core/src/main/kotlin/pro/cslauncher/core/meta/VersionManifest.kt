package pro.cslauncher.core.meta

import kotlinx.serialization.json.JsonObject
import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import pro.cslauncher.core.util.MetadataException
import pro.cslauncher.core.util.arr
import pro.cslauncher.core.util.bool
import pro.cslauncher.core.util.int
import pro.cslauncher.core.util.obj
import pro.cslauncher.core.util.parseJsonObject
import pro.cslauncher.core.util.str
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** Mojang's version categories. Anything unexpected (april fools, snapshots of old builds) is MODDED. */
enum class VersionType(val id: String) {
    RELEASE("release"),
    SNAPSHOT("snapshot"),
    OLD_BETA("old_beta"),
    OLD_ALPHA("old_alpha"),
    MODDED("modded");

    companion object {
        fun of(s: String?): VersionType = entries.firstOrNull { it.id == s } ?: MODDED
    }
}

data class ManifestEntry(
    val id: String,
    val type: VersionType,
    val url: String,
    val sha1: String?,
    val releaseTime: String,
    val complianceLevel: Int?
) {
    /** Release date `yyyy-MM-dd` for sorting and display. */
    val releaseDate: String get() = releaseTime.substringBefore('T')
}

data class VersionManifest(
    val latestRelease: String?,
    val latestSnapshot: String?,
    val versions: List<ManifestEntry>,
    val fetchedAt: Instant,
    val fromCache: Boolean
) {
    fun find(id: String): ManifestEntry? = versions.firstOrNull { it.id == id }

    fun filter(types: Set<VersionType>, query: String = ""): List<ManifestEntry> {
        val q = query.trim()
        return versions.filter { entry ->
            entry.type in types && (q.isEmpty() || entry.id.contains(q, ignoreCase = true))
        }
    }

    fun releases(): List<ManifestEntry> = versions.filter { it.type == VersionType.RELEASE }

    companion object {
        const val URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

        fun parse(text: String, fetchedAt: Instant, fromCache: Boolean): VersionManifest {
            val o = parseJsonObject(text, "Version manifest")
            val list = o.arr("versions") ?: throw MetadataException("Version manifest has no 'versions' array")
            val entries = list.mapNotNull { el ->
                val v = el as? JsonObject ?: return@mapNotNull null
                val id = v.str("id") ?: return@mapNotNull null
                val url = v.str("url") ?: return@mapNotNull null
                ManifestEntry(
                    id = id,
                    type = VersionType.of(v.str("type")),
                    url = url,
                    sha1 = v.str("sha1"),
                    releaseTime = v.str("releaseTime") ?: v.str("time") ?: "",
                    complianceLevel = v.int("complianceLevel")
                )
            }
            if (entries.isEmpty()) throw MetadataException("Version manifest lists no versions")
            val latest = o.obj("latest")
            return VersionManifest(
                latestRelease = latest?.str("release"),
                latestSnapshot = latest?.str("snapshot"),
                versions = entries,
                fetchedAt = fetchedAt,
                fromCache = fromCache
            )
        }
    }
}

/**
 * Manifest with an on-disk cache.
 *
 * The launcher must keep working with no network (a user opens it on a train), so a cached copy is
 * served when it is younger than [maxAge], and **any** cached copy is served when the network
 * fails. A failure to refresh never blocks the Installations screen.
 */
class VersionManifestRepository(
    private val http: HttpTransport,
    private val cacheFile: Path,
    private val maxAge: Duration = Duration.ofHours(6)
) {

    @Volatile
    private var memo: VersionManifest? = null

    fun load(forceRefresh: Boolean = false): VersionManifest {
        val now = Instant.now()
        memo?.let { cached ->
            if (!forceRefresh && Duration.between(cached.fetchedAt, now) < maxAge) return cached
        }

        val onDisk = readCache()
        if (!forceRefresh && onDisk != null && Duration.between(onDisk.fetchedAt, now) < maxAge) {
            memo = onDisk
            return onDisk
        }

        return try {
            val text = http.getText(VersionManifest.URL)
            val fresh = VersionManifest.parse(text, now, false)
            writeCache(text)
            memo = fresh
            CsLog.i("Manifest", "Fetched ${fresh.versions.size} versions (latest release ${fresh.latestRelease})")
            fresh
        } catch (e: Exception) {
            val fallback = onDisk
            if (fallback != null) {
                CsLog.w("Manifest", "Network failed, using cached manifest from ${fallback.fetchedAt}: ${e.message}")
                memo = fallback
                return fallback
            }
            CsLog.e("Manifest", "Cannot load the version manifest and no cache exists", e)
            throw MetadataException(
                "Could not load the Minecraft version list. Check the connection and try again. (${e.message})",
                e
            )
        }
    }

    /** The cached copy, or null when nothing usable is on disk. */
    private fun readCache(): VersionManifest? = runCatching {
        val text = FsUtil.readText(cacheFile)
        val o = parseJsonObject(text, "cached manifest")
        val fetched = o.str("fetchedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
        val manifestJson = o.obj("manifest") ?: throw MetadataException("cache has no manifest")
        VersionManifest.parse(CsJson.encodeToString(JsonObject.serializer(), manifestJson), fetched, true)
    }.onFailure {
        if (Files.exists(cacheFile)) CsLog.w("Manifest", "Ignoring unreadable manifest cache: ${it.message}")
    }.getOrNull()

    private fun writeCache(text: String) {
        runCatching {
            val wrapper = buildString {
                append("{\"fetchedAt\":\"${Instant.now()}\",\"manifest\":")
                append(text.trim())
                append("}")
            }
            FsUtil.writeAtomic(cacheFile, wrapper)
        }.onFailure { CsLog.w("Manifest", "Could not write manifest cache: ${it.message}") }
    }

    fun cachedExists(): Boolean = Files.isRegularFile(cacheFile)

    fun clearCache() {
        memo = null
        runCatching { Files.deleteIfExists(cacheFile) }
    }

    private fun JsonObject.boolOrNull(key: String): Boolean? = bool(key)
}
