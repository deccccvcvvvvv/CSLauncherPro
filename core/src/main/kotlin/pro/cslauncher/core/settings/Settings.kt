package pro.cslauncher.core.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path

@Serializable
enum class ThemeChoice { MINECRAFT_LAUNCHER, PIXEL, DARK_GRAPHITE, GLASSMORPHISM }

@Serializable
enum class CursorMode { DESKTOP, TOUCHPAD, IMMERSIVE, SMART }

@Serializable
enum class CursorShape { ARROW, CIRCLE, CROSSHAIR, DOT, HAND, IBEAM, RESIZE_NS, RESIZE_EW, RESIZE_ALL, NOT_ALLOWED }

@Serializable
enum class AudioSourceChoice { NONE, MICROPHONE, GAME_AUDIO, GAME_AND_MICROPHONE }

@Serializable
enum class PerformancePreset { BATTERY_SAVER, BALANCED, PERFORMANCE, CUSTOM }

@Serializable
enum class RecordingQuality { LOW, BALANCED, HIGH, CUSTOM }

@Serializable
enum class RecordingResolution { P480, P720, P1080, NATIVE }

@Serializable
enum class TapAction { NONE, MOUSE_LEFT, MOUSE_RIGHT }

@Serializable
data class TouchSettings(
    val lookSensitivityX: Float = 1.0f,
    val lookSensitivityY: Float = 1.0f,
    val invertY: Boolean = false,
    val smoothing: Float = 0f,
    val acceleration: Float = 0f,
    val tapAction: TapAction = TapAction.MOUSE_RIGHT,
    val longPressBreak: Boolean = true,
    val longPressMs: Int = 320,
    val hideOverlayWithPhysicalInput: Boolean = true,
    val vibrateOnPress: Boolean = false,
    val safeAreaPaddingDp: Float = 8f,
    /** Global multipliers applied on top of every control's own opacity/size (live from the in-game menu). */
    val controlsOpacity: Float = 1f,
    val controlsScale: Float = 1f,
    val showMenuHandle: Boolean = true,
    val showFpsHud: Boolean = false
)

@Serializable
data class CursorSettings(
    val mode: CursorMode = CursorMode.SMART,
    val shape: CursorShape = CursorShape.ARROW,
    val sizeDp: Float = 22f,
    val opacity: Float = 0.95f,
    val colorArgb: Long = 0xFFF0F1F5,
    val sensitivity: Float = 1.0f,
    val smoothing: Float = 0f,
    val clickAnimation: Boolean = true,
    val trail: Boolean = false,
    val selectionHighlight: Boolean = true,
    val externalMouse: Boolean = true
)

@Serializable
data class TouchpadConfig(
    val sizeFraction: Float = 0.45f,
    val deadZone: Float = 0.02f,
    val sensitivity: Float = 1.2f,
    val tapToClick: Boolean = true,
    val twoFingerScroll: Boolean = true
)

@Serializable
data class RecordingSettings(
    val resolution: RecordingResolution = RecordingResolution.P720,
    val fps: Int = 30,
    val quality: RecordingQuality = RecordingQuality.BALANCED,
    val customBitrateKbps: Int = 6000,
    val preferHevc: Boolean = false,
    val audio: AudioSourceChoice = AudioSourceChoice.GAME_AUDIO,
    val saveToGallery: Boolean = false,
    val showOverlay: Boolean = true,
    val overlayX: Float = 0.02f,
    val overlayY: Float = 0.08f,
    val hideControls: Boolean = true
)

data class ValidationProblem(val field: String, val message: String)

/** A virtual mouse exists whenever the cursor is not permanently captured by touch look. */
val CursorSettings.pointerOnTouch: Boolean get() = mode != CursorMode.IMMERSIVE

fun CursorSettings.withVirtualMouse(on: Boolean): CursorSettings =
    if (on) copy(mode = CursorMode.SMART) else copy(mode = CursorMode.IMMERSIVE)

fun CursorSettings.withPointerOnTouch(show: Boolean): CursorSettings =
    if (show) copy(mode = CursorMode.SMART) else copy(mode = CursorMode.IMMERSIVE)

/**
 * All launcher settings.
 *
 * Every field has a working default so a fresh install is usable without opening the settings
 * screen, and the schema version lets a future release migrate rather than reset a user's choices.
 */
@Serializable
data class LauncherSettings(
    val schemaVersion: Int = SCHEMA,
    val theme: ThemeChoice = ThemeChoice.MINECRAFT_LAUNCHER,
    val animatedBackground: Boolean = false,
    val fullscreenLauncher: Boolean = true,
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = true,
    val uiClickSounds: Boolean = true,
    val downloadConcurrency: Int = 6,
    val downloadAttempts: Int = 3,
    val showSnapshots: Boolean = false,
    val showHistorical: Boolean = false,
    val recentActivityEnabled: Boolean = true,
    val defaultRenderer: String = "auto",
    val defaultMemoryMb: Int? = null,
    val defaultRuntimeId: String? = null,
    val defaultJvmArgs: String = "",
    val defaultFullscreen: Boolean = false,
    val defaultResolutionWidth: Int? = null,
    val defaultResolutionHeight: Int? = null,
    val startupScreen: String = "HOME",
    val performancePreset: PerformancePreset = PerformancePreset.BALANCED,
    val frameRateLimit: Int = 0,
    val touch: TouchSettings = TouchSettings(),
    val cursor: CursorSettings = CursorSettings(),
    val recording: RecordingSettings = RecordingSettings(),
    val msaClientId: String = "",
    val verboseDiagnostics: Boolean = false,
    val keepLauncherLogsDays: Int = 14
) {
    companion object {
        const val SCHEMA = 3

        /**
         * Schema upgrade. A migration must never lose a user's choices: only fields whose meaning
         * changed are rewritten, everything else is carried over untouched.
         */
        fun migrate(s: LauncherSettings): LauncherSettings {
            if (s.schemaVersion >= SCHEMA) return s
            var out = s
            if (s.schemaVersion < 2) {
                // v2 introduced per-field validation; clamp values that used to be free-form.
                out = out.copy(
                    downloadConcurrency = s.downloadConcurrency.coerceIn(1, 16),
                    downloadAttempts = s.downloadAttempts.coerceIn(1, 8)
                )
            }
            if (s.schemaVersion < 3) {
                // v3 moved the old default pixel UI to the clean launcher theme.
                val wasPixelDefault = s.theme == ThemeChoice.PIXEL
                out = out.copy(
                    theme = if (wasPixelDefault) ThemeChoice.MINECRAFT_LAUNCHER else s.theme,
                    animatedBackground = if (wasPixelDefault) false else s.animatedBackground,
                    reduceMotion = if (wasPixelDefault) true else s.reduceMotion
                )
            }
            return out.copy(schemaVersion = SCHEMA)
        }

        /** Clamps anything a text field or an old file could contain into a usable range. */
        fun sanitize(s: LauncherSettings): LauncherSettings = s.copy(
            schemaVersion = SCHEMA,
            downloadConcurrency = s.downloadConcurrency.coerceIn(1, 16),
            downloadAttempts = s.downloadAttempts.coerceIn(1, 8),
            defaultMemoryMb = s.defaultMemoryMb?.coerceIn(512, 65536),
            defaultResolutionWidth = s.defaultResolutionWidth?.coerceIn(320, 7680),
            defaultResolutionHeight = s.defaultResolutionHeight?.coerceIn(240, 4320),
            frameRateLimit = s.frameRateLimit.coerceIn(0, 240),
            keepLauncherLogsDays = s.keepLauncherLogsDays.coerceIn(1, 365),
            touch = s.touch.copy(
                lookSensitivityX = s.touch.lookSensitivityX.coerceIn(0.1f, 4f),
                lookSensitivityY = s.touch.lookSensitivityY.coerceIn(0.1f, 4f),
                smoothing = s.touch.smoothing.coerceIn(0f, 1f),
                acceleration = s.touch.acceleration.coerceIn(0f, 1f),
                longPressMs = s.touch.longPressMs.coerceIn(120, 1500),
                controlsOpacity = s.touch.controlsOpacity.coerceIn(0.1f, 1f),
                controlsScale = s.touch.controlsScale.coerceIn(0.5f, 2f),
                safeAreaPaddingDp = s.touch.safeAreaPaddingDp.coerceIn(0f, 64f)
            ),
            cursor = s.cursor.copy(
                sizeDp = s.cursor.sizeDp.coerceIn(8f, 64f),
                opacity = s.cursor.opacity.coerceIn(0.1f, 1f),
                sensitivity = s.cursor.sensitivity.coerceIn(0.2f, 5f),
                smoothing = s.cursor.smoothing.coerceIn(0f, 1f)
            ),
            recording = s.recording.copy(
                fps = s.recording.fps.coerceIn(15, 60),
                customBitrateKbps = s.recording.customBitrateKbps.coerceIn(500, 120_000),
                overlayX = s.recording.overlayX.coerceIn(0f, 1f),
                overlayY = s.recording.overlayY.coerceIn(0f, 1f)
            )
        )
    }
}

/**
 * Settings with an atomic, observable store.
 *
 * Two processes write here (the launcher and, while a game runs, the in-game menu), so every save is
 * atomic and [reload] exists for the other process to pick up a change. A corrupt file is preserved
 * as a `.corrupt-<timestamp>` copy instead of being silently overwritten: losing a user's settings
 * without a trace is unacceptable.
 */
class SettingsStore(private val file: Path) {

    private val _state = MutableStateFlow(read())
    val state: StateFlow<LauncherSettings> = _state.asStateFlow()

    val current: LauncherSettings get() = _state.value

    private fun read(): LauncherSettings = runCatching {
        if (!Files.isRegularFile(file)) {
            LauncherSettings()
        } else {
            LauncherSettings.sanitize(
                LauncherSettings.migrate(CsJson.decodeFromString(LauncherSettings.serializer(), FsUtil.readText(file)))
            )
        }
    }.getOrElse { e ->
        CsLog.w("Settings", "Settings unreadable, keeping a copy and using defaults: ${e.message}")
        runCatching {
            Files.copy(file, file.resolveSibling("settings.corrupt-${System.currentTimeMillis()}.json"))
        }
        LauncherSettings()
    }

    /** Re-reads the file: the launcher and the game process each hold a store and both may save. */
    @Synchronized
    fun reload() {
        _state.value = read()
    }

    @Synchronized
    fun update(transform: (LauncherSettings) -> LauncherSettings) {
        val next = LauncherSettings.sanitize(transform(_state.value)).copy(schemaVersion = LauncherSettings.SCHEMA)
        FsUtil.writeAtomic(file, CsJson.encodeToString(LauncherSettings.serializer(), next))
        _state.value = next
    }

    /** Exports the settings that a bug report should contain (no tokens, no file paths). */
    fun exportForReport(): String {
        val s = _state.value
        return buildJsonObject {
            put("schemaVersion", s.schemaVersion)
            put("theme", s.theme.name)
            put("renderer", s.defaultRenderer)
            put("memoryMb", s.defaultMemoryMb ?: -1)
            put("performancePreset", s.performancePreset.name)
            put("downloadConcurrency", s.downloadConcurrency)
            put("reduceMotion", s.reduceMotion)
            putJsonObject("touch") {
                put("opacity", s.touch.controlsOpacity)
                put("scale", s.touch.controlsScale)
            }
            putJsonObject("cursor") { put("mode", s.cursor.mode.name) }
        }.toString()
    }

    /** True when nothing has been written yet, so the UI can show first-run defaults honestly. */
    fun exists(): Boolean = Files.isRegularFile(file)
}
