package pro.cslauncher.core.launch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import pro.cslauncher.core.account.AccountStore
import pro.cslauncher.core.account.AuthException
import pro.cslauncher.core.account.LaunchSession
import pro.cslauncher.core.component.ComponentManager
import pro.cslauncher.core.component.LwjglCompat
import pro.cslauncher.core.install.InstallReport
import pro.cslauncher.core.install.Installer
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.install.ResolvedVersion
import pro.cslauncher.core.install.Verifier
import pro.cslauncher.core.perf.DeviceFacts
import pro.cslauncher.core.perf.PerformanceAdvisor
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.profile.Profile
import pro.cslauncher.core.profile.ProfileValidator
import pro.cslauncher.core.renderer.RendererDecision
import pro.cslauncher.core.renderer.RendererEnvironment
import pro.cslauncher.core.renderer.RendererRegistry
import pro.cslauncher.core.renderer.RendererState
import pro.cslauncher.core.runtime.JavaRuntime
import pro.cslauncher.core.runtime.RuntimeChoice
import pro.cslauncher.core.runtime.RuntimeManager
import pro.cslauncher.core.runtime.RuntimeSelector
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What the user has to do to fix a blocking problem. Drives the one button on the error sheet. */
enum class FixAction {
    NONE,
    INSTALL_VERSION,
    REPAIR_INSTALLATION,
    SIGN_IN,
    INSTALL_RUNTIME,
    CHOOSE_RENDERER,
    INSTALL_COMPONENT,
    FREE_STORAGE,
    FIX_GAME_DIRECTORY,
    EDIT_PROFILE,
    INSTALL_LOADER
}

data class LaunchIssue(
    val stage: LaunchState,
    val message: String,
    val fix: FixAction,
    val blocking: Boolean = true
)

class LaunchBlockedException(val issues: List<LaunchIssue>) :
    Exception(issues.filter { it.blocking }.joinToString("\n") { it.message })

/** Makes sure a runtime for Java [major] is installed and bundled components are present. */
interface Provisioner {
    fun ensureRuntime(major: Int, progress: (String) -> Unit)
    fun ensureComponents(progress: (String) -> Unit)
}

data class LaunchRequest(
    val profile: Profile,
    val session: () -> LaunchSession,
    val environment: (ResolvedVersion) -> RendererEnvironment,
    val device: DeviceFacts,
    val width: Int,
    val height: Int,
    val glfwShim: Path? = null,
    /** Fingerprints of renderers that already failed on this setup, so they are not retried blindly. */
    val failedRendererFingerprints: Set<String> = emptySet(),
    val launcherVersion: String = "1.0.0"
)

data class PreparedLaunch(
    val plan: LaunchPlan,
    val accessToken: String,
    val resolved: ResolvedVersion,
    val renderer: RendererDecision,
    val runtime: RuntimeChoice,
    val rendererFingerprint: String,
    val warnings: List<LaunchIssue>
)

/** Session status shared between the game process/executor and the launcher UI — never secrets. */
@Serializable
data class SessionRecord(
    val profileId: String,
    val versionId: String,
    val rendererId: String,
    val state: LaunchState,
    val failedAt: LaunchState? = null,
    val milestones: List<Milestone> = emptyList(),
    val exitCode: Int? = null,
    val message: String? = null,
    val crashLine: String? = null,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val rendererFingerprint: String? = null
)

/**
 * The single entry point for "play this profile".
 *
 * [prepare] runs every check that can be done cheaply and *refuses to start a launch that cannot
 * work*, returning a [LaunchIssue] with the exact action that fixes it. This is the difference
 * between "it crashed on startup" and "Java 21 is not installed — install it".
 *
 * Order matters and is deliberate: cheap local checks first, then anything that may download, then the
 * renderer decision (which depends on the component packs), and only then the plan.
 */
class LaunchCoordinator(
    private val dirs: LauncherDirs,
    private val platform: PlatformInfo,
    private val installer: Installer,
    private val runtimes: RuntimeManager,
    private val renderers: RendererRegistry,
    private val components: ComponentManager?,
    private val provisioner: Provisioner? = null
) {
    private val planner = LaunchPlanner(dirs, platform)

    suspend fun prepare(req: LaunchRequest, sm: LaunchStateMachine): PreparedLaunch = withContext(Dispatchers.IO) {
        val p = req.profile
        val issues = mutableListOf<LaunchIssue>()

        fun block(stage: LaunchState): Nothing {
            val first = issues.first { it.blocking }
            sm.fail(first.message)
            throw LaunchBlockedException(issues)
        }

        // ---------------------------------------------------------------- 1. validate
        sm.advance(LaunchState.VALIDATING)
        ProfileValidator.validate(p).forEach {
            issues += LaunchIssue(LaunchState.VALIDATING, "${it.field}: ${it.message}", FixAction.EDIT_PROFILE)
        }
        if (p.loader != null && p.launchVersionId == null) {
            issues += LaunchIssue(
                LaunchState.VALIDATING,
                "The ${p.loader.type.displayName} loader is selected but not installed",
                FixAction.INSTALL_LOADER
            )
        }

        val rv = runCatching { installer.resolve(p.effectiveVersionId, null) }.getOrElse {
            issues += LaunchIssue(
                LaunchState.VALIDATING,
                "Minecraft ${p.effectiveVersionId} is not installed (${it.message})",
                FixAction.INSTALL_VERSION
            )
            block(LaunchState.VALIDATING)
        }

        val report: InstallReport = Verifier(dirs, installer, platform).verify(rv, fullHash = false)
        if (!report.complete) {
            val first = report.issues.first()
            issues += LaunchIssue(
                LaunchState.VALIDATING,
                report.summary() + ". First: ${first.name} (${first.problem})",
                FixAction.REPAIR_INSTALLATION
            )
        }

        val session = runCatching { req.session() }.getOrElse {
            issues += LaunchIssue(
                LaunchState.VALIDATING,
                (it as? AuthException)?.message ?: "No usable account: ${it.message}",
                FixAction.SIGN_IN
            )
            null
        }

        val gameDir = runCatching { p.gameDir(dirs) }.getOrNull()
        if (gameDir == null) {
            issues += LaunchIssue(LaunchState.VALIDATING, "Game directory is not configured", FixAction.FIX_GAME_DIRECTORY)
        } else {
            runCatching {
                Files.createDirectories(gameDir)
                LauncherDirs.GAME_SUBDIRS.forEach { Files.createDirectories(gameDir.resolve(it)) }
                val probe = gameDir.resolve(".cs-write-test")
                FsUtil.writeText(probe, "ok")
                Files.delete(probe)
                val free = FsUtil.usableSpace(gameDir)
                if (free in 1 until 150L * 1024 * 1024) {
                    issues += LaunchIssue(
                        LaunchState.VALIDATING,
                        "Only ${free / 1048576} MB free; at least 150 MB are needed for logs and world saves",
                        FixAction.FREE_STORAGE
                    )
                }
            }.onFailure {
                issues += LaunchIssue(
                    LaunchState.VALIDATING,
                    "Game directory $gameDir is not writable: ${it.message}",
                    FixAction.FIX_GAME_DIRECTORY
                )
            }
        }
        if (issues.any { it.blocking }) block(LaunchState.VALIDATING)

        // ---------------------------------------------------------------- 2. runtime
        sm.advance(LaunchState.PREPARING_RUNTIME)
        val required = RuntimeSelector.requiredMajorFor(rv.meta.javaMajor ?: 8)
        provisioner?.let { pv ->
            runCatching { pv.ensureRuntime(required) { msg -> sm.progress(msg) } }
                .onFailure {
                    CsLog.w("Launch", "Automatic Java $required setup failed: ${it.message}")
                    issues += LaunchIssue(
                        LaunchState.PREPARING_RUNTIME,
                        "Automatic Java $required setup failed: ${it.message}",
                        FixAction.INSTALL_RUNTIME,
                        blocking = false
                    )
                }
        }
        val choice = RuntimeSelector.select(required, rv.meta.usesLwjgl3, runtimes.list(), platform, p.runtimeId)
        val runtime = choice.runtime
        if (runtime == null) {
            issues += LaunchIssue(LaunchState.PREPARING_RUNTIME, choice.reason, FixAction.INSTALL_RUNTIME)
            block(LaunchState.PREPARING_RUNTIME)
        }
        if (choice.compatibility == pro.cslauncher.core.runtime.Compatibility.RISKY) {
            issues += LaunchIssue(LaunchState.PREPARING_RUNTIME, choice.reason, FixAction.INSTALL_RUNTIME, blocking = false)
        }
        if (platform.isAndroid && runtime!!.libjvm() == null) {
            issues += LaunchIssue(
                LaunchState.PREPARING_RUNTIME,
                "The runtime ${runtime.id} has no libjvm.so, so it cannot be loaded on Android",
                FixAction.INSTALL_RUNTIME
            )
            block(LaunchState.PREPARING_RUNTIME)
        }

        // ---------------------------------------------------------------- 3. renderer
        sm.advance(LaunchState.PREPARING_RENDERER)
        provisioner?.let { pv ->
            runCatching { pv.ensureComponents { msg -> sm.progress(msg) } }
                .onFailure {
                    CsLog.w("Launch", "Bundled component setup failed: ${it.message}")
                    issues += LaunchIssue(
                        LaunchState.PREPARING_RENDERER,
                        "Bundled component setup failed: ${it.message}",
                        FixAction.INSTALL_COMPONENT,
                        blocking = false
                    )
                }
        }

        val env = req.environment(rv)
        val coordinates = rv.libraries.map { it.library.coordinate }
        val lwjgl = if (platform.isAndroid) {
            components?.lwjgl(platform.arch.androidAbi!!, LwjglCompat.gameVersion(coordinates))
        } else null

        if (platform.isAndroid) {
            if (!rv.meta.usesLwjgl3) {
                issues += LaunchIssue(
                    LaunchState.PREPARING_RENDERER,
                    "Minecraft ${rv.meta.id} uses LWJGL 2; CS LAUNCHER PRO supports LWJGL 3 versions (1.13+)",
                    FixAction.EDIT_PROFILE
                )
                block(LaunchState.PREPARING_RENDERER)
            }
            if (lwjgl == null) {
                issues += LaunchIssue(
                    LaunchState.PREPARING_RENDERER,
                    "The LWJGL 3 Android component is not installed",
                    FixAction.INSTALL_COMPONENT
                )
                block(LaunchState.PREPARING_RENDERER)
            }
            val bad = components!!.verify(lwjgl!!)
            if (bad.isNotEmpty()) {
                issues += LaunchIssue(
                    LaunchState.PREPARING_RENDERER,
                    "The LWJGL component is damaged: ${bad.first()}",
                    FixAction.INSTALL_COMPONENT
                )
                block(LaunchState.PREPARING_RENDERER)
            }
            LwjglCompat.problem(coordinates, lwjgl.lwjglVersion)?.let {
                issues += LaunchIssue(LaunchState.PREPARING_RENDERER, it, FixAction.EDIT_PROFILE)
                block(LaunchState.PREPARING_RENDERER)
            }
        }

        val memo = p.rendererMemo?.takeIf { it.fingerprint.isNotBlank() }?.let { it.rendererId to it.fingerprint }
        val decision = renderers.decide(env, p.rendererId, p.rendererOptions, memo, req.failedRendererFingerprints)
        if (decision.backend == null || decision.config == null) {
            val missing = decision.statuses.values.any { it.state == RendererState.MISSING_COMPONENT }
            issues += LaunchIssue(
                LaunchState.PREPARING_RENDERER,
                decision.reason,
                if (missing) FixAction.INSTALL_COMPONENT else FixAction.CHOOSE_RENDERER
            )
            block(LaunchState.PREPARING_RENDERER)
        }

        // ---------------------------------------------------------------- 4. plan
        val memory = p.memoryMb
            ?: PerformanceAdvisor.recommendedHeapMb(req.device, modCount(gameDir!!), runtime!!.major)
        if (memory > PerformanceAdvisor.maxHeapMb(req.device)) {
            issues += LaunchIssue(
                LaunchState.PREPARING_RUNTIME,
                "$memory MB exceeds the safe limit of ${PerformanceAdvisor.maxHeapMb(req.device)} MB",
                FixAction.EDIT_PROFILE,
                blocking = false
            )
        }

        val sessionDir = SessionDirs.create(dirs, p.id)
        dirs.createBase()
        installer.materializeResources(rv, gameDir!!)
        val s = session!!

        // The MobileGlues config file path is decided here so the plan's environment points at it.
        val lwjglLibraries = if (platform.isAndroid) lwjgl!!.libraries() else null

        val plan = try {
            planner.plan(
                LaunchInput(
                    profile = p,
                    resolved = rv,
                    runtime = runtime!!,
                    session = s,
                    renderer = decision.config!!,
                    memoryMb = memory,
                    width = req.width,
                    height = req.height,
                    sessionDir = sessionDir,
                    lwjglLibraries = lwjglLibraries,
                    glfwShim = req.glfwShim,
                    launcherVersion = req.launcherVersion,
                    device = req.device
                )
            )
        } catch (e: LaunchPlanException) {
            issues += LaunchIssue(
                LaunchState.PREPARING_RENDERER,
                e.message ?: "Invalid launch configuration",
                FixAction.EDIT_PROFILE
            )
            block(LaunchState.PREPARING_RENDERER)
        }

        FsUtil.writeAtomic(sessionDir.resolve("plan.json"), CsJson.encodeToString(LaunchPlan.serializer(), plan))
        FsUtil.writeText(sessionDir.resolve("command-line.txt"), plan.redactedCommandLine())
        writeResolverConf(sessionDir, platform)

        CsLog.i(
            "Launch",
            "Prepared ${p.name}: ${rv.meta.id}, Java ${runtime.major}, renderer ${decision.backend!!.id} " +
                "(${decision.reason}), ${memory} MB"
        )

        PreparedLaunch(
            plan = plan,
            accessToken = s.accessToken,
            resolved = rv,
            renderer = decision,
            runtime = choice,
            rendererFingerprint = renderers.fingerprint(env, decision.backend.id),
            warnings = issues.filter { !it.blocking }
        )
    }

    /**
     * Android has no `/etc/resolv.conf`, but the Android OpenJDK builds read the path given by
     * `ext.net.resolvPath`. Writing it into the session directory keeps it per-launch and deleteable.
     */
    private fun writeResolverConf(sessionDir: Path, platform: PlatformInfo) {
        if (!platform.isAndroid) return
        val servers = runCatching {
            (1..4).mapNotNull { System.getProperty("net.dns$it") }
        }.getOrDefault(emptyList())
        runCatching {
            FsUtil.writeAtomic(sessionDir.resolve("resolv.conf"), AndroidRuntimeEnv.resolvConf(servers))
        }.onFailure { CsLog.w("Launch", "Could not write resolv.conf: ${it.message}") }
    }

    /**
     * Records the outcome of a launch in the session directory and returns the record the UI stores.
     * A failed launch keeps the renderer fingerprint so the next attempt avoids the same backend.
     */
    fun finish(
        prepared: PreparedLaunch,
        exitCode: Int?,
        monitor: StartupMonitor?,
        failure: String? = null,
        startedAtMs: Long = System.currentTimeMillis()
    ): SessionRecord {
        val reachedReady = monitor?.reachedTitleScreen == true
        val state = when {
            failure != null -> LaunchState.FAILED
            monitor?.crashed == true -> LaunchState.FAILED
            else -> LaunchState.EXITED
        }
        val record = SessionRecord(
            profileId = prepared.plan.profileId,
            versionId = prepared.plan.versionId,
            rendererId = prepared.renderer.backend?.id ?: "unknown",
            state = state,
            failedAt = if (state == LaunchState.FAILED) LaunchState.RUNNING else null,
            milestones = monitor?.milestones?.toList().orEmpty(),
            exitCode = exitCode,
            message = failure ?: monitor?.summary(),
            crashLine = monitor?.crashLine,
            startedAtMs = startedAtMs,
            endedAtMs = System.currentTimeMillis(),
            rendererFingerprint = prepared.rendererFingerprint.takeIf { !reachedReady }
        )
        runCatching {
            FsUtil.writeAtomic(
                Path.of(prepared.plan.sessionDir, "session.json"),
                CsJson.encodeToString(SessionRecord.serializer(), record)
            )
        }.onFailure { CsLog.w("Launch", "Could not write the session record: ${it.message}") }
        return record
    }

    /** Convenience used by the UI when a launch fails before a plan exists. */
    fun reportFailure(stage: LaunchState, message: String) {
        CsLog.e("Launch", "Launch failed at $stage: $message")
    }

    companion object {
        /** A profile-shaped summary for the home screen: "1.20.1 · Fabric 0.15.7 · Java 17". */
        fun describe(profile: Profile, runtime: JavaRuntime?): String {
            val parts = mutableListOf(profile.versionId)
            profile.loader?.let { parts += "${it.type.displayName} ${it.version}".trim() }
            runtime?.let { parts += "Java ${it.major}" }
            return parts.joinToString(" · ")
        }

        fun timestamp(): String =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }
}

/** Small helper so callers do not need to know how accounts are stored. */
fun AccountStore.sessionFor(accountId: String?): LaunchSession {
    val account = accountId?.let { id -> accounts().firstOrNull { it.id == id } } ?: active()
    ?: throw AuthException("No account selected")
    return session(account)
}
