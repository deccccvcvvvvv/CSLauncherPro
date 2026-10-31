package pro.cslauncher.core.renderer

import pro.cslauncher.core.platform.PlatformInfo
import java.nio.file.Path

/** How the native layer must create the GL context for a backend. */
enum class ContextKind { EGL_GLES2, EGL_GLES3, OSMESA, DESKTOP_GL, EGL_DESKTOP }

/**
 * Everything the renderer choice depends on.
 *
 * Kept as a value so the decision is a pure function: the same device, game version and installed
 * libraries always produce the same renderer, and a test can assert that without a GPU.
 */
data class RendererEnvironment(
    val platform: PlatformInfo,
    val androidApi: Int,
    /** e.g. 32 for OpenGL ES 3.2, 0 when the device could not be queried. */
    val glesVersion: Int,
    /** e.g. `1.1.128`, or null when Vulkan is unavailable. */
    val vulkanApi: String?,
    /** Native libraries from verified component packs, by file name. */
    val libraries: Map<String, Path>,
    val gameVersionId: String,
    val gameUsesLwjgl3: Boolean,
    /** Minecraft 1.17+ requires an OpenGL 3.2 core profile. */
    val gameNeedsGl32Core: Boolean,
    /** Minecraft 26.3+ creates an SDL3 window; only the EGL bridge can render into it. */
    val gameUsesSdl: Boolean = false
) {
    /** A GLES level of 0 means "unknown", which must not be read as "ancient". */
    fun hasGles(min: Int): Boolean = glesVersion == 0 || glesVersion >= min

    fun glesLabel(): String =
        if (glesVersion <= 0) "unknown" else "ES ${glesVersion / 10}.${glesVersion % 10}"
}

enum class RendererState { AVAILABLE, MISSING_COMPONENT, DEVICE_UNSUPPORTED, GAME_UNSUPPORTED, NOT_APPLICABLE }

data class RendererStatus(val state: RendererState, val reason: String, val missing: List<String> = emptyList()) {
    val usable: Boolean get() = state == RendererState.AVAILABLE
}

enum class OptionType { BOOL, CHOICE }

data class RendererOption(
    val key: String,
    val label: String,
    val description: String,
    val type: OptionType,
    val default: String,
    val choices: List<String> = emptyList(),
    val experimental: Boolean = false
)

/**
 * The concrete instructions for the native layer: which libraries to load, which environment
 * variables and system properties to set, and any caveat worth showing before launch.
 */
data class RendererConfig(
    val rendererId: String,
    val contextKind: ContextKind,
    val glLibrary: Path?,
    val eglLibrary: Path?,
    val osmesaLibrary: Path?,
    val env: Map<String, String>,
    val jvmProps: Map<String, String>,
    val notes: List<String> = emptyList()
)

interface RendererBackend {
    val id: String
    val displayName: String
    val description: String
    val license: String

    /** Native libraries this backend cannot work without. */
    val requiredLibraries: List<String>

    val options: List<RendererOption>

    fun status(env: RendererEnvironment): RendererStatus

    fun configure(env: RendererEnvironment, options: Map<String, String>): RendererConfig

    /** Only declared options with valid values survive; everything else falls back to the default. */
    fun sanitize(options: Map<String, String>): Map<String, String> = this.options.associate { o ->
        val v = options[o.key]
        o.key to when {
            v == null -> o.default
            o.type == OptionType.BOOL -> if (v == "1" || v == "0") v else o.default
            else -> if (v in o.choices) v else o.default
        }
    }

    fun missingLibs(env: RendererEnvironment): List<String> = requiredLibraries.filterNot { it in env.libraries }

    /** The options actually in effect for [env], shown in the UI so "auto" is never a mystery. */
    fun effectiveOptions(options: Map<String, String>): Map<String, String> = sanitize(options)
}

/**
 * GL4ES (MIT): OpenGL 2.1 → OpenGL ES 2/3 translation. The most compatible choice for
 * Minecraft 1.13 – 1.16.5; it cannot provide the GL 3.2 core profile that 1.17+ needs.
 */
class Gl4esBackend : RendererBackend {
    override val id = "gl4es"
    override val displayName = "GL4ES"
    override val description = "Translates OpenGL 2.1 to OpenGL ES. Most compatible choice for Minecraft 1.13 – 1.16.5."
    override val license = "MIT"
    override val requiredLibraries = listOf("libgl4es.so")
    override val options = listOf(
        RendererOption("LIBGL_ES", "OpenGL ES backend", "GLES version used underneath GL4ES", OptionType.CHOICE, "2", listOf("2", "3")),
        RendererOption("LIBGL_MIPMAP", "Mipmap handling", "0 default, 1 force, 2 disable automipmap, 3 ignore", OptionType.CHOICE, "3", listOf("0", "1", "2", "3")),
        RendererOption("LIBGL_NOERROR", "Skip glGetError", "Faster, but hides GL errors from logs", OptionType.BOOL, "1"),
        RendererOption("LIBGL_SHRINK", "Texture shrink", "Halve large textures to save GPU memory", OptionType.CHOICE, "0", listOf("0", "1", "10", "11")),
        RendererOption("LIBGL_VSYNC", "VSync", "Synchronise buffer swaps with the display", OptionType.BOOL, "0")
    )

    override fun status(env: RendererEnvironment): RendererStatus = when {
        !env.platform.isAndroid -> RendererStatus(RendererState.NOT_APPLICABLE, "Android only")
        !env.gameUsesLwjgl3 -> RendererStatus(
            RendererState.GAME_UNSUPPORTED,
            "Minecraft ${env.gameVersionId} uses LWJGL 2, which needs a compatibility layer that is not included"
        )
        env.gameNeedsGl32Core -> RendererStatus(
            RendererState.GAME_UNSUPPORTED,
            "Minecraft ${env.gameVersionId} requires OpenGL 3.2 core; GL4ES provides 2.1"
        )
        !env.hasGles(20) -> RendererStatus(RendererState.DEVICE_UNSUPPORTED, "Requires OpenGL ES 2.0+")
        missingLibs(env).isNotEmpty() -> RendererStatus(
            RendererState.MISSING_COMPONENT, "Install a GL4ES component pack", missingLibs(env)
        )
        else -> RendererStatus(RendererState.AVAILABLE, "Ready")
    }

    override fun configure(env: RendererEnvironment, options: Map<String, String>): RendererConfig {
        val o = sanitize(options)
        val gl = env.libraries.getValue("libgl4es.so")
        return RendererConfig(
            rendererId = id,
            contextKind = if (o["LIBGL_ES"] == "3") ContextKind.EGL_GLES3 else ContextKind.EGL_GLES2,
            glLibrary = gl,
            eglLibrary = null,
            osmesaLibrary = null,
            env = o + mapOf("LIBGL_GL" to "21", "LIBGL_NOINTOVLHACK" to "1"),
            jvmProps = mapOf("org.lwjgl.opengl.libname" to gl.toString())
        )
    }
}

/**
 * GL4ES running on ANGLE (GLES over Vulkan). The escape hatch for devices whose system GLES driver
 * misrenders — a common failure on older Mali/Adreno blobs.
 */
class Gl4esAngleBackend : RendererBackend {
    override val id = "gl4es-angle"
    override val displayName = "GL4ES on ANGLE (Vulkan)"
    override val description = "GL4ES with Google's ANGLE as the OpenGL ES driver. Try when the system GLES driver misrenders."
    override val license = "MIT (GL4ES), BSD-3-Clause (ANGLE)"
    override val requiredLibraries = listOf("libgl4es.so", "libEGL_angle.so", "libGLESv2_angle.so")
    override val options = listOf(
        RendererOption("LIBGL_NOERROR", "Skip glGetError", "Faster, hides GL errors", OptionType.BOOL, "1")
    )

    override fun status(env: RendererEnvironment): RendererStatus = when {
        !env.platform.isAndroid -> RendererStatus(RendererState.NOT_APPLICABLE, "Android only")
        !env.gameUsesLwjgl3 -> RendererStatus(RendererState.GAME_UNSUPPORTED, "LWJGL 2 versions are not supported")
        env.gameNeedsGl32Core -> RendererStatus(
            RendererState.GAME_UNSUPPORTED, "Requires OpenGL 3.2 core; GL4ES provides 2.1"
        )
        env.vulkanApi == null -> RendererStatus(
            RendererState.DEVICE_UNSUPPORTED, "ANGLE needs Vulkan, which this device does not report"
        )
        missingLibs(env).isNotEmpty() -> RendererStatus(
            RendererState.MISSING_COMPONENT, "Install the GL4ES and ANGLE component packs", missingLibs(env)
        )
        else -> RendererStatus(RendererState.AVAILABLE, "Ready (experimental)")
    }

    override fun configure(env: RendererEnvironment, options: Map<String, String>): RendererConfig {
        val gl = env.libraries.getValue("libgl4es.so")
        val egl = env.libraries.getValue("libEGL_angle.so")
        val gles = env.libraries.getValue("libGLESv2_angle.so")
        return RendererConfig(
            rendererId = id,
            contextKind = ContextKind.EGL_GLES3,
            glLibrary = gl,
            eglLibrary = egl,
            osmesaLibrary = null,
            env = sanitize(options) + mapOf(
                "LIBGL_ES" to "3",
                "LIBGL_GL" to "21",
                "LIBGL_EGL" to egl.toString(),
                "LIBGL_GLES" to gles.toString()
            ),
            jvmProps = mapOf("org.lwjgl.opengl.libname" to gl.toString()),
            notes = listOf("Experimental")
        )
    }
}

/**
 * MobileGlues (LGPL-2.1): a desktop OpenGL 4.x front end over OpenGL ES 3.x with GLSL translation.
 * It owns the EGL front end, so the same library is passed as both the EGL and the GL library.
 * This is the default for Minecraft 1.17+, which needs a GL 3.2 core profile.
 */
class MobileGluesBackend : RendererBackend {
    override val id = "mobileglues"
    override val displayName = "MobileGlues"
    override val description = "OpenGL 4 on top of OpenGL ES 3.x. Recommended for Minecraft 1.17 and newer."
    override val license = "LGPL-2.1 (MobileGlues), Apache-2.0 (SPIRV-Cross), BSD/MIT (glslang)"
    override val requiredLibraries = listOf("libmobileglues.so")

    // Written to MobileGlues' own config.json (pointed at by MG_DIR_PATH) by the game process; see [MobileGluesConfig].
    override val options = listOf(
        RendererOption(
            "errorFilter", "Shader error filter",
            "Ignore GL errors from shader/program checks. Auto = partial for Minecraft 26.3+, which needs it " +
                "(black screen otherwise), off for older versions.",
            OptionType.CHOICE, "auto", listOf("auto", "off", "partial", "full")
        ),
        RendererOption(
            "glVersion", "Reported OpenGL version",
            "Version MobileGlues reports to the game. Auto = 4.0.",
            OptionType.CHOICE, "auto", listOf("auto", "33", "40", "43", "46")
        ),
        RendererOption("glslCache", "Shader cache", "Keep translated shaders (32 MB) so later launches load faster", OptionType.BOOL, "1"),
        RendererOption(
            "dsa", "Direct state access",
            "Expose GL_ARB_direct_state_access (faster on some drivers, may misrender)",
            OptionType.BOOL, "0", experimental = true
        )
    )

    override fun status(env: RendererEnvironment): RendererStatus = when {
        !env.platform.isAndroid -> RendererStatus(RendererState.NOT_APPLICABLE, "Android only")
        !env.gameUsesLwjgl3 -> RendererStatus(RendererState.GAME_UNSUPPORTED, "LWJGL 2 versions are not supported")
        !env.hasGles(30) -> RendererStatus(RendererState.DEVICE_UNSUPPORTED, "Requires OpenGL ES 3.0+, device reports ${env.glesLabel()}")
        missingLibs(env).isNotEmpty() -> RendererStatus(
            RendererState.MISSING_COMPONENT, "Install the MobileGlues component pack", missingLibs(env)
        )
        else -> RendererStatus(
            RendererState.AVAILABLE,
            if (env.glesVersion in 30..31) "Ready (${env.glesLabel()}; ES 3.2 works best)" else "Ready"
        )
    }

    override fun configure(env: RendererEnvironment, options: Map<String, String>): RendererConfig {
        val mg = env.libraries.getValue("libmobileglues.so")
        val o = sanitize(options)
        return RendererConfig(
            rendererId = id,
            contextKind = ContextKind.EGL_DESKTOP,
            glLibrary = mg,
            eglLibrary = mg,
            osmesaLibrary = null,
            env = mapOf(MobileGluesConfig.ENV to MobileGluesConfig.json(o, env.gameUsesSdl)),
            jvmProps = mapOf(
                "org.lwjgl.opengl.libname" to mg.toString(),
                "org.lwjgl.egl.libname" to mg.toString()
            )
        )
    }
}

/**
 * MobileGlues reads `config.json` from `MG_DIR_PATH` (keys and enum values from its
 * `config/config.cpp` and `settings.h`).
 *
 * Without the file it disables error filtering, and Minecraft 26.3+ then renders only black after
 * the loading screen (MobileGlues release notes: "Ignore shader/program error is required to run
 * 26.3-snapshot-3 and later"). The launcher therefore passes the JSON through the plan environment
 * and the game process writes the file before starting the VM.
 */
object MobileGluesConfig {
    const val ENV = "CS_MG_CONFIG"

    /** NoErrorConfig: 0 auto (= none), 1 disable, 2 level 1 (partial), 3 level 2 (full). */
    fun noErrorLevel(choice: String?, gameUsesSdl: Boolean): Int = when (choice) {
        "off" -> 1
        "partial" -> 2
        "full" -> 3
        else -> if (gameUsesSdl) 2 else 0
    }

    fun json(o: Map<String, String>, gameUsesSdl: Boolean): String {
        val gl = o["glVersion"]?.toIntOrNull() ?: 0     // 0 = MobileGlues default (4.0)
        val entries = linkedMapOf(
            "enableANGLE" to 0,                        // ANGLE is not bundled with MobileGlues
            "enableNoError" to noErrorLevel(o["errorFilter"], gameUsesSdl),
            "enableExtComputeShader" to 0,
            "enableExtTimerQuery" to 0,
            "enableExtDirectStateAccess" to if (o["dsa"] == "1") 1 else 0,
            "maxGlslCacheSize" to if (o["glslCache"] == "0") 0 else 32,
            "angleDepthClearFixMode" to 0,
            "customGLVersion" to gl,
            "fsr1Setting" to 0,
            "hideMGEnvLevel" to 0
        )
        return entries.entries.joinToString(",\n  ", "{\n  ", "\n}\n") { (k, v) -> "\"$k\": $v" }
    }
}

/**
 * Mesa Zink (OpenGL 4.x over Vulkan) through an OSMesa off-screen context that the native layer
 * blits into the Android window.
 */
class ZinkBackend : RendererBackend {
    override val id = "zink"
    override val displayName = "Zink (Mesa, Vulkan)"
    override val description = "Mesa's OpenGL-on-Vulkan driver. Provides OpenGL 3.2+ core for Minecraft 1.17 and newer."
    override val license = "MIT (Mesa)"
    override val requiredLibraries = listOf("libOSMesa.so")
    override val options = listOf(
        RendererOption("MESA_GL_VERSION_OVERRIDE", "Reported GL version", "Version string reported to the game", OptionType.CHOICE, "4.6", listOf("3.3", "4.3", "4.6")),
        RendererOption("ZINK_DESCRIPTORS", "Descriptor mode", "Mesa Zink descriptor management", OptionType.CHOICE, "auto", listOf("auto", "lazy", "db"), experimental = true),
        RendererOption("MESA_DEBUG", "Mesa debug output", "Verbose driver messages in the game log", OptionType.BOOL, "0")
    )

    override fun status(env: RendererEnvironment): RendererStatus = when {
        !env.platform.isAndroid -> RendererStatus(RendererState.NOT_APPLICABLE, "Android only")
        !env.gameUsesLwjgl3 -> RendererStatus(RendererState.GAME_UNSUPPORTED, "LWJGL 2 versions are not supported")
        env.gameUsesSdl -> RendererStatus(
            RendererState.GAME_UNSUPPORTED,
            "Minecraft ${env.gameVersionId} uses SDL3 windowing, which the launcher renders through EGL; " +
                "Zink (OSMesa) is not supported there yet. Use MobileGlues."
        )
        env.vulkanApi == null -> RendererStatus(
            RendererState.DEVICE_UNSUPPORTED, "Requires Vulkan 1.1+, which this device does not report"
        )
        !vulkanAtLeast11(env.vulkanApi) -> RendererStatus(
            RendererState.DEVICE_UNSUPPORTED, "Requires Vulkan 1.1+, device reports ${env.vulkanApi}"
        )
        missingLibs(env).isNotEmpty() -> RendererStatus(
            RendererState.MISSING_COMPONENT, "Install a Mesa Zink component pack", missingLibs(env)
        )
        else -> RendererStatus(RendererState.AVAILABLE, "Ready (experimental, driver dependent)")
    }

    private fun vulkanAtLeast11(v: String): Boolean {
        val p = v.split('.').mapNotNull { it.toIntOrNull() }
        return p.getOrElse(0) { 0 } * 100 + p.getOrElse(1) { 0 } >= 101
    }

    override fun configure(env: RendererEnvironment, options: Map<String, String>): RendererConfig {
        val o = sanitize(options).toMutableMap()
        if (o["MESA_DEBUG"] == "0") o.remove("MESA_DEBUG")
        val osm = env.libraries.getValue("libOSMesa.so")
        return RendererConfig(
            rendererId = id,
            contextKind = ContextKind.OSMESA,
            glLibrary = osm,
            eglLibrary = null,
            osmesaLibrary = osm,
            env = o + mapOf(
                "GALLIUM_DRIVER" to "zink",
                "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
                "MESA_GLSL_CACHE_DIR" to ""
            ),
            jvmProps = mapOf("org.lwjgl.opengl.libname" to osm.toString()),
            notes = listOf("Experimental")
        )
    }
}

/** Desktop OpenGL from the host system. Used by the desktop end-to-end test; not applicable on Android. */
class SystemGlBackend : RendererBackend {
    override val id = "system-gl"
    override val displayName = "System OpenGL (desktop)"
    override val description = "Native desktop OpenGL driver. Only for desktop hosts."
    override val license = "System"
    override val requiredLibraries = emptyList<String>()
    override val options = emptyList<RendererOption>()

    override fun status(env: RendererEnvironment): RendererStatus =
        if (env.platform.isAndroid) RendererStatus(RendererState.NOT_APPLICABLE, "Desktop only")
        else RendererStatus(RendererState.AVAILABLE, "Ready")

    override fun configure(env: RendererEnvironment, options: Map<String, String>): RendererConfig =
        RendererConfig(id, ContextKind.DESKTOP_GL, null, null, null, emptyMap(), emptyMap())
}

data class RendererDecision(
    val backend: RendererBackend?,
    val config: RendererConfig?,
    val reason: String,
    val statuses: Map<String, RendererStatus>,
    val fromMemory: Boolean = false
)

/**
 * The set of renderers, and the policy for choosing one.
 *
 * Rules that keep the failure modes honest:
 * * an explicit profile choice is either honoured or rejected with a reason — never silently swapped,
 *   because a user debugging a renderer problem needs to know their setting was respected;
 * * automatic mode only reuses the remembered renderer when the **fingerprint** (device, driver
 *   libraries, game version) is unchanged, so a driver or version change re-decides;
 * * a renderer that already failed for this exact fingerprint is skipped, which is what stops the
 *   classic crash loop;
 * * if every compatible renderer has failed, the preferred one is retried with an explanation rather
 *   than leaving the user with a dead end.
 */
class RendererRegistry(
    val backends: List<RendererBackend> = listOf(
        SystemGlBackend(), MobileGluesBackend(), Gl4esBackend(), ZinkBackend(), Gl4esAngleBackend()
    )
) {
    fun byId(id: String): RendererBackend? = backends.firstOrNull { it.id == id }

    fun statuses(env: RendererEnvironment): Map<String, RendererStatus> = backends.associate { it.id to it.status(env) }

    fun fingerprint(env: RendererEnvironment, backendId: String): String = listOf(
        backendId,
        env.gameVersionId,
        env.platform.arch.name,
        env.androidApi,
        env.glesVersion,
        env.vulkanApi ?: "-",
        env.libraries.entries.sortedBy { it.key }
            .joinToString(",") { it.key + ":" + runCatching { java.nio.file.Files.size(it.value) }.getOrDefault(0L) }
    ).joinToString("|")

    fun decide(
        env: RendererEnvironment,
        requested: String,
        options: Map<String, String>,
        memo: Pair<String, String>?,
        failedFingerprints: Set<String>
    ): RendererDecision {
        val st = statuses(env)

        if (requested != "auto") {
            val b = byId(requested) ?: return RendererDecision(null, null, "Unknown renderer '$requested'", st)
            val s = st.getValue(b.id)
            return if (s.usable) {
                RendererDecision(b, b.configure(env, options), "Selected by the profile", st)
            } else {
                RendererDecision(null, null, "${b.displayName} is unavailable: ${s.reason}", st)
            }
        }

        if (memo != null) {
            val (id, fp) = memo
            val b = byId(id)
            if (b != null && st[id]?.usable == true && fp == fingerprint(env, id) && fp !in failedFingerprints) {
                return RendererDecision(b, b.configure(env, options), "Last successful renderer for this setup", st, fromMemory = true)
            }
        }

        val order = if (env.gameNeedsGl32Core) {
            listOf("system-gl", "mobileglues", "zink", "gl4es", "gl4es-angle")
        } else {
            listOf("system-gl", "gl4es", "mobileglues", "gl4es-angle", "zink")
        }
        for (id in order) {
            val b = byId(id) ?: continue
            if (st[id]?.usable == true && fingerprint(env, id) !in failedFingerprints) {
                return RendererDecision(b, b.configure(env, options), "Automatically selected", st)
            }
        }

        // Every compatible backend failed before on this setup. Blocking would be a dead end: the earlier
        // failure may not have been the renderer's fault. Retry the preferred one and say so.
        order.firstOrNull { st[it]?.usable == true }?.let { id ->
            val b = byId(id)!!
            return RendererDecision(
                b, b.configure(env, options),
                "Retrying ${b.displayName}: it failed on an earlier launch, and no other compatible renderer is available",
                st
            )
        }

        val why = st.entries.filter { it.value.state != RendererState.NOT_APPLICABLE }
            .joinToString("; ") { "${byId(it.key)?.displayName}: ${it.value.reason}" }
        return RendererDecision(null, null, "No compatible renderer. $why", st)
    }

    /** What the settings screen lists: every backend with its state for this device. */
    fun describe(env: RendererEnvironment): List<Triple<RendererBackend, RendererStatus, List<RendererOption>>> =
        backends.map { Triple(it, it.status(env), it.options) }
}
