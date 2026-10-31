package pro.cslauncher.core.diagnostics

import pro.cslauncher.core.launch.Log4jXml
import pro.cslauncher.core.util.Redactor

enum class FailureKind(val title: String) {
    MISSING_RUNTIME("Java runtime missing"),
    WRONG_JAVA_VERSION("Wrong Java version"),
    INCOMPLETE_CLIENT("Incomplete game installation"),
    MISSING_LIBRARY("Missing library"),
    CORRUPT_FILE("Corrupt downloaded file"),
    MISSING_NATIVE("Missing native library"),
    UNSUPPORTED_RENDERER("Unsupported renderer"),
    GRAPHICS_INIT("Graphics initialisation failed"),
    MISSING_MOD_DEPENDENCY("Missing mod dependency"),
    INCOMPATIBLE_MOD("Incompatible mod"),
    INVALID_JVM_ARGUMENT("Invalid JVM argument"),
    INSUFFICIENT_STORAGE("Insufficient storage"),
    OUT_OF_MEMORY("Out of memory"),
    INCOMPATIBLE_SHADER("Incompatible shader pack"),
    AUTHENTICATION("Authentication failed"),
    DIRECTORY_PERMISSION("Game directory not writable"),
    PROCESS_KILLED("Game process terminated"),
    NATIVE_CRASH("Native crash"),
    HEAP_POINTER_TAGGING("Android heap pointer tagging abort"),
    UNKNOWN("Unknown failure");

    val rendererRelated: Boolean
        get() = this == GRAPHICS_INIT || this == UNSUPPORTED_RENDERER || this == NATIVE_CRASH
}

/**
 * Something the user (or the launcher) can do about a failure. [destructive] actions are never
 * executed automatically: disabling a mod or removing a JVM argument changes what the user asked for,
 * so those require an explicit confirmation.
 */
sealed class RepairAction(val description: String, val destructive: Boolean) {
    class RedownloadFiles(val reason: String) :
        RepairAction("Verify the installation and re-download only the missing or corrupt files ($reason)", false)

    object RebuildNatives : RepairAction("Rebuild the launcher-generated natives directory", false)

    object RemoveTempFiles : RepairAction("Remove unfinished temporary downloads (*.part)", false)

    class SuggestRuntime(val major: Int) : RepairAction("Install or select Java $major for this profile", false)

    class SuggestRenderer(val reason: String) : RepairAction("Choose a different renderer ($reason)", false)

    class SuggestMemory(val mb: Int) : RepairAction(
        if (mb > 0) "Change the memory allocation to $mb MB" else "Lower the memory allocation for this device",
        false
    )

    class InstallMod(val modId: String) : RepairAction("Install the missing mod '$modId'", false)

    class DisableMod(val hint: String) :
        RepairAction("Disable the incompatible mod ($hint) — requires your confirmation", true)

    class RemoveJvmArgument(val arg: String) :
        RepairAction("Remove the JVM argument '$arg' from the profile — requires your confirmation", true)

    object SignInAgain : RepairAction("Sign in to the account again", false)

    object FreeStorage : RepairAction("Free storage space (Storage Manager)", false)

    object UpdateLauncher : RepairAction("Update CS LAUNCHER PRO to a version that supports this device", false)
}

data class Diagnosis(
    val kind: FailureKind,
    val confidence: Double,
    val explanation: String,
    val evidence: List<String>,
    val repairs: List<RepairAction>
) {
    fun describe(): String = "${kind.title}: $explanation"
}

/**
 * Classifies game logs into a [FailureKind] with evidence and suggested repairs.
 *
 * Two ideas make this useful rather than noisy:
 *
 * 1. **Known-benign lines are skipped.** A missing Narrator native library, no OpenAL device or a
 *    failing Realms lookup happens on a perfectly working game. Reporting those as a crash is how a
 *    launcher loses the user's trust.
 * 2. **The evidence is shown.** Every diagnosis carries the surrounding log lines, so the user (and a
 *    bug report) can see why the launcher thinks what it thinks.
 */
object CrashAnalyzer {

    private data class Rule(
        val kind: FailureKind,
        val pattern: Regex,
        val confidence: Double,
        val explain: (MatchResult) -> String,
        val repairs: (MatchResult) -> List<RepairAction>
    )

    private fun firstNonEmpty(m: MatchResult): String =
        m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun lastNonEmpty(m: MatchResult): String =
        m.groupValues.drop(1).lastOrNull { it.isNotEmpty() }.orEmpty()

    private val rules: List<Rule> = listOf(
        Rule(
            FailureKind.WRONG_JAVA_VERSION,
            Regex("UnsupportedClassVersionError.*class file version (\\d+)"),
            0.95,
            { m -> "The game or a mod was compiled for Java ${(m.groupValues[1].toIntOrNull() ?: 0) - 44}, which is newer than the selected runtime." },
            { m -> listOf(RepairAction.SuggestRuntime(((m.groupValues[1].toIntOrNull() ?: 44) - 44))) }
        ),
        Rule(
            FailureKind.WRONG_JAVA_VERSION,
            Regex("requires Java (\\d+)|Java (\\d+) or (?:newer|higher) is required"),
            0.8,
            { m -> "A component requires Java ${firstNonEmpty(m)}." },
            { m -> listOf(RepairAction.SuggestRuntime(firstNonEmpty(m).toIntOrNull() ?: 17)) }
        ),
        Rule(
            FailureKind.OUT_OF_MEMORY,
            Regex("java\\.lang\\.OutOfMemoryError(: (.+))?"),
            0.95,
            { m -> "The Java heap ran out of memory (${m.groupValues.getOrNull(2)?.ifEmpty { "heap" } ?: "heap"})." },
            { listOf(RepairAction.SuggestMemory(0)) }
        ),
        Rule(
            FailureKind.OUT_OF_MEMORY,
            Regex("Could not reserve enough space for .*object heap|Native memory allocation \\(mmap\\) failed"),
            0.9,
            { "The JVM could not reserve the requested memory: the allocation is too large for this device." },
            { listOf(RepairAction.SuggestMemory(-1)) }
        ),
        Rule(
            FailureKind.INVALID_JVM_ARGUMENT,
            Regex("Unrecognized (?:VM )?option '([^']+)'|Unrecognized option: (\\S+)|Invalid maximum heap size: (\\S+)"),
            0.95,
            { m -> "The JVM rejected the argument '${firstNonEmpty(m)}'." },
            { m -> listOf(RepairAction.RemoveJvmArgument(firstNonEmpty(m))) }
        ),
        Rule(
            FailureKind.MISSING_NATIVE,
            Regex("UnsatisfiedLinkError:? (.{0,160})|Failed to locate library: (\\S+)|dlopen failed: (.{0,160})"),
            0.85,
            { m -> "A native library could not be loaded: ${firstNonEmpty(m).trim()}" },
            { listOf(RepairAction.RebuildNatives, RepairAction.SuggestRenderer("native library failed to load")) }
        ),
        Rule(
            FailureKind.GRAPHICS_INIT,
            Regex(
                "GLFW error (\\d+)|Failed to create (?:the )?(?:OpenGL )?context|No OpenGL context found|" +
                    "EGL_BAD_\\w+|eglCreateContext failed|OSMesaCreateContext failed|" +
                    "WGL: The driver does not appear to support OpenGL"
            ),
            0.85,
            { m -> "The renderer could not create a graphics context (${m.value.take(80)})." },
            { listOf(RepairAction.SuggestRenderer("context creation failed")) }
        ),
        Rule(
            FailureKind.UNSUPPORTED_RENDERER,
            Regex(
                "OpenGL 3\\.2|GL_ARB_|requires OpenGL (\\d\\.\\d)|Unsupported OpenGL version|" +
                    "GLX: Failed to create context: GLXBadFBConfig"
            ),
            0.75,
            { "The game needs OpenGL features the selected renderer does not provide." },
            { listOf(RepairAction.SuggestRenderer("missing OpenGL features")) }
        ),
        Rule(
            FailureKind.INCOMPATIBLE_SHADER,
            Regex("(?i)(iris|oculus|optifine).{0,80}(shader).{0,80}(fail|error|compile)|Shader compilation failed|Failed to (?:compile|link) program"),
            0.7,
            { "A shader pack failed to compile on this renderer/driver." },
            { listOf(RepairAction.SuggestRenderer("shader compilation failed")) }
        ),
        Rule(
            FailureKind.MISSING_MOD_DEPENDENCY,
            Regex(
                "requires (?:any )?(?:version(?: .{0,60}?)? of )?(?:mod )?'?([a-z0-9_\\-]+)'?,? which is missing|" +
                    "Mod ID: '([a-z0-9_\\-]+)',? Requested by|" +
                    "Missing or unsupported mandatory dependencies:\\s*Mod ID: '([a-z0-9_\\-]+)'"
            ),
            0.85,
            { m -> "A mod requires '${lastNonEmpty(m)}', which is not installed." },
            { m -> listOf(RepairAction.InstallMod(lastNonEmpty(m))) }
        ),
        Rule(
            FailureKind.INCOMPATIBLE_MOD,
            Regex("Incompatible mods? found|Mixin apply (?:for mod )?([a-z0-9_\\-]+)? ?failed|MixinApplyError|is incompatible with|Duplicate mods? found"),
            0.75,
            { m ->
                val id = m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
                "A mod is incompatible with this game version or with another mod${id?.let { " ($it)" } ?: ""}."
            },
            { m ->
                listOf(
                    RepairAction.DisableMod(m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "see the evidence")
                )
            }
        ),
        Rule(
            FailureKind.CORRUPT_FILE,
            Regex("ZipException|invalid (?:LOC|CEN) header|zip END header not found|java\\.util\\.zip\\.ZipError|Invalid or corrupt jarfile|error in opening zip file"),
            0.85,
            { "A downloaded archive is corrupt." },
            { listOf(RepairAction.RedownloadFiles("corrupt archive"), RepairAction.RemoveTempFiles) }
        ),
        Rule(
            FailureKind.MISSING_LIBRARY,
            Regex(
                "ClassNotFoundException: (net\\.minecraft\\.|com\\.mojang\\.|org\\.lwjgl\\.|io\\.netty|com\\.google)\\S*|" +
                    "NoClassDefFoundError: (net/minecraft|com/mojang|org/lwjgl|io/netty|com/google)\\S*"
            ),
            0.8,
            { m -> "A required game class is missing (${m.value.substringAfter(": ").take(80)})." },
            { listOf(RepairAction.RedownloadFiles("missing classes")) }
        ),
        Rule(
            FailureKind.INCOMPLETE_CLIENT,
            Regex("Error: Could not find or load main class (\\S+)"),
            0.9,
            { m -> "The main class ${m.groupValues[1]} was not found: the client jar or loader libraries are missing." },
            { listOf(RepairAction.RedownloadFiles("main class missing")) }
        ),
        Rule(
            FailureKind.INSUFFICIENT_STORAGE,
            Regex("No space left on device|ENOSPC|There is not enough space on the disk"),
            0.95,
            { "The device ran out of storage." },
            { listOf(RepairAction.FreeStorage, RepairAction.RemoveTempFiles) }
        ),
        Rule(
            FailureKind.DIRECTORY_PERMISSION,
            Regex("AccessDeniedException|Permission denied|EACCES|Read-only file system"),
            0.75,
            { "The game could not write to its directory." },
            { emptyList() }
        ),
        Rule(
            FailureKind.AUTHENTICATION,
            Regex("Invalid session|Failed to (?:login|verify username)|Not authenticated with Minecraft\\.net|Multiplayer is disabled"),
            0.8,
            { "The account session is invalid or expired." },
            { listOf(RepairAction.SignInAgain) }
        ),
        Rule(
            FailureKind.HEAP_POINTER_TAGGING,
            Regex("Pointer tag for 0x[0-9a-fA-F]+ was truncated"),
            0.95,
            {
                "Android aborted the game because native code freed a heap pointer whose Android 11+ tag had been " +
                    "removed. CS LAUNCHER PRO turns heap pointer tagging off for the game; update the launcher and try again."
            },
            { listOf(RepairAction.UpdateLauncher) }
        ),
        Rule(
            FailureKind.NATIVE_CRASH,
            Regex("A fatal error has been detected by the Java Runtime Environment|SIGSEGV|SIGBUS|SIGABRT|Fatal signal \\d+"),
            0.7,
            { "A native component crashed (driver, renderer or JVM)." },
            { listOf(RepairAction.SuggestRenderer("native crash")) }
        ),
        Rule(
            FailureKind.MISSING_RUNTIME,
            Regex("Error occurred during initialization of VM|Cannot find java\\.base|no libjvm|JNI_CreateJavaVM failed"),
            0.9,
            { "The Java runtime could not be initialised." },
            { listOf(RepairAction.SuggestRuntime(0)) }
        )
    )

    /**
     * Lines that look like errors but are normal on Android. Keeping this list short and specific is
     * the point: a broad pattern here would hide real failures.
     */
    private val benign = Regex(
        "fliteWrapper|libflite|Narrator library|text2speech|Failed to open OpenAL device|" +
            "Realms server|/player/attributes|Failed to fetch user properties|" +
            "Could not find or load sound|OpenAL initialized|Using missing texture"
    )

    fun analyze(text: String, exitCode: Int? = null): List<Diagnosis> {
        val lines = text.lineSequence()
            .map { Redactor.redact(Log4jXml.flattenText(it)) }
            .toList()
        val found = LinkedHashMap<FailureKind, Diagnosis>()

        for (rule in rules) {
            for ((i, line) in lines.withIndex()) {
                if (benign.containsMatchIn(line)) continue
                val match = rule.pattern.find(line) ?: continue
                val existing = found[rule.kind]
                if (existing != null && existing.confidence >= rule.confidence) break
                val evidence = lines.subList(maxOf(0, i - 1), minOf(lines.size, i + 3))
                    .map { it.take(300) }
                found[rule.kind] = Diagnosis(
                    kind = rule.kind,
                    confidence = rule.confidence,
                    explanation = rule.explain(match),
                    evidence = evidence,
                    repairs = rule.repairs(match)
                )
                break
            }
        }

        val diagnosed = found.values.sortedByDescending { it.confidence }
        if (diagnosed.isNotEmpty()) return diagnosed

        // A non-zero exit with no recognised cause is still worth reporting: silence would look like a
        // launcher bug. On Android a process death usually means the system killed it for memory.
        val evidence = lines.takeLast(5).map { it.take(300) }
        val oomKilled = lines.any { it.contains("Killed by the system") || it.contains("lowmemorykiller") }
        return when {
            oomKilled -> listOf(
                Diagnosis(
                    FailureKind.PROCESS_KILLED,
                    0.6,
                    "Android killed the game, most likely because it ran out of memory. Lower the memory allocation " +
                        "or use the Battery Saver preset.",
                    evidence,
                    listOf(RepairAction.SuggestMemory(-1))
                )
            )
            exitCode != null && exitCode != 0 -> listOf(
                Diagnosis(
                    FailureKind.UNKNOWN,
                    0.3,
                    "The game stopped with exit code $exitCode and the log does not contain a recognised cause. " +
                        "Share the log from Diagnostics if this happens again.",
                    evidence,
                    emptyList()
                )
            )
            else -> emptyList()
        }
    }

    /** Reads a log file's text and classifies it. Convenience for the crash screen. */
    fun analyzeFile(file: java.nio.file.Path, exitCode: Int? = null): List<Diagnosis> = runCatching {
        analyze(pro.cslauncher.core.util.FsUtil.tail(file, 2 shl 20), exitCode)
    }.getOrDefault(emptyList())

    /**
     * The surrounding evidence for a report: the last lines of the log plus the session plan. Never
     * includes tokens — [Redactor] has already run over every line.
     */
    fun collectEvidence(logFile: java.nio.file.Path?, extra: Map<String, String> = emptyMap()): String = buildString {
        extra.forEach { (k, v) -> appendLine("$k: ${Redactor.redact(v)}") }
        if (logFile != null && java.nio.file.Files.isRegularFile(logFile)) {
            appendLine("--- last 200 log lines ---")
            pro.cslauncher.core.util.FsUtil.tail(logFile, 64 * 1024).lineSequence().toList().takeLast(200)
                .forEach { appendLine(Redactor.redact(it)) }
        }
    }
}
