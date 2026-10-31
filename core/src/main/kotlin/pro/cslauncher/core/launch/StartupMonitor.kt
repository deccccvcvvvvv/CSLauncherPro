package pro.cslauncher.core.launch

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

@Serializable
enum class Milestone { JVM_STARTED, MAIN_ENTERED, GAME_INIT, RENDER_INIT, READY, CRASH_DETECTED }

/**
 * Derives startup milestones from the game's output.
 *
 * Why this exists: the launcher must be able to say "it reached the title screen" or "it crashed
 * while initialising the renderer", and the only evidence available without injecting code into the
 * game is its log. The patterns are deliberately version tolerant, and READY uses the texture-atlas /
 * sound-engine markers that vanilla prints right before the title screen.
 *
 * Only *fatal* markers count as a crash. Errors that vanilla recovers from (a missing Narrator native,
 * no audio device, a Realm lookup failing) are kept as log evidence but must never be reported as a
 * crash — that is how a working game ends up labelled broken.
 */
class StartupMonitor(private val onMilestone: (Milestone, String) -> Unit = { _, _ -> }) {

    private val reached = linkedSetOf<Milestone>()

    val milestones: Set<Milestone> get() = reached
    var crashLine: String? = null
        private set
    var lineCount: Long = 0L
        private set

    private val rules: List<Pair<Milestone, Regex>> = listOf(
        Milestone.MAIN_ENTERED to Regex(
            "Setting user: |Loading Minecraft .* with Fabric Loader|Loading for game Minecraft|" +
                "ModLauncher running|Launching wrapped minecraft|Starting minecraft client"
        ),
        Milestone.GAME_INIT to Regex(
            "Backend library: LWJGL|LWJGL Version: |Environment: authHost|Datafixer optimizations"
        ),
        Milestone.RENDER_INIT to Regex(
            "OpenGL|GL_VENDOR|Reloading ResourceManager|Narrator library"
        ),
        Milestone.READY to Regex(
            "Sound engine started|Created: \\\\d+x\\\\d+x?\\\\d* minecraft:textures/atlas/blocks\\\\.png-atlas|" +
                "Created: \\\\d+x\\\\d+ textures-atlas"
        ),
        Milestone.CRASH_DETECTED to Regex(
            "---- Minecraft Crash Report ----|#@!@# Game crashed!|" +
                "Exception in thread (main|Render thread)|" +
                "Error: Could not (create the Java Virtual Machine|find or load main class)|" +
                "A fatal error has been detected by the Java Runtime|" +
                "Failed to start the minecraft server|Unable to launch\\b|" +
                "Unreported exception thrown!|Error occurred during initialization of VM"
        )
    )

    fun markJvmStarted() = hit(Milestone.JVM_STARTED, "JVM started")

    fun onLine(line: String) {
        lineCount++
        if (Milestone.JVM_STARTED !in reached) hit(Milestone.JVM_STARTED, line)
        for ((m, re) in rules) {
            if (m in reached && m != Milestone.CRASH_DETECTED) continue
            if (!re.containsMatchIn(line)) continue
            if (m == Milestone.CRASH_DETECTED) {
                if (crashLine == null) {
                    crashLine = line
                    hit(m, line)
                }
            } else {
                // Output formats differ between versions: seeing a later marker implies the earlier ones.
                Milestone.entries
                    .filter { it.ordinal < m.ordinal && it != Milestone.CRASH_DETECTED }
                    .filterNot { it in reached }
                    .forEach { hit(it, line) }
                hit(m, line)
            }
        }
    }

    private fun hit(m: Milestone, line: String) {
        if (reached.add(m)) onMilestone(m, line)
    }

    /** The state the current evidence justifies. */
    fun stateFor(): LaunchState = when {
        Milestone.READY in reached -> LaunchState.RUNNING
        Milestone.MAIN_ENTERED in reached -> LaunchState.LOADING_GAME
        else -> LaunchState.INITIALIZING_GAME
    }

    val crashed: Boolean get() = Milestone.CRASH_DETECTED in reached

    val reachedTitleScreen: Boolean get() = Milestone.READY in reached

    fun summary(): String = when {
        crashed -> "Crashed: ${crashLine?.take(160) ?: "no detail"}"
        reachedTitleScreen -> "Reached the title screen"
        Milestone.MAIN_ENTERED in reached -> "Started loading the game, but never reached the title screen"
        else -> "The JVM started, but the game never got past initialisation"
    }
}

/**
 * Mojang's logging configurations (`assets/log_configs/client-*.xml`) make the game print log4j XML
 * events whose payload is meant for launchers. This streaming converter turns them into readable
 * `[HH:mm:ss] [thread/LEVEL]: message` lines and passes anything else (plain stdout, JVM messages)
 * through unchanged.
 *
 * Feed it one line at a time; it keeps no unbounded state, so a 50 MB log cannot blow up the
 * launcher's heap.
 */
class Log4jXml {

    private var level = "INFO"
    private var thread = "main"
    private var time = ""
    private var inThrowable = false
    private var inMessage = false

    /** Returns zero or more readable lines for one raw output line. */
    fun feed(raw: String): List<String> {
        val line = raw.trimEnd()
        val t = line.trim()

        if (inThrowable || inMessage) {
            val endTag = if (inThrowable) "]]></log4j:Throwable>" else "]]></log4j:Message>"
            return if (t.endsWith(endTag)) {
                inThrowable = false
                inMessage = false
                listOfNotNull(line.removeSuffix(endTag).takeIf { it.isNotBlank() })
            } else {
                listOf(line)
            }
        }

        if (t.startsWith("<log4j:Event ")) {
            level = attr(t, "level") ?: "INFO"
            thread = attr(t, "thread") ?: "main"
            time = attr(t, "timestamp")?.toLongOrNull()?.let { fmt(it) } ?: ""
            return emptyList()
        }
        if (t == "</log4j:Event>") return emptyList()

        if (t.startsWith("<log4j:Message><![CDATA[")) {
            val body = t.removePrefix("<log4j:Message><![CDATA[")
            val prefix = (if (time.isNotEmpty()) "[$time] " else "") + "[$thread/$level]: "
            return if (body.endsWith("]]></log4j:Message>")) {
                listOf(prefix + body.removeSuffix("]]></log4j:Message>"))
            } else {
                inMessage = true
                listOf(prefix + body)
            }
        }

        if (t.startsWith("<log4j:Throwable><![CDATA[")) {
            val body = t.removePrefix("<log4j:Throwable><![CDATA[")
            return if (body.endsWith("]]></log4j:Throwable>")) {
                listOf(body.removeSuffix("]]></log4j:Throwable>"))
            } else {
                inThrowable = true
                listOf(body)
            }
        }
        return listOf(line)
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    private fun fmt(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().withNano(0).toString()

    companion object {
        /** Stateless best-effort flattening of a single line (used when analysing stored logs). */
        fun flattenText(line: String): String = line.trim().let { t ->
            when {
                t.startsWith("<log4j:Message><![CDATA[") ->
                    t.removePrefix("<log4j:Message><![CDATA[").removeSuffix("]]></log4j:Message>")
                t.startsWith("<log4j:Throwable><![CDATA[") ->
                    t.removePrefix("<log4j:Throwable><![CDATA[").removeSuffix("]]></log4j:Throwable>")
                else -> line
            }
        }
    }
}
