package pro.cslauncher.core.content

import pro.cslauncher.core.download.DownloadTask
import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.FsUtil
import pro.cslauncher.core.util.Integrity
import pro.cslauncher.core.util.arr
import pro.cslauncher.core.util.bool
import pro.cslauncher.core.util.int
import pro.cslauncher.core.util.long
import pro.cslauncher.core.util.obj
import pro.cslauncher.core.util.parseJsonObject
import pro.cslauncher.core.util.str
import java.net.URLEncoder
import java.nio.file.Path

enum class ProjectKind(val modrinth: String) {
    MOD("mod"),
    MODPACK("modpack"),
    RESOURCE_PACK("resourcepack"),
    SHADER("shader"),
    DATAPACK("datapack")
}

data class CatalogProject(
    val source: String,
    val id: String,
    val slug: String,
    val title: String,
    val description: String,
    val downloads: Long,
    val iconUrl: String?,
    val author: String?,
    val categories: List<String>
)

data class CatalogFile(
    val source: String,
    val projectId: String,
    val versionName: String,
    val fileName: String,
    val url: String,
    val sha1: String?,
    val sha512: String?,
    val size: Long?,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val requiredDependencies: List<String>
) {
    fun task(dir: Path): DownloadTask = DownloadTask(
        url = url,
        destination = dir.resolve(FsUtil.safeFileName(fileName)),
        integrity = Integrity(sha1 = sha1, sha512 = sha512, size = size),
        label = fileName
    )

    fun matches(gameVersion: String?, loader: String?): Boolean {
        val versionOk = gameVersion == null || gameVersions.isEmpty() || gameVersion in gameVersions
        val loaderOk = loader == null || loaders.isEmpty() || loaders.any { it.equals(loader, true) }
        return versionOk && loaderOk
    }
}

interface ContentCatalog {
    val name: String

    /** False when the source needs a key or is otherwise unusable; the UI hides it instead of failing. */
    val available: Boolean

    fun search(query: String, kind: ProjectKind, gameVersion: String?, loader: String?, offset: Int = 0): List<CatalogProject>

    fun files(projectId: String, gameVersion: String?, loader: String?): List<CatalogFile>

    /** The single file a user most likely wants: newest matching, preferring the primary file. */
    fun bestFile(projectId: String, gameVersion: String?, loader: String?): CatalogFile? =
        files(projectId, gameVersion, loader).firstOrNull { it.matches(gameVersion, loader) }
}

/**
 * Modrinth's public API — no key, so this is the default catalogue.
 *
 * Every URL that comes back is checked to be `https://` before it is offered: a catalogue response is
 * remote input, and silently downloading over plain HTTP would defeat the transport's guarantees.
 */
class ModrinthCatalog(private val http: HttpTransport) : ContentCatalog {

    override val name: String = "Modrinth"
    override val available: Boolean = true

    private val base = "https://api.modrinth.com/v2"
    private val userAgent = "CSLauncherPro/1.0 (launcher)"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    override fun search(query: String, kind: ProjectKind, gameVersion: String?, loader: String?, offset: Int): List<CatalogProject> {
        val facets = buildList {
            add("[\"project_type:${kind.modrinth}\"]")
            gameVersion?.let { add("[\"versions:$it\"]") }
            if (loader != null && (kind == ProjectKind.MOD || kind == ProjectKind.MODPACK)) {
                add("[\"categories:$loader\"]")
            }
        }
        val url = "$base/search?query=${enc(query)}&limit=20&offset=$offset" +
            "&facets=${enc(facets.joinToString(",", "[", "]"))}"
        val root = parseJsonObject(http.getText(url, mapOf("User-Agent" to userAgent)), "Modrinth search")
        return root.arr("hits").orEmpty().mapNotNull { hit ->
            val p = hit as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val id = p.str("project_id") ?: return@mapNotNull null
            CatalogProject(
                source = "modrinth",
                id = id,
                slug = p.str("slug") ?: "",
                title = p.str("title") ?: "",
                description = p.str("description") ?: "",
                downloads = p.long("downloads") ?: 0,
                iconUrl = p.str("icon_url"),
                author = p.str("author"),
                categories = p.arr("categories")?.map { it.toString().trim('"') }.orEmpty()
            )
        }
    }

    override fun files(projectId: String, gameVersion: String?, loader: String?): List<CatalogFile> {
        require(Regex("^[A-Za-z0-9_-]{1,64}$").matches(projectId)) { "Invalid project id" }
        val query = buildList {
            loader?.let { add("loaders=${enc("[\"$it\"]")}") }
            gameVersion?.let { add("game_versions=${enc("[\"$it\"]")}") }
        }.joinToString("&")
        val url = "$base/project/$projectId/version" + if (query.isNotEmpty()) "?$query" else ""
        val array = CsJson.parseToJsonElement(http.getText(url, mapOf("User-Agent" to userAgent)))
            as? kotlinx.serialization.json.JsonArray ?: return emptyList()

        return array.mapNotNull { element ->
            val o = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val files = o.arr("files")?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }.orEmpty()
            val file = files.firstOrNull { it.bool("primary") == true } ?: files.firstOrNull() ?: return@mapNotNull null
            val downloadUrl = file.str("url") ?: return@mapNotNull null
            if (!downloadUrl.startsWith("https://")) return@mapNotNull null
            CatalogFile(
                source = "modrinth",
                projectId = projectId,
                versionName = o.str("version_number") ?: "",
                fileName = file.str("filename") ?: "download.jar",
                url = downloadUrl,
                sha1 = file.obj("hashes")?.str("sha1"),
                sha512 = file.obj("hashes")?.str("sha512"),
                size = file.long("size"),
                gameVersions = o.arr("game_versions")?.map { it.toString().trim('"') }.orEmpty(),
                loaders = o.arr("loaders")?.map { it.toString().trim('"') }.orEmpty(),
                requiredDependencies = o.arr("dependencies").orEmpty().mapNotNull { d ->
                    val dep = d as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    if (dep.str("dependency_type") == "required") dep.str("project_id") else null
                }
            )
        }
    }
}

/**
 * CurseForge. Needs an API key, so [available] is false without one and the UI hides the source
 * instead of showing an error the user cannot fix.
 *
 * Files whose author forbids third-party downloads come back with no URL; those are reported as
 * unavailable rather than offered as a broken download.
 */
class CurseForgeCatalog(private val http: HttpTransport, private val apiKey: String?) : ContentCatalog {

    override val name: String = "CurseForge"
    override val available: Boolean get() = !apiKey.isNullOrBlank()

    private val base = "https://api.curseforge.com/v1"

    private fun headers(): Map<String, String> = mapOf(
        "x-api-key" to (apiKey ?: throw IllegalStateException("CurseForge needs an API key (Settings › Content)")),
        "Accept" to "application/json"
    )

    private val classIds = mapOf(
        ProjectKind.MOD to 6,
        ProjectKind.MODPACK to 4471,
        ProjectKind.RESOURCE_PACK to 12,
        ProjectKind.SHADER to 6552,
        ProjectKind.DATAPACK to 6945
    )
    private val loaderIds = mapOf("forge" to 1, "fabric" to 4, "quilt" to 5, "neoforge" to 6)

    override fun search(query: String, kind: ProjectKind, gameVersion: String?, loader: String?, offset: Int): List<CatalogProject> {
        val q = buildString {
            append("gameId=432&classId=${classIds[kind]}&pageSize=20&index=$offset")
            append("&searchFilter=${URLEncoder.encode(query, "UTF-8")}")
            gameVersion?.let { append("&gameVersion=$it") }
            loader?.let { l -> loaderIds[l]?.let { append("&modLoaderType=$it") } }
        }
        val root = parseJsonObject(http.getText("$base/mods/search?$q", headers()), "CurseForge search")
        return root.arr("data").orEmpty().mapNotNull { el ->
            val p = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val id = p.long("id")?.toString() ?: return@mapNotNull null
            CatalogProject(
                source = "curseforge",
                id = id,
                slug = p.str("slug") ?: "",
                title = p.str("name") ?: "",
                description = p.str("summary") ?: "",
                downloads = p.long("downloadCount") ?: 0,
                iconUrl = p.obj("logo")?.str("thumbnailUrl"),
                author = p.arr("authors")?.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonObject)?.str("name") },
                categories = p.arr("categories").orEmpty()
                    .mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.str("name") }
            )
        }
    }

    override fun files(projectId: String, gameVersion: String?, loader: String?): List<CatalogFile> {
        require(projectId.all { it.isDigit() }) { "Invalid project id" }
        val q = buildList {
            gameVersion?.let { add("gameVersion=$it") }
            loader?.let { l -> loaderIds[l]?.let { add("modLoaderType=$it") } }
        }.joinToString("&")
        val root = parseJsonObject(http.getText("$base/mods/$projectId/files?$q", headers()), "CurseForge files")
        return root.arr("data").orEmpty().mapNotNull { el ->
            val x = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            // Null when the author disallows third-party downloads: not offerable, but not an error.
            val url = x.str("downloadUrl") ?: return@mapNotNull null
            if (!url.startsWith("https://")) return@mapNotNull null
            val sha1 = x.arr("hashes").orEmpty()
                .mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                .firstOrNull { it.int("algo") == 1 }?.str("value")
            CatalogFile(
                source = "curseforge",
                projectId = projectId,
                versionName = x.str("displayName") ?: "",
                fileName = x.str("fileName") ?: "download.jar",
                url = url,
                sha1 = sha1,
                sha512 = null,
                size = x.long("fileLength"),
                gameVersions = x.arr("gameVersions").orEmpty().map { it.toString().trim('"') },
                loaders = emptyList(),
                requiredDependencies = emptyList()
            )
        }
    }
}

/**
 * Joins the available catalogues.
 *
 * Search results are merged and de-duplicated by title so the same mod does not appear twice, and a
 * failing source degrades to "no results from that source" instead of an empty screen.
 */
class CatalogSet(private val catalogs: List<ContentCatalog>) {

    constructor(vararg catalogs: ContentCatalog) : this(catalogs.toList())

    val available: List<ContentCatalog> get() = catalogs.filter { it.available }

    fun search(
        query: String,
        kind: ProjectKind,
        gameVersion: String?,
        loader: String?,
        offset: Int = 0,
        onError: (String) -> Unit = {}
    ): List<CatalogProject> {
        val results = mutableListOf<CatalogProject>()
        for (catalog in available) {
            runCatching { catalog.search(query, kind, gameVersion, loader, offset) }
                .onSuccess { results += it }
                .onFailure { onError("${catalog.name}: ${it.message}") }
        }
        return results
            .distinctBy { "${it.source}:${it.id}" }
            .sortedByDescending { it.downloads }
    }

    fun files(projectId: String, source: String, gameVersion: String?, loader: String?): List<CatalogFile> =
        available.firstOrNull { it.name.equals(source, true) || it.name.take(4).lowercase() == source.take(4).lowercase() }
            ?.files(projectId, gameVersion, loader)
            .orEmpty()

    companion object {
        /** The usual configuration: Modrinth always, CurseForge when the user has supplied a key. */
        fun standard(http: HttpTransport, curseForgeKey: String? = null): CatalogSet =
            CatalogSet(ModrinthCatalog(http), CurseForgeCatalog(http, curseForgeKey))
    }
}
