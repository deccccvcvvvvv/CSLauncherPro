package pro.cslauncher.core.controls

import pro.cslauncher.core.settings.CursorSettings
import pro.cslauncher.core.settings.CursorShape
import pro.cslauncher.core.settings.pointerOnTouch
import kotlin.math.abs
import kotlin.math.hypot

/** What a cursor hit tested against: any rectangle-shaped target on screen. */
data class CursorTarget(val id: String, val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(px: Float, py: Float): Boolean =
        abs(px - x) <= width / 2f && abs(py - y) <= height / 2f
}

/**
 * The virtual mouse the player moves with a touchpad, a gamepad or a physical mouse.
 *
 * Three behaviours earn their place here:
 * * **smoothing is a low-pass filter, not a multiplier** — it must reduce jitter without adding input
 *   lag, so it is applied to the *delta*, not to the position;
 * * **sensitivity is per-axis-configurable through the caller**, because a phone in landscape often
 *   needs different X and Y scaling;
 * * **the cursor is clamped to the surface and always reported in normalised coordinates**, so the
 *   JNI bridge and the game agree about where it is.
 */
class DynamicCursor(private var settings: CursorSettings) {

    var widthPx: Float = 1080f
        private set
    var heightPx: Float = 2400f
        private set

    var x: Float = widthPx / 2f
        private set
    var y: Float = heightPx / 2f
        private set

    var visible: Boolean = false
        private set

    private var smoothedDx = 0f
    private var smoothedDy = 0f

    /** The surface changed (rotation, window resize): keep the cursor inside it. */
    fun resize(widthPx: Int, heightPx: Int) {
        this.widthPx = widthPx.toFloat().coerceAtLeast(1f)
        this.heightPx = heightPx.toFloat().coerceAtLeast(1f)
        x = x.coerceIn(0f, this.widthPx)
        y = y.coerceIn(0f, this.heightPx)
    }

    fun updateSettings(newSettings: CursorSettings) {
        settings = newSettings
        if (!newSettings.pointerOnTouch) hide()
    }

    /**
     * Applies relative movement. [dx]/[dy] are in surface pixels before sensitivity and smoothing.
     * Returns the movement actually applied, so the caller can decide whether to forward it to the game.
     */
    fun move(dx: Float, dy: Float): Pair<Float, Float> {
        val sensitivity = settings.sensitivity.coerceIn(0.05f, 10f)
        val targetX = dx * sensitivity
        val targetY = dy * sensitivity

        val smoothing = settings.smoothing.coerceIn(0f, 0.95f)
        smoothedDx = smoothedDx * smoothing + targetX * (1f - smoothing)
        smoothedDy = smoothedDy * smoothing + targetY * (1f - smoothing)

        x = (x + smoothedDx).coerceIn(0f, widthPx)
        y = (y + smoothedDy).coerceIn(0f, heightPx)
        return smoothedDx to smoothedDy
    }

    /** Absolute placement, used by the touchpad's "tap to click" and by menu navigation. */
    fun moveTo(normalizedX: Float, normalizedY: Float) {
        x = (normalizedX.coerceIn(0f, 1f)) * widthPx
        y = (normalizedY.coerceIn(0f, 1f)) * heightPx
    }

    fun show() {
        visible = true
    }

    fun hide() {
        visible = false
    }

    fun reset() {
        x = widthPx / 2f
        y = heightPx / 2f
        smoothedDx = 0f
        smoothedDy = 0f
    }

    val normalizedX: Float get() = if (widthPx <= 0f) 0f else (x / widthPx).coerceIn(0f, 1f)
    val normalizedY: Float get() = if (heightPx <= 0f) 0f else (y / heightPx).coerceIn(0f, 1f)

    /** Size in px derived from dp, for the drawn cursor. */
    fun radiusPx(density: Float): Float = (settings.sizeDp * density / 2f).coerceAtLeast(4f)

    /** The first target under the cursor; later targets are on top, so they are checked first. */
    fun targetAt(targets: List<CursorTarget>): CursorTarget? =
        targets.lastOrNull { it.contains(normalizedX, normalizedY) }

    /**
     * Which direction the cursor should be drawn facing, for shapes that are not symmetric. Keeps the
     * arrow legible while the pointer moves instead of flipping wildly on vertical motion.
     */
    fun facing(dx: Float, dy: Float): Float {
        if (hypot(dx, dy) < 0.5f) return 0f
        return Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    /** Honest label for the settings screen. */
    fun describe(): String = buildString {
        append(settings.shape.name.lowercase().replace('_', ' '))
        append(" · ${settings.sizeDp.toInt()} dp")
        if (settings.trail) append(" · trail")
        if (!settings.pointerOnTouch) append(" · hidden in immersive mode")
    }

    val shape: CursorShape get() = settings.shape
}
