package pro.cslauncher.core.meta

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.Integrity
import pro.cslauncher.core.util.MetadataException
import pro.cslauncher.core.util.arr
import pro.cslauncher.core.util.int
import pro.cslauncher.core.util.long
import pro.cslauncher.core.util.numberish
import pro.cslauncher.core.util.obj
import pro.cslauncher.core.util.parseJsonObject
import pro.cslauncher.core.util.str

/** A downloadable file declared by version metadata. */
data class Download(val url: String, val integrity: Integrity, val path: String? = null) {
    val hasUrl: Boolean get() = url.startsWith("https://") || url.startsWith("http://")
}

/** A modern-format argument fragment: the values apply only when the rules allow them. */
data class ArgumentEntry(val values: List<String>, val rules: List<Rule>) {
    fun apply(platform: PlatformInfo, features: LaunchFeatures): List<String> =
        if (Rule.allows(rules, platform, features)) values else emptyList()
}

data class AssetIndexRef(val id: String, val url: String, val sha1: String?, val size: Long?, val totalSize: Long?)

data class LoggingConfig(val argument: String, val fileId: String, val download: Download)

/** Maven coordinates `group:artifact:version[:classifier][@ext]`. */
data class MavenCoordinate(
    val group: String,
    val artifact: String,
    val version: String,
    val classifier: String? = null,
    val extension: String = "jar"
) {
    /** `group/artifact/version/artifact-version[-classifier].ext` */
    fun path(): String = buildString {
        append(group.replace('.', '/')).append('/')
        append(artifact).append('/').append(version).append('/')
        append(artifact).append('-').append(version)
        if (!classifier.isNullOrEmpty()) append('-').append(classifier)
        append('.').append(extension)
    }

    val groupArtifact: String get() = "$group:$artifact"

    /** Same artefact regardless of version: used to let a child version override a parent library. */
    val versionlessKey: String get() = "$group:$artifact:${classifier ?: ""}"

    fun withClassifier(c: String) = copy(classifier = c)

    companion object {
        private val PATTERN = Regex("^([^:@\\s]+):([^:@\\s]+):([^:@\\s]+)(?::([^@\\s]+))?(?:@([A-Za-z0-9]+))?$")

        fun parse(name: String): MavenCoordinate {
            val m = PATTERN.matchEntire(name.trim())
                ?: throw MetadataException("Invalid library coordinate '$name'")
            val (g, a, v, c, e) = m.destructured
            return MavenCoordinate(g, a, v, c.ifBlank { null }, e.ifBlank { "jar" })
        }

        fun parseOrNull(name: String): MavenCoordinate? = runCatching { parse(name) }.getOrNull()
    }
}

/**
 * One entry of a version's `libraries` array.
 *
 * `artifact` is the classpath jar; `classifiers` holds optional extra jars (most importantly the
 * native classifier chosen by the `natives` template). Loader metadata (Fabric/Quilt) often omits
 * `downloads` entirely and only gives `name` (+`url`), which the parser expands into an artifact.
 */
data class Library(
    val name: String,
    val coordinate: MavenCoordinate,
    val artifact: Download?,
    val classifiers: Map<String, Download>,
    val natives: Map<String, String>,
    val rules: List<Rule>,
    val extractExclude: List<String>,
    val mavenBaseUrl: String?
) {
    val isNativeOnly get() = natives.isNotEmpty() && artifact == null
    val isNativeClassifierCoordinate get() = coordinate.classifier?.startsWith("natives-") == true

    /** True when this library should be on the classpath for [platform]. */
    fun onClasspath(platform: PlatformInfo, features: LaunchFeatures): Boolean =
        Rule.allows(rules, platform, features) && artifact != null && !isNativeOnly

    /**
     * The native classifier download for [platform], or null. Candidates are tried in order so a
     * `${arch}` template resolves to whichever classifier the publisher actually shipped.
     */
    fun nativeDownload(platform: PlatformInfo): Pair<String, Download>? {
        val template = natives[platform.osName] ?: return null
        for (candidate in NativeClassifier.candidates(template, platform)) {
            classifiers[candidate]?.let { return candidate to it }
        }
        return null
    }
}

/**
 * A fully parsed version JSON. Kept close to the file on disk (`raw` is retained) so a later
 * stage can read fields this launcher does not model yet.
 */
data class VersionMetadata(
    val id: String,
    val type: VersionType,
    val inheritsFrom: String?,
    val mainClass: String,
    val legacyArguments: String?,
    val gameArguments: List<ArgumentEntry>,
    val jvmArguments: List<ArgumentEntry>,
    val assetIndex: AssetIndexRef?,
    val assets: String?,
    val clientDownload: Download?,
    val libraries: List<Library>,
    val javaMajor: Int?,
    val javaComponent: String?,
    val logging: LoggingConfig?,
    val releaseTime: String?,
    val raw: JsonObject
) {
    val usesModernArguments get() = gameArguments.isNotEmpty() || jvmArguments.isNotEmpty()

    /** Pre-1.13 versions use LWJGL 2, which needs a different Android integration than LWJGL 3. */
    val usesLwjgl3 get() = libraries.any {
        it.coordinate.group == "org.lwjgl" && it.coordinate.version.startsWith("3")
    }

    /** 1.17+ requires an OpenGL 3.2 core profile context. */
    val needsGl32Core: Boolean get() = run {
        val v = id.substringBefore('-')
        val parts = v.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return@run usesLwjgl3
        val major = parts[0]
        val minor = parts[1]
        (major > 1) || (major == 1 && minor >= 17)
    }

    /** Minecraft 26.x ships an SDL3 window instead of GLFW. */
    val usesSdl: Boolean get() = run {
        val major = id.substringBefore('.').toIntOrNull() ?: return@run false
        major >= 26
    }

    val effectiveJavaMajor: Int get() = javaMajor ?: 8

    companion object {

        fun parse(text: String): VersionMetadata = parse(parseJsonObject(text, "Version metadata"))

        fun parse(o: JsonObject): VersionMetadata {
            val id = o.str("id")
            if (id.isNullOrBlank()) throw MetadataException("Version metadata has no usable 'id'")
            if (!LauncherDirs.checkIdPattern.matches(id)) {
                throw MetadataException("Version id '$id' is not a safe identifier")
            }
            val inherits = o.str("inheritsFrom")?.also {
                if (!LauncherDirs.checkIdPattern.matches(it)) {
                    throw MetadataException("inheritsFrom '$it' is not a safe identifier")
                }
            }
            val mainClass = o.str("mainClass")
            if (mainClass == null && inherits == null) throw MetadataException("Version $id has no mainClass")

            val args = o.obj("arguments")
            val libraries = (o.arr("libraries") ?: JsonArray(emptyList())).mapIndexed { i, el ->
                val libObj = el as? JsonObject ?: throw MetadataException("Library #$i of $id is not an object")
                parseLibrary(libObj, id)
            }

            val assetIndex = o.obj("assetIndex")?.let {
                AssetIndexRef(
                    id = it.str("id") ?: throw MetadataException("assetIndex.id missing in $id"),
                    url = it.str("url") ?: throw MetadataException("assetIndex.url missing in $id"),
                    sha1 = it.str("sha1"),
                    size = it.long("size") ?: it.numberish("size"),
                    totalSize = it.long("totalSize") ?: it.numberish("totalSize")
                )
            }
            val client = o.obj("downloads")?.obj("client")?.let(::parseDownload)

            val logging = o.obj("logging")?.obj("client")?.let { lc ->
                val file = lc.obj("file") ?: return@let null
                val arg = lc.str("argument") ?: return@let null
                val fileId = file.str("id") ?: return@let null
                val dl = parseDownload(file) ?: return@let null
                LoggingConfig(arg, fileId, dl)
            }

            val javaVersion = o.obj("javaVersion")

            return VersionMetadata(
                id = id,
                type = VersionType.of(o.str("type")),
                inheritsFrom = inherits,
                mainClass = mainClass ?: "",
                legacyArguments = o.str("minecraftArguments"),
                gameArguments = parseArgumentList(args?.arr("game")),
                jvmArguments = parseArgumentList(args?.arr("jvm")),
                assetIndex = assetIndex,
                assets = o.str("assets"),
                clientDownload = client,
                libraries = libraries,
                javaMajor = javaVersion?.int("majorVersion"),
                javaComponent = javaVersion?.str("component"),
                logging = logging,
                releaseTime = o.str("releaseTime"),
                raw = o
            )
        }

        private fun parseDownload(o: JsonObject): Download? {
            val url = o.str("url") ?: return null
            if (url.isBlank()) return null
            return Download(
                url = url,
                integrity = Integrity.of(
                    sha1 = o.str("sha1"),
                    sha256 = o.str("sha256"),
                    size = o.long("size") ?: o.numberish("size")
                ),
                path = o.str("path")
            )
        }

        private fun parseArgumentList(a: JsonArray?): List<ArgumentEntry> = a?.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> ArgumentEntry(listOf(el.content), emptyList())
                is JsonObject -> {
                    val values = when (val v = el["value"]) {
                        is JsonPrimitive -> listOfNotNull(v.contentOrNullSafe())
                        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
                        else -> return@mapNotNull null
                    }
                    if (values.isEmpty()) null
                    else ArgumentEntry(values, Rule.parseList(el.arr("rules")))
                }
                else -> null
            }
        } ?: emptyList()

        private fun JsonPrimitive.contentOrNullSafe(): String? =
            if (isString) content else content.takeIf { it.isNotEmpty() }

        private fun parseLibrary(o: JsonObject, versionId: String): Library {
            val name = o.str("name") ?: throw MetadataException("A library in $versionId has no name")
            val coordinate = MavenCoordinate.parse(name)
            val downloads = o.obj("downloads")
            val artifact = downloads?.obj("artifact")?.let(::parseDownload)
            val classifiers = downloads?.obj("classifiers")?.mapNotNull { (k, v) ->
                val dl = parseDownload(v as? JsonObject ?: return@mapNotNull null) ?: return@mapNotNull null
                k to dl
            }?.toMap() ?: emptyMap()

            val natives = o.obj("natives")?.entries?.mapNotNull { (os, v) ->
                val template = (v as? JsonPrimitive)?.content ?: return@mapNotNull null
                // Mojang's osx entries carry an extra key; only the classifier template matters.
                if (template.isBlank()) null else os to template
            }?.toMap() ?: emptyMap()

            val extract = o.obj("extract")?.arr("exclude")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

            // Loader-style entry: only a coordinate (+ optional url/sha1/size).
            val loaderArtifact = if (artifact == null && downloads == null && natives.isEmpty()) {
                val base = (o.str("url") ?: "https://libraries.minecraft.net/").let {
                    if (it.endsWith("/")) it else "$it/"
                }
                Download(
                    url = base + coordinate.path(),
                    integrity = Integrity.of(sha1 = o.str("sha1"), size = o.long("size") ?: o.numberish("size")),
                    path = coordinate.path()
                )
            } else null

            return Library(
                name = name,
                coordinate = coordinate,
                artifact = artifact ?: loaderArtifact,
                classifiers = classifiers,
                natives = natives,
                rules = Rule.parseList(o.arr("rules")),
                extractExclude = extract,
                mavenBaseUrl = o.str("url")
            )
        }

        /**
         * Merges a child version (a loader profile) onto its parent.
         *
         * Order matters: the child's libraries come first because they must win on the classpath,
         * and parent entries with the same `group:artifact:classifier` are dropped so a loader can
         * pin a different version of a Minecraft library without producing a duplicate.
         */
        fun merge(child: VersionMetadata, parent: VersionMetadata): VersionMetadata {
            val childKeys = child.libraries.map { it.coordinate.versionlessKey }.toSet()
            return child.copy(
                inheritsFrom = parent.inheritsFrom,
                type = if (child.type == VersionType.MODDED) parent.type else child.type,
                mainClass = child.mainClass.ifEmpty { parent.mainClass },
                legacyArguments = child.legacyArguments ?: parent.legacyArguments,
                gameArguments = parent.gameArguments + child.gameArguments,
                jvmArguments = parent.jvmArguments + child.jvmArguments,
                assetIndex = child.assetIndex ?: parent.assetIndex,
                assets = child.assets ?: parent.assets,
                clientDownload = child.clientDownload ?: parent.clientDownload,
                libraries = child.libraries + parent.libraries.filter { it.coordinate.versionlessKey !in childKeys },
                javaMajor = child.javaMajor ?: parent.javaMajor,
                javaComponent = child.javaComponent ?: parent.javaComponent,
                logging = child.logging ?: parent.logging,
                releaseTime = child.releaseTime ?: parent.releaseTime
            ).also { merged ->
                if (merged.mainClass.isBlank()) {
                    CsLog.w("VersionMetadata", "Merged version ${merged.id} has no main class")
                }
            }
        }
    }
}
