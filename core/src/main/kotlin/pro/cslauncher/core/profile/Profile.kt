package pro.cslauncher.core.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import pro.cslauncher.core.util.arr
import pro.cslauncher.core.util.int
import pro.cslauncher.core.util.obj
import pro.cslauncher.core.util.parseJsonObject
import pro.cslauncher.core.util.str
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

@Serializable
enum class LoaderType(val displayName: String) {
    VANILLA("Vanilla"),
    FABRIC("Fabric"),
    QUILT("Quilt"),
    FORGE("Forge"),
    NEOFORGE("NeoForge");

    val isModded: Boolean get() = this != VANILLA
}

@Serializable
enum class RunOutcome { SUCCESS, CRASHED, FAILED_TO_START, KILLED }

@Serializable
enum class GameDirMode { SHARED, ISOLATED, CUSTOM }

@Serializable
data class LoaderSpec(val type: LoaderType, val version: String)

@Serializable
data class RunStatus(
    val outcome: RunOutcome,
    val atMs: Long,
    val stage: String? = null,
    val exitCode: Int? = null,
    val summary: String? = null
)

/**
 * Remembers which renderer actually worked for this profile, together with the **fingerprint** of
 * the device/renderer environment it was proven on. If the device, the renderer libraries or the
 * game version change, the memory is invalid and the launcher re-decides instead of blindly reusing
 * a configuration that may now fail.
 */
@Serializable
data class RendererMemo(val rendererId: String, val fingerprint: String, val atMs: Long)

/**
 * An installation the user can press Play on: a Minecraft version, an optional mod loader, a game
 * directory and everything that may be overridden per profile.
 */
@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val versionId: String,
    val loader: LoaderSpec? = null,
    /** Installed loader version id (which `inheritsFrom` the base version); null until installed. */
    val launchVersionId: String? = null,
    val runtimeId: String? = null,
    val rendererId: String = "auto",
    val rendererOptions: Map<String, String> = emptyMap(),
    val memoryMb: Int? = null,
    val jvmArgs: String = "",
    val gameArgs: String = "",
    val gameDirMode: GameDirMode = GameDirMode.ISOLATED,
    val customGameDir: String? = null,
    val controlLayoutId: String = "default-touch",
    val renderScale: Float = 1.0f,
    val resolutionWidth: Int? = null,
    val resolutionHeight: Int? = null,
    val fullscreen: Boolean = false,
    val env: Map<String, String> = emptyMap(),
    val iconKey: String = "grass",
    val customIconId: String? = null,
    /** "new" | "installing" | "existing" | "failed" — drives what the card's button does. */
    val installationState: String = "existing",
    val installationError: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val lastPlayedAtMs: Long? = null,
    val lastRun: RunStatus? = null,
    val rendererMemo: RendererMemo? = null
) {
    /** The version that is actually launched: the loader build when one is installed. */
    val effectiveVersionId: String get() = launchVersionId ?: versionId

    val isOptiFine: Boolean get() = name.contains("optifine", ignoreCase = true) || versionId.contains("OptiFine", true)

    val isModded: Boolean get() = loader?.type?.isModded == true || isOptiFine

    fun gameDir(dirs: LauncherDirs): Path = when (gameDirMode) {
        GameDirMode.SHARED -> dirs.sharedGameDir
        GameDirMode.ISOLATED -> dirs.instances.resolve(LauncherDirs.checkId(id))
        GameDirMode.CUSTOM -> Paths.get(
            customGameDir ?: throw IllegalStateException("Custom game directory is not set for '${name}'")
        )
    }

    /** Custom JVM flags as the user typed them, minus anything that would override the launcher. */
    fun jvmArgumentList(): List<String> = JvmArgs.tokenize(jvmArgs)

    fun gameArgumentList(): List<String> = JvmArgs.tokenize(gameArgs)

    val totalMemoryIsAutomatic: Boolean get() = memoryMb == null
}

data class ValidationProblem(val field: String, val message: String)

object ProfileValidator {

    private val ENV_KEY = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")

    fun validate(p: Profile): List<ValidationProblem> {
        val out = mutableListOf<ValidationProblem>()
        runCatching { LauncherDirs.checkId(p.id) }
            .onFailure { out += ValidationProblem("id", "Invalid profile identifier") }
        if (p.name.isBlank()) out += ValidationProblem("name", "Profile name cannot be empty")
        if (p.name.length > 64) out += ValidationProblem("name", "Profile name is longer than 64 characters")
        p.customIconId?.let {
            if (!Regex("^[a-fA-F0-9-]{36}\\.(png|jpg|webp|gif)$", RegexOption.IGNORE_CASE).matches(it)) {
                out += ValidationProblem("customIconId", "Invalid custom profile image reference")
            }
        }
        runCatching { LauncherDirs.checkId(p.versionId) }
            .onFailure { out += ValidationProblem("versionId", "Invalid version id") }
        p.memoryMb?.let { if (it < 512) out += ValidationProblem("memoryMb", "At least 512 MB is required") }
        p.memoryMb?.let { if (it > 65536) out += ValidationProblem("memoryMb", "Memory is limited to 64 GB") }
        if (p.gameDirMode == GameDirMode.CUSTOM && p.customGameDir.isNullOrBlank()) {
            out += ValidationProblem("customGameDir", "Choose a game directory")
        }
        if ((p.resolutionWidth == null) != (p.resolutionHeight == null)) {
            out += ValidationProblem("resolution", "Both resolution dimensions are required")
        }
        p.resolutionWidth?.let { if (it !in 320..7680) out += ValidationProblem("resolution", "Width must be 320–7680") }
        p.resolutionHeight?.let { if (it !in 240..4320) out += ValidationProblem("resolution", "Height must be 240–4320") }
        if (p.renderScale !in 0.25f..1.0f) {
            out += ValidationProblem("renderScale", "Render scale must be between 25% and 100%")
        }
        p.env.keys.filterNot { ENV_KEY.matches(it) }
            .forEach { out += ValidationProblem("env", "Invalid environment variable name '$it'") }
        JvmArgs.validate(JvmArgs.tokenize(p.jvmArgs))
            .filter { it.severity == JvmArgs.Severity.ERROR }
            .forEach { out += ValidationProblem("jvmArgs", it.message) }
        runCatching { LauncherDirs.checkId(p.controlLayoutId) }
            .onFailure { out += ValidationProblem("controlLayoutId", "Invalid control layout reference") }
        return out
    }
}

/**
 * Tokenising and sanity-checking user JVM flags.
 *
 * The launcher owns the classpath, the main class and the library path, so flags that would fight
 * with it are rejected rather than allowed to produce an unexplainable crash. Everything else is
 * passed through: power users must not be blocked, only warned.
 */
object JvmArgs {

    enum class Severity { INFO, WARNING, ERROR }

    data class Finding(val arg: String, val severity: Severity, val message: String)

    /** Shell-like tokenizer: quoted segments stay together, empty quoted strings are kept. */
    fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var any = false
        for (c in s) {
            when {
                quote != null -> if (c == quote) quote = null else cur.append(c)
                c == '"' || c == '\'' -> {
                    quote = c
                    any = true
                }
                c.isWhitespace() -> {
                    if (cur.isNotEmpty() || any) {
                        out += cur.toString()
                        cur.clear()
                        any = false
                    }
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty() || any) out += cur.toString()
        return out
    }

    fun validate(args: List<String>): List<Finding> = args.map { a ->
        when {
            a == "-cp" || a == "-classpath" || a.startsWith("-Djava.class.path") ->
                Finding(a, Severity.ERROR, "The classpath is managed by the launcher")
            a == "-jar" ->
                Finding(a, Severity.ERROR, "-jar is not supported; the launcher starts the version's main class")
            a.startsWith("-Xmx") || a.startsWith("-Xms") ->
                Finding(a, Severity.WARNING, "Memory flags override the profile memory setting")
            a.startsWith("-Djava.library.path") ->
                Finding(a, Severity.WARNING, "Overriding java.library.path can break native loading")
            a.startsWith("-javaagent") || a.startsWith("-agentpath") || a.startsWith("-agentlib") ->
                Finding(a, Severity.WARNING, "Java agents run arbitrary code inside the game")
            a.startsWith("-XX:") || a.startsWith("-D") || a.startsWith("-X") ||
                a.startsWith("--add-") || a.startsWith("-verbose") || a.startsWith("--enable-") ->
                Finding(a, Severity.INFO, "ok")
            a.startsWith("-") -> Finding(a, Severity.WARNING, "Unrecognised JVM option; the JVM may refuse to start")
            else -> Finding(a, Severity.ERROR, "'$a' is not a JVM option (did you mean a game argument?)")
        }
    }.filter { it.severity != Severity.INFO }

    /** Removes flags the launcher must own, keeping the user's other flags in order. */
    fun stripLauncherOwned(args: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-cp" || a == "-classpath" -> i += 2
                a == "-jar" -> i += 1
                a.startsWith("-Xmx") || a.startsWith("-Xms") -> i += 1
                a.startsWith("-Djava.library.path") || a.startsWith("-Djava.class.path") -> i += 1
                else -> {
                    out += a
                    i++
                }
            }
        }
        return out
    }
}

/** Per-profile launcher state: which one is selected, and the renderer memory of a failed attempt. */
object LaunchLocks {
    @Volatile
    var activeProfileId: String? = null

    fun tryAcquire(profileId: String): Boolean {
        if (activeProfileId != null) return false
        activeProfileId = profileId
        return true
    }

    fun release(profileId: String? = null) {
        if (profileId == null || activeProfileId == profileId) activeProfileId = null
    }

    val isRunning: Boolean get() = activeProfileId != null
}

data class DeletionPlan(
    val profile: Profile,
    val removesConfigOnly: Boolean,
    val gameDir: Path?,
    val gameDirBytes: Long,
    val sharedWithOthers: Boolean
) {
    fun describe(): String {
        if (gameDir == null) return "Remove '${profile.name}' from the launcher"
        val size = FsUtil.human(gameDirBytes)
        return when {
            sharedWithOthers -> "Remove '${profile.name}' from the launcher. Its game directory is shared, so no files are deleted."
            removesConfigOnly -> "Remove '${profile.name}'. The game directory ($size) stays on disk."
            else -> "Remove '${profile.name}' and delete its game directory ($size, including worlds)."
        }
    }
}

@Serializable
data class ProfilesFile(
    val schemaVersion: Int = ProfileStore.SCHEMA,
    val selectedId: String? = null,
    val profiles: List<Profile> = emptyList()
) {
    val selected: Profile? get() = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
}

/**
 * On-disk profile store.
 *
 * Requirements that shaped it:
 * * **Never lose profiles** — every save is atomic and the previous file is kept as a backup.
 * * **Survive schema changes** — [migrate] upgrades an older file in place instead of discarding it.
 * * **Prevent accidental data loss** — [planDeletion] tells the caller whether deleting would remove
 *   worlds, and refuses to delete a directory another profile also uses.
 */
class ProfileStore(private val file: Path, private val dirs: LauncherDirs) {

    companion object {
        const val SCHEMA = 2
        private const val MAX_BACKUPS = 5
    }

    private val lock = Any()

    fun load(): ProfilesFile = synchronized(lock) {
        if (!Files.isRegularFile(file)) return ProfilesFile()
        runCatching {
            val root = parseJsonObject(FsUtil.readText(file), "profiles.json")
            val migrated = migrate(root)
            CsJson.decodeFromJsonElement(ProfilesFile.serializer(), migrated)
        }.getOrElse { e ->
            CsLog.w("Profiles", "profiles.json unreadable, keeping a copy: ${e.message}")
            runCatching { Files.copy(file, file.resolveSibling("profiles.corrupt-${System.currentTimeMillis()}.json")) }
            ProfilesFile()
        }
    }

    /**
     * Schema 1 stored memory as a free-form string ("2G", "2048M") and had no loader object.
     * The migration converts it into [Profile.memoryMb] and keeps every other field.
     */
    internal fun migrate(root: JsonObject): JsonObject {
        val version = root.int("schemaVersion") ?: 1
        if (version >= SCHEMA) return root
        val profiles: List<kotlinx.serialization.json.JsonElement> = (root.arr("profiles") ?: JsonArray(emptyList())).map { el ->
            val o: MutableMap<String, kotlinx.serialization.json.JsonElement> =
                (el as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            // memory: "2G" / "2048M" / 2048 -> memoryMb
            val memory = o["memory"]
            if (memory != null && o["memoryMb"] == null) {
                val text = memory.jsonPrimitive.content.trim().uppercase()
                val mb = when {
                    text.endsWith("G") -> text.dropLast(1).toDoubleOrNull()?.times(1024)?.toInt()
                    text.endsWith("M") -> text.dropLast(1).toDoubleOrNull()?.toInt()
                    else -> text.toIntOrNull()
                }
                if (mb != null) o["memoryMb"] = kotlinx.serialization.json.JsonPrimitive(mb)
                o.remove("memory")
            }
            // loader: a bare string ("fabric") becomes an object with an empty version.
            val loader = o["loader"]
            if (loader is kotlinx.serialization.json.JsonPrimitive) {
                o["loader"] = buildJsonObject {
                    put("type", loader.content.uppercase())
                    put("version", "")
                }
            }
            // v1 called it "mcVersion".
            o["versionId"]?.let { } ?: run { o["mcVersion"]?.let { o["versionId"] = it } }
            o.remove("mcVersion")
            JsonObject(o)
        }
        return buildJsonObject {
            put("schemaVersion", SCHEMA)
            root["selectedId"]?.let { put("selectedId", it) }
            put("profiles", JsonArray(profiles))
        }
    }

    fun save(pf: ProfilesFile) = synchronized(lock) {
        backup()
        FsUtil.writeAtomic(file, CsJson.encodeToString(ProfilesFile.serializer(), pf))
    }

    fun upsert(p: Profile): ProfilesFile = synchronized(lock) {
        val problems = ProfileValidator.validate(p)
        require(problems.isEmpty()) { problems.joinToString("; ") { "${it.field}: ${it.message}" } }
        val pf = load()
        val list = pf.profiles.filterNot { it.id == p.id } + p
        val next = pf.copy(profiles = list.sortedBy { it.createdAtMs }, selectedId = pf.selectedId ?: p.id)
        save(next)
        next
    }

    fun select(id: String) = synchronized(lock) {
        val pf = load()
        require(pf.profiles.any { it.id == id }) { "No such profile" }
        save(pf.copy(selectedId = id))
    }

    fun duplicate(id: String, newName: String): Profile = synchronized(lock) {
        val src = load().profiles.first { it.id == id }
        val copy = src.copy(
            id = UUID.randomUUID().toString(),
            name = newName,
            createdAtMs = System.currentTimeMillis(),
            lastPlayedAtMs = null,
            lastRun = null,
            rendererMemo = null,
            // A duplicate must not share the original's isolated game directory.
            gameDirMode = if (src.gameDirMode == GameDirMode.ISOLATED) GameDirMode.ISOLATED else src.gameDirMode,
            installationState = if (src.installationState == "failed") "existing" else src.installationState,
            installationError = null
        )
        val pf = load()
        save(pf.copy(profiles = pf.profiles + copy))
        copy
    }

    /**
     * Works out what deleting [id] would really remove, before anything is deleted. The UI shows this
     * sentence verbatim, because "delete profile" destroying a 2 GB world by surprise is the single
     * worst thing a launcher can do.
     */
    fun planDeletion(id: String): DeletionPlan {
        val pf = load()
        val p = pf.profiles.first { it.id == id }
        val gd = runCatching { p.gameDir(dirs) }.getOrNull()
        val shared = gd != null && pf.profiles.any { it.id != id && runCatching { it.gameDir(dirs) }.getOrNull() == gd }
        val bytes = if (gd != null && !shared) FsUtil.sizeOf(gd) else 0L
        return DeletionPlan(
            profile = p,
            removesConfigOnly = !shared && gd == null,
            gameDir = gd,
            gameDirBytes = bytes,
            sharedWithOthers = shared
        )
    }

    fun delete(id: String, deleteGameDir: Boolean) = synchronized(lock) {
        val plan = planDeletion(id)
        val pf = load()
        if (deleteGameDir && !plan.sharedWithOthers) {
            plan.gameDir?.let { gd ->
                // Only ever delete inside the instance directory, never a user-chosen custom path
                // unless the caller explicitly confirmed it: custom paths may hold other data.
                when (plan.profile.gameDirMode) {
                    GameDirMode.ISOLATED -> runCatching { FsUtil.deleteTreeWithin(dirs.instances, gd) }
                    GameDirMode.SHARED -> CsLog.w("Profiles", "Refusing to delete the shared game directory")
                    GameDirMode.CUSTOM -> runCatching { FsUtil.deleteTreeWithin(gd, gd) }
                }
            }
        }
        val remaining = pf.profiles.filterNot { it.id == id }
        save(pf.copy(profiles = remaining, selectedId = pf.selectedId.takeIf { it != id } ?: remaining.firstOrNull()?.id))
    }

    /** A profile as a shareable file. Runtime state (play history, renderer memory) is stripped. */
    fun export(id: String): String {
        val p = load().profiles.first { it.id == id }
        val clean = p.copy(
            lastRun = null,
            rendererMemo = null,
            lastPlayedAtMs = null,
            customIconId = null
        )
        return CsJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("formatVersion", 1)
                put("exportedAtMs", System.currentTimeMillis())
                put("profile", CsJson.encodeToJsonElement(Profile.serializer(), clean))
            }
        )
    }

    fun import(text: String): Profile {
        val root = parseJsonObject(text, "Profile export")
        val p = CsJson.decodeFromJsonElement(Profile.serializer(), root.obj("profile") ?: error("Missing profile"))
        val imported = p.copy(
            id = UUID.randomUUID().toString(),
            createdAtMs = System.currentTimeMillis(),
            lastPlayedAtMs = null,
            lastRun = null,
            rendererMemo = null,
            customIconId = null,
            // An imported profile must never point at a loader build the new device does not have.
            launchVersionId = null,
            installationState = "existing",
            installationError = null
        )
        val problems = ProfileValidator.validate(imported)
        require(problems.isEmpty()) { problems.joinToString("; ") { it.message } }
        upsert(imported)
        return imported
    }

    private val backupDir get() = dirs.backups.resolve("profiles")

    private fun backup() {
        if (!Files.isRegularFile(file)) return
        runCatching {
            Files.createDirectories(backupDir)
            val stamp = System.currentTimeMillis()
            Files.copy(file, backupDir.resolve("profiles-$stamp.json"))
            val existing = Files.list(backupDir).use { it.toList() }.filter { it.fileName.toString().startsWith("profiles-") }
                .sortedBy { it.fileName.toString() }
            if (existing.size > MAX_BACKUPS) {
                existing.take(existing.size - MAX_BACKUPS).forEach { Files.deleteIfExists(it) }
            }
        }.onFailure { CsLog.w("Profiles", "Could not write a profiles backup: ${it.message}") }
    }
}
