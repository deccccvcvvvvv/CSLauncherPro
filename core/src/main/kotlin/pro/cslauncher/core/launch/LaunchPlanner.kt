package pro.cslauncher.core.launch

import pro.cslauncher.core.account.LaunchSession
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.install.ResolvedVersion
import pro.cslauncher.core.meta.ArgumentEntry
import pro.cslauncher.core.meta.LaunchFeatures
import pro.cslauncher.core.meta.Library
import pro.cslauncher.core.meta.MavenCoordinate
import pro.cslauncher.core.perf.DeviceFacts
import pro.cslauncher.core.perf.PerformanceAdvisor
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.profile.JvmArgs
import pro.cslauncher.core.profile.Profile
import pro.cslauncher.core.renderer.RendererConfig
import pro.cslauncher.core.runtime.JavaRuntime
import pro.cslauncher.core.runtime.RuntimeSource
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class LaunchInput(
    val profile: Profile,
    val resolved: ResolvedVersion,
    val runtime: JavaRuntime,
    val session: LaunchSession,
    val renderer: RendererConfig,
    val memoryMb: Int,
    val width: Int,
    val height: Int,
    val sessionDir: Path,
    /** Native libraries from the LWJGL component pack (Android), null on desktop. */
    val lwjglLibraries: Map<String, Path>? = null,
    /** A GLFW shim the native layer needs on Android, when one is required. */
    val glfwShim: Path? = null,
    val launcherVersion: String = "1.0.0",
    val device: DeviceFacts = DeviceFacts.desktop(),
    /** Extra feature flags for argument rules (demo mode, quick play…). */
    val features: LaunchFeatures = LaunchFeatures(),
    /** Written to `MG_DIR_PATH`'s directory by the game process; already serialised by the renderer. */
    val rendererEnvFromConfig: Boolean = true
)

/**
 * Turns a resolved version, a runtime, a session and a renderer decision into a [LaunchPlan].
 *
 * This is the file that must be *right*: a wrong template placeholder, a classpath entry that is not
 * absolute or a missing native directory all end in a JVM that dies before printing anything useful.
 * Everything here is therefore explicit and validated, and the produced plan is written next to the
 * log so a bug report can be reproduced exactly.
 */
class LaunchPlanner(private val dirs: LauncherDirs, private val platform: PlatformInfo) {

    fun plan(input: LaunchInput): LaunchPlan {
        val p = input.profile
        val meta = input.resolved.meta
        val runtime = input.runtime
        val gameDir = safeGameDir(p)
        val unresolved = mutableSetOf<String>()

        // ---------------------------------------------------------------- classpath
        val classpath = buildList {
            // Loader jars first: Forge/NeoForge bootstrap classes must win over the vanilla ones.
            addAll(input.resolved.classpathFiles.map { it.toAbsolutePath().toString() })
            // Android LWJGL component jars replace the desktop ones the metadata lists.
            input.lwjglLibraries?.values?.sortedBy { it.fileName.toString() }
                ?.filter { it.fileName.toString().endsWith(".jar") }
                ?.forEach { add(it.toAbsolutePath().toString()) }
            // Forge needs the client jar under a second name; harmless for vanilla and every other loader.
            add(dirs.versionJar(input.resolved.jarVersionId).toAbsolutePath().toString())
        }.distinct()

        // ---------------------------------------------------------------- natives
        val nativeDirs = buildList {
            if (!platform.isAndroid) add(dirs.nativesDir(meta.id).toAbsolutePath().toString())
            input.lwjglLibraries?.values?.map { it.toAbsolutePath().parent.toString() }?.distinct()?.forEach { add(it) }
            input.renderer.glLibrary?.toAbsolutePath()?.parent?.toString()?.let { add(it) }
            input.renderer.eglLibrary?.toAbsolutePath()?.parent?.toString()?.let { add(it) }
            input.renderer.osmesaLibrary?.toAbsolutePath()?.parent?.toString()?.let { add(it) }
            input.glfwShim?.toAbsolutePath()?.parent?.toString()?.let { add(it) }
        }.distinct()

        // ---------------------------------------------------------------- JVM arguments
        val jvmArgs = mutableListOf<String>()
        jvmArgs += PerformanceAdvisor.jvmTuning(input.device, input.memoryMb, runtime.major)
        jvmArgs += "-Djava.library.path=" + nativeDirs.joinToString(":")
        jvmArgs += "-Dminecraft.launcher.brand=CS LAUNCHER PRO"
        jvmArgs += "-Dminecraft.launcher.version=${input.launcherVersion}"
        jvmArgs += "-Dfml.ignoreInvalidMinecraftCertificates=true"
        jvmArgs += "-Dfml.ignorePatchDiscrepancies=true"
        jvmArgs += "-Djna.tmpdir=${input.sessionDir.resolve("tmp").toAbsolutePath()}"
        jvmArgs += "-Dio.netty.native.workdir=${input.sessionDir.resolve("tmp").toAbsolutePath()}"

        val placeholderValues = placeholderValues(input, gameDir).toMutableMap()

        // Version-provided JVM arguments (with rules), then the user's own flags on top.
        val versionJvm = ArgumentBuilder.expand(meta.jvmArguments, platform, input.features)
            .map { ArgumentBuilder.substitute(it, placeholderValues, unresolved) }
        jvmArgs += versionJvm

        // Renderer / runtime properties from the decision.
        input.renderer.jvmProps.forEach { (k, v) -> jvmArgs += "-D$k=$v" }
        if (platform.isAndroid && runtime.libjvm() != null) {
            val rtEnv = pro.cslauncher.core.launch.AndroidRuntimeEnv.build(
                libjvm = runtime.libjvm()!!.toAbsolutePath().toString(),
                javaMajor = runtime.major,
                nativeDirs = nativeDirs,
                rendererLibraries = rendererLibraries(input.renderer),
                sessionTmp = input.sessionDir.resolve("tmp").toAbsolutePath().toString(),
                gameDir = gameDir.toAbsolutePath().toString(),
                resolvConf = input.sessionDir.resolve("resolv.conf").toAbsolutePath().toString(),
                timeZoneId = java.util.TimeZone.getDefault().id,
                androidRelease = System.getProperty("os.version").orEmpty().ifBlank { "unknown" },
                existingJvmArgs = jvmArgs
            )
            jvmArgs += rtEnv.jvmOptions
        }

        // User flags last so they can override the launcher's defaults, minus the ones the launcher owns.
        jvmArgs += JvmArgs.stripLauncherOwned(p.jvmArgumentList())

        // One-token form is mandatory for JNI_CreateJavaVM; harmless bookkeeping otherwise.
        val normalized = JvmOptionNormalizer.normalize(jvmArgs)

        // ---------------------------------------------------------------- game arguments
        val versionGame = if (meta.usesModernArguments) {
            ArgumentBuilder.expand(meta.gameArguments, platform, input.features)
        } else {
            ArgumentBuilder.expandLegacy(meta.legacyArguments.orEmpty(), platform, input.features)
        }
        val gameArgs = mutableListOf<String>()
        gameArgs += versionGame.map { ArgumentBuilder.substitute(it, placeholderValues, unresolved) }
        gameArgs += p.gameArgumentList()

        if (unresolved.isNotEmpty()) {
            CsLog.w("Launch", "Unresolved argument placeholders: ${unresolved.joinToString()}")
        }

        val logFile = input.sessionDir.resolve("game.log")
        val plan = LaunchPlan(
            profileId = p.id,
            profileName = p.name,
            versionId = meta.id,
            mainClass = meta.mainClass,
            javaHome = runtime.javaHome.toAbsolutePath().toString(),
            javaMajor = runtime.major,
            javaExecutable = runtime.javaExecutable.toAbsolutePath().toString(),
            libjvm = runtime.libjvm()?.toAbsolutePath()?.toString(),
            classpath = classpath,
            nativeDirs = nativeDirs,
            jvmArgs = normalized.options,
            gameArgs = gameArgs,
            workingDir = gameDir.toAbsolutePath().toString(),
            env = buildEnv(input, gameDir, nativeDirs, placeholderValues),
            rendererId = input.renderer.rendererId,
            rendererContext = input.renderer.contextKind.name,
            rendererLibraries = rendererLibraries(input.renderer),
            controlLayoutId = p.controlLayoutId,
            sessionDir = input.sessionDir.toAbsolutePath().toString(),
            logFile = logFile.toAbsolutePath().toString(),
            width = input.width,
            height = input.height,
            preloadLibraries = if (platform.isAndroid && runtime.libjvm() != null) {
                androidPreload(runtime, nativeDirs)
            } else emptyList(),
            fullAccessTokenPresent = input.session.accessToken.isNotBlank()
        )

        val problems = plan.validate()
        if (problems.isNotEmpty()) throw LaunchPlanException(problems.joinToString("; "))
        return plan
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Every placeholder Mojang's metadata can contain. A missing one is recorded as unresolved and
     * logged, never silently substituted with a wrong value.
     */
    fun placeholderValues(input: LaunchInput, gameDir: Path): Map<String, String> {
        val s = input.session
        val p = input.profile
        val v = linkedMapOf(
            "auth_player_name" to s.username,
            "version_name" to input.resolved.meta.id,
            "game_directory" to gameDir.toAbsolutePath().toString(),
            "assets_root" to dirs.assets.toAbsolutePath().toString(),
            "game_assets" to dirs.assets.resolve("virtual").resolve(input.resolved.meta.assetIndex?.id ?: "legacy")
                .toAbsolutePath().toString(),
            "assets_index_name" to (input.resolved.meta.assetIndex?.id ?: "legacy"),
            "auth_uuid" to s.uuid,
            "auth_access_token" to s.accessToken,
            "clientid" to s.clientId,
            "auth_xuid" to s.xuid,
            "user_type" to s.userType,
            "user_properties" to "{}",
            "version_type" to (input.resolved.meta.type.name.lowercase()),
            "resolution_width" to input.width.toString(),
            "resolution_height" to input.height.toString(),
            "natives_directory" to dirs.nativesDir(input.resolved.meta.id).toAbsolutePath().toString(),
            "launcher_name" to "CS LAUNCHER PRO",
            "launcher_version" to input.launcherVersion,
            "classpath" to input.resolved.classpathFiles.joinToString(":") { it.toAbsolutePath().toString() },
            "classpath_separator" to ":",
            "library_directory" to dirs.libraries.toAbsolutePath().toString(),
            "primary_jar" to dirs.versionJar(input.resolved.jarVersionId).toAbsolutePath().toString(),
            "library_search_path" to dirs.libraries.toAbsolutePath().toString(),
            "quickPlayPath" to input.sessionDir.resolve("quickplay.log").toAbsolutePath().toString(),
            "quickPlaySingleplayer" to p.gameArgs.substringAfter("--quickPlaySingleplayer", "").trim()
                .substringBefore(' '),
            "quickPlayMultiplayer" to p.gameArgs.substringAfter("--quickPlayMultiplayer", "").trim()
                .substringBefore(' ')
        )
        // Profile-specific overrides of the assets root (a custom game dir may carry its own).
        if (p.env.containsKey("CS_ASSETS_ROOT")) v["assets_root"] = p.env.getValue("CS_ASSETS_ROOT")
        return v.filterValues { true }
    }

    private fun buildEnv(
        input: LaunchInput,
        gameDir: Path,
        nativeDirs: List<String>,
        placeholders: Map<String, String>
    ): Map<String, String> {
        val env = linkedMapOf<String, String>()
        env.putAll(input.renderer.env)
        env["HOME"] = gameDir.toAbsolutePath().toString()
        env["TMPDIR"] = input.sessionDir.resolve("tmp").toAbsolutePath().toString()
        if (nativeDirs.isNotEmpty()) env["LD_LIBRARY_PATH"] = nativeDirs.joinToString(":")
        env["CS_LAUNCHER_VERSION"] = input.launcherVersion
        env["CS_SESSION_DIR"] = input.sessionDir.toAbsolutePath().toString()
        env["CS_RENDERER"] = input.renderer.rendererId
        env["CS_CONTROL_LAYOUT"] = input.profile.controlLayoutId
        env["CS_ASSETS"] = placeholders["assets_root"].orEmpty()
        // RENDERER CONFIG: MobileGlues reads a file, so the path is prepared here.
        input.renderer.env[pro.cslauncher.core.renderer.MobileGluesConfig.ENV]?.let { json ->
            val configDir = input.sessionDir.resolve("mobileglues")
            runCatching {
                Files.createDirectories(configDir)
                FsUtil.writeAtomic(configDir.resolve("config.json"), json)
            }.onFailure { CsLog.w("Launch", "Could not write the MobileGlues config: ${it.message}") }
            env["MG_DIR_PATH"] = configDir.toAbsolutePath().toString()
        }
        // Profile environment last: explicit user intent wins over everything the launcher inferred.
        env.putAll(input.profile.env)
        return env
    }

    /** Absolute paths of the libraries that must be preloaded before the JVM starts (Android only). */
    private fun androidPreload(runtime: JavaRuntime, nativeDirs: List<String>): List<String> {
        val libjvm = runtime.libjvm() ?: return emptyList()
        val env = AndroidRuntimeEnv.build(
            libjvm = libjvm.toAbsolutePath().toString(),
            javaMajor = runtime.major,
            nativeDirs = nativeDirs,
            rendererLibraries = emptyMap(),
            sessionTmp = "",
            gameDir = "",
            resolvConf = "",
            timeZoneId = "",
            androidRelease = "",
            existingJvmArgs = emptyList()
        )
        return env.preload.map { it.first }
    }

    private fun rendererLibraries(config: RendererConfig): Map<String, String> = buildMap {
        config.glLibrary?.let { put("gl", it.toAbsolutePath().toString()) }
        config.eglLibrary?.let { put("egl", it.toAbsolutePath().toString()) }
        config.osmesaLibrary?.let { put("osmesa", it.toAbsolutePath().toString()) }
    }

    private fun safeGameDir(p: Profile): Path = try {
        p.gameDir(dirs)
    } catch (e: Exception) {
        throw LaunchPlanException("Game directory is not configured: ${e.message}")
    }
}

/**
 * The launch session directory: `<sessions>/<yyyyMMdd-HHmmss>-<profile>`. One directory per launch
 * means a log, a plan, a crash dump and a screenshot set can never be confused with an earlier run.
 */
object SessionDirs {
    fun create(dirs: LauncherDirs, profileId: String): Path {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        var candidate = dirs.sessions.resolve("$stamp-${profileId.take(8)}")
        var n = 1
        while (Files.exists(candidate)) {
            candidate = dirs.sessions.resolve("$stamp-${profileId.take(8)}-${n++}")
        }
        Files.createDirectories(candidate.resolve("tmp"))
        return candidate
    }
}

/** Convenience for the UI: the mod count that feeds the heap recommendation. */
fun modCount(gameDir: Path): Int = runCatching {
    val mods = gameDir.resolve("mods")
    if (!Files.isDirectory(mods)) 0
    else Files.list(mods).use { s -> s.filter { it.fileName.toString().endsWith(".jar") }.count().toInt() }
}.getOrDefault(0)

/** Library list → Maven coordinates, for the LWJGL compatibility checks. */
fun ResolvedVersion.coordinates(): List<MavenCoordinate> = libraries.mapNotNull { it.library.coordinate }

/** True when the game's classpath already contains a loader, so mods are expected. */
fun ResolvedVersion.hasLoader(): Boolean = libraries.any {
    it.library.coordinate.group in setOf("net.fabricmc", "org.quiltmc", "net.minecraftforge", "net.neoforged")
}

internal fun Library.describeForLog(): String = "$name${if (isNativeOnly) " (native)" else ""}"
