package pro.cslauncher.core.launch

import kotlinx.serialization.Serializable
import pro.cslauncher.core.meta.ArgumentEntry
import pro.cslauncher.core.meta.LaunchFeatures
import pro.cslauncher.core.meta.Rule
import pro.cslauncher.core.platform.PlatformInfo
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Expands Mojang's argument templates.
 *
 * Two generations exist: the pre-1.13 `minecraftArguments` string (no rules) and the 1.13+
 * `arguments` object with `game`/`jvm` lists whose entries carry rules and `${placeholders}`. Both are
 * supported, and a placeholder that cannot be resolved is reported instead of being passed through
 * literally — `${auth_player_name}` appearing in a chat message is a support ticket nobody can solve.
 */
object ArgumentBuilder {

    // "}" is escaped: Android's ICU regex rejects a bare "}".
    private val PLACEHOLDER = Regex("""\$\{([A-Za-z0-9_.]+)\}""")

    fun substitute(s: String, values: Map<String, String>, unresolved: MutableSet<String>): String =
        PLACEHOLDER.replace(s) { m ->
            values[m.groupValues[1]] ?: run {
                unresolved += m.groupValues[1]
                m.value
            }
        }

    /** Expands modern argument entries, honouring rules and feature flags. */
    fun expand(entries: List<ArgumentEntry>, platform: PlatformInfo, features: LaunchFeatures): List<String> =
        entries.filter { Rule.allows(it.rules, platform, features) }.flatMap { it.values }

    /** Pre-1.13 `minecraftArguments`. Mojang never puts spaces inside a value here. */
    fun legacy(minecraftArguments: String): List<String> =
        minecraftArguments.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun expandLegacy(minecraftArguments: String, platform: PlatformInfo, features: LaunchFeatures): List<String> =
        ArgumentBuilder.expand(
            listOf(ArgumentEntry(legacy(minecraftArguments), emptyList())), platform, features
        )

    /**
     * Replaces `${auth_access_token}` and any other secret-ish placeholder with a marker before the
     * command line is written to disk. A token in a log file is a credential leak.
     */
    fun redactRedactedCommand(args: List<String>): List<String> {
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (SECRET_FLAG.matches(a)) {
                // The value is the next token: redact it too, not just the flag.
                out += a
                if (i + 1 < args.size) {
                    out += "<redacted>"
                    i += 2
                    continue
                }
            } else if (SECRET_ARG.containsMatchIn(a)) {
                out += SECRET_ARG.replace(a) { m ->
                    m.groupValues[1] + (m.groupValues[2].takeIf { it.isNotEmpty() } ?: "=") + "<redacted>"
                }
            } else {
                out += a
            }
            i++
        }
        return out
    }

    /**
     * Matches a credential flag and its value in either form the game accepts: a separate token
     * (`--accessToken TOKEN`) or an inline one (`--accessToken=TOKEN`).
     */
    private val SECRET_FLAG = Regex("(?i)--(accessToken|clientid|xuid|auth_session|session)")

    private val SECRET_ARG = Regex(
        "(?i)(--accessToken|--clientid|--xuid|--auth_session|--session|auth_access_token=|accessToken=)(\\s+|=)?[^\\s]+"
    )
}

/**
 * `JNI_CreateJavaVM` accepts single-token options only. This converts launcher-style arguments —
 * where `-cp` and `--add-opens` take a separate token — into the joined form the JNI interface needs.
 * Only used by the Android in-process launcher; desktop launches pass arguments through unchanged.
 */
object JvmOptionNormalizer {

    data class Result(val options: List<String>, val dropped: List<String>)

    private val TWO_TOKEN = setOf(
        "--add-opens", "--add-exports", "--add-reads", "--add-modules",
        "--patch-module", "--limit-modules", "--module-path", "-p"
    )

    fun normalize(args: List<String>): Result {
        val out = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-cp" || a == "-classpath" || a == "--class-path" -> {
                    out += "-Djava.class.path=" + args.getOrElse(i + 1) { "" }
                    i += 2
                }
                a in TWO_TOKEN -> {
                    val v = args.getOrElse(i + 1) { "" }
                    val key = if (a == "-p") "--module-path" else a
                    out += if (key == "--module-path") "-Djdk.module.path=$v" else "$key=$v"
                    i += 2
                }
                // macOS-only: the JVM would refuse to start a main thread that does not exist here.
                a == "-XstartOnFirstThread" -> {
                    dropped += a
                    i++
                }
                // Windows-only workaround that some launchers copy into their default arguments.
                a.startsWith("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance") -> {
                    dropped += a
                    i++
                }
                a.isBlank() -> i++
                else -> {
                    out += a
                    i++
                }
            }
        }
        return Result(out, dropped)
    }
}

/**
 * The complete, serialisable instructions for one launch.
 *
 * The plan is written to the session directory before the game starts, which is what makes the
 * launcher debuggable: a crash report can be matched against the exact command line, classpath,
 * renderer and environment that produced it. It is the contract between the core and the native
 * launcher, so keep it backward compatible (`planVersion`).
 */
@Serializable
data class LaunchPlan(
    val planVersion: Int = 2,
    val profileId: String,
    val profileName: String,
    val versionId: String,
    val mainClass: String,
    val javaHome: String,
    val javaMajor: Int,
    val javaExecutable: String,
    val libjvm: String?,
    val classpath: List<String>,
    val nativeDirs: List<String>,
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    val workingDir: String,
    val env: Map<String, String>,
    val rendererId: String,
    val rendererContext: String,
    val rendererLibraries: Map<String, String>,
    val controlLayoutId: String,
    val sessionDir: String,
    val logFile: String,
    val width: Int,
    val height: Int,
    /** Additions over the original plan format; all optional so an older reader still works. */
    val preloadLibraries: List<String> = emptyList(),
    val fullAccessTokenPresent: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    /** The full argument vector, useful for a desktop launch and for diagnostics. */
    fun commandLine(): List<String> = listOf(javaExecutable) + jvmArgs + listOf(mainClass) + gameArgs

    /** Same vector with secrets removed — the only version that may be logged or shown. */
    fun redactedCommandLine(): String = ArgumentBuilder.redactRedactedCommand(commandLine()).joinToString(" ")

    fun validate(): List<String> {
        val out = mutableListOf<String>()
        if (mainClass.isBlank()) out += "No main class"
        if (javaHome.isBlank()) out += "No Java home"
        if (classpath.isEmpty()) out += "Empty classpath"
        classpath.filterNot { Paths.get(it).isAbsolute }.forEach { out += "Classpath entry is not absolute: $it" }
        if (workingDir.isBlank()) out += "No working directory"
        if (width <= 0 || height <= 0) out += "Invalid window size ${width}x$height"
        // A non-absolute native directory is invisible to the dynamic linker: fail loudly instead.
        nativeDirs.filterNot { Paths.get(it).isAbsolute }.forEach { out += "Native directory is not absolute: $it" }
        return out
    }

    fun nativePaths(): List<Path> = nativeDirs.map { Paths.get(it) }

    /** Reads a stored plan back (diagnostics, or resuming a launch the UI recreated). */
    fun fromJson(text: String): LaunchPlan =
        pro.cslauncher.core.util.CsJson.decodeFromString(serializer(), text)
}

class LaunchPlanException(message: String) : Exception(message)
