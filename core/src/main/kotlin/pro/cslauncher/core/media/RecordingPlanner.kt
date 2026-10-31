package pro.cslauncher.core.media

import pro.cslauncher.core.settings.AudioSourceChoice
import pro.cslauncher.core.settings.RecordingQuality
import pro.cslauncher.core.settings.RecordingResolution
import pro.cslauncher.core.settings.RecordingSettings

/**
 * One hardware/software encoder as reported by the platform. [maxFps] is a function because an
 * encoder's achievable frame rate depends on the frame size, which is exactly the trade-off the
 * planner has to resolve.
 */
data class EncoderCapability(
    val mime: String,
    val name: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val widthAlignment: Int,
    val heightAlignment: Int,
    val bitrateRange: IntRange,
    val maxFps: (Int, Int) -> Double?
)

data class AudioPlan(
    val source: AudioSourceChoice,
    val sampleRate: Int = 44_100,
    val channels: Int = 2,
    val bitrate: Int = 128_000
)

data class RecordingPlan(
    val mime: String,
    val encoder: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val audio: AudioPlan,
    val warnings: List<String>
) {
    val resolutionLabel: String get() = "${width}x$height"
    val bitrateLabel: String get() = "${bitrate / 1000} kbps"
}

class RecordingUnsupportedException(message: String) : Exception(message)

/**
 * Chooses an encoder, a frame size, a frame rate and a bitrate that the device can actually sustain.
 *
 * The failure this exists to prevent: a recording that looks fine in settings but produces a stuttering,
 * unwatchable file, or crashes the encoder in the middle of a session. So the planner walks *down* from
 * the requested resolution until the encoder both accepts the size and reports an achievable frame
 * rate, and every compromise is returned as a warning the UI shows **before** recording starts.
 */
object RecordingPlanner {

    const val AVC = "video/avc"
    const val HEVC = "video/hevc"

    fun plan(
        s: RecordingSettings,
        surfaceW: Int,
        surfaceH: Int,
        encoders: List<EncoderCapability>,
        androidApi: Int,
        micPermission: Boolean
    ): RecordingPlan {
        val warnings = mutableListOf<String>()

        val wantHevc = s.preferHevc && encoders.any { it.mime == HEVC && it.hardware }
        if (s.preferHevc && !wantHevc) warnings += "No HEVC hardware encoder; recording in H.264"

        val pool = encoders
            .filter { it.mime == (if (wantHevc) HEVC else AVC) }
            .sortedByDescending { it.hardware }
        val enc = pool.firstOrNull()
            ?: encoders.firstOrNull { it.mime == AVC }
            ?: throw RecordingUnsupportedException("No H.264 or HEVC video encoder is available on this device")
        if (!enc.hardware) {
            warnings += "Only a software encoder is available; recording will reduce game performance noticeably"
        }

        // ---------------------------------------------------------------- frame size
        val landscape = surfaceW >= surfaceH
        val long0 = maxOf(surfaceW, surfaceH)
        val short0 = minOf(surfaceW, surfaceH)
        val targetShort = when (s.resolution) {
            RecordingResolution.P480 -> 480
            RecordingResolution.P720 -> 720
            RecordingResolution.P1080 -> 1080
            RecordingResolution.NATIVE -> short0
        }.coerceAtMost(short0)

        var shortSide = targetShort
        var width = 0
        var height = 0
        var fps = s.fps.coerceIn(15, 60)

        while (shortSide >= 240) {
            val longSide = (long0.toLong() * shortSide / short0).toInt()
            val w = align(if (landscape) longSide else shortSide, maxOf(16, enc.widthAlignment))
            val h = align(if (landscape) shortSide else longSide, maxOf(16, enc.heightAlignment))
            if (w <= enc.maxWidth && h <= enc.maxHeight) {
                val achievable = enc.maxFps(w, h)
                if (achievable != null) {
                    if (achievable + 0.5 < fps) {
                        // Prefer dropping to 30 FPS over dropping resolution when 30 is sustainable.
                        if (fps > 30 && achievable >= 29.5) {
                            warnings += "$fps FPS is not sustainable at ${w}x$h; using 30 FPS"
                            fps = 30
                        } else {
                            shortSide -= 120
                            continue
                        }
                    }
                    width = w
                    height = h
                    break
                }
            }
            shortSide -= 120
        }
        if (width == 0) throw RecordingUnsupportedException("The encoder ${enc.name} supports none of the requested sizes")
        if (shortSide < targetShort) warnings += "Resolution reduced to ${minOf(width, height)}p by encoder limits"

        // ---------------------------------------------------------------- bitrate
        val bitsPerPixel = when (s.quality) {
            RecordingQuality.LOW -> 0.05
            RecordingQuality.BALANCED -> 0.09
            RecordingQuality.HIGH -> 0.15
            RecordingQuality.CUSTOM -> 0.0
        }
        var bitrate = if (s.quality == RecordingQuality.CUSTOM) {
            s.customBitrateKbps * 1000
        } else {
            (width.toLong() * height * fps * bitsPerPixel * (if (enc.mime == HEVC) 0.7 else 1.0)).toInt()
        }
        bitrate = bitrate.coerceIn(maxOf(enc.bitrateRange.first, 500_000), enc.bitrateRange.last)

        // ---------------------------------------------------------------- audio
        var audio = s.audio
        if ((audio == AudioSourceChoice.GAME_AUDIO || audio == AudioSourceChoice.GAME_AND_MICROPHONE) && androidApi in 1..28) {
            warnings += "Game audio capture needs Android 10+"
            audio = if (audio == AudioSourceChoice.GAME_AND_MICROPHONE) AudioSourceChoice.MICROPHONE else AudioSourceChoice.NONE
        }
        if (audio != AudioSourceChoice.NONE && !micPermission && audio != AudioSourceChoice.GAME_AUDIO) {
            warnings += "Audio permission not granted; recording without a microphone"
            audio = if (s.audio == AudioSourceChoice.GAME_AND_MICROPHONE) AudioSourceChoice.GAME_AUDIO else AudioSourceChoice.NONE
        }
        if (fps == 60) warnings += "60 FPS recordings only contain new frames if the game itself renders at 60 FPS"

        return RecordingPlan(enc.mime, enc.name, width, height, fps, bitrate, AudioPlan(audio), warnings)
    }

    private fun align(v: Int, a: Int) = (v / a) * a

    /** Bytes per second of the resulting file, for the "storage left" estimate. */
    fun bytesPerSecond(p: RecordingPlan): Long =
        (p.bitrate + if (p.audio.source != AudioSourceChoice.NONE) p.audio.bitrate else 0) / 8L

    /** How long a recording of [plan] fits in [freeBytes]. */
    fun estimatedMinutes(plan: RecordingPlan, freeBytes: Long): Int {
        val bps = bytesPerSecond(plan)
        if (bps <= 0) return 0
        return (freeBytes / bps / 60).toInt().coerceAtLeast(0)
    }

    fun describe(plan: RecordingPlan): String =
        "${plan.resolutionLabel} · ${plan.fps} FPS · ${plan.bitrateLabel} · ${plan.encoder}"
}
