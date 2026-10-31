package pro.cslauncher.core.controls

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GLFW key/mouse constants — the same values the game and the JNI bridge already speak. */
object Glfw {
    const val PRESS = 1
    const val RELEASE = 0
    const val REPEAT = 2

    const val MOUSE_LEFT = 0
    const val MOUSE_RIGHT = 1
    const val MOUSE_MIDDLE = 2

    const val KEY_SPACE = 32
    const val KEY_APOSTROPHE = 39
    const val KEY_COMMA = 44
    const val KEY_MINUS = 45
    const val KEY_PERIOD = 46
    const val KEY_SLASH = 47
    const val KEY_0 = 48
    const val KEY_9 = 57
    const val KEY_SEMICOLON = 59
    const val KEY_EQUAL = 61
    const val KEY_A = 65
    const val KEY_D = 68
    const val KEY_E = 69
    const val KEY_F = 70
    const val KEY_Q = 81
    const val KEY_S = 83
    const val KEY_T = 84
    const val KEY_W = 87
    const val KEY_LEFT_BRACKET = 91
    const val KEY_BACKSLASH = 92
    const val KEY_RIGHT_BRACKET = 93
    const val KEY_GRAVE = 96

    const val KEY_ESCAPE = 256
    const val KEY_ENTER = 257
    const val KEY_TAB = 258
    const val KEY_BACKSPACE = 259
    const val KEY_INSERT = 260
    const val KEY_DELETE = 261
    const val KEY_RIGHT = 262
    const val KEY_LEFT = 263
    const val KEY_DOWN = 264
    const val KEY_UP = 265
    const val KEY_PAGE_UP = 266
    const val KEY_PAGE_DOWN = 267
    const val KEY_HOME = 268
    const val KEY_END = 269
    const val KEY_CAPS_LOCK = 280

    const val KEY_F1 = 290
    const val KEY_F2 = 291
    const val KEY_F3 = 292
    const val KEY_F5 = 294
    const val KEY_F11 = 300
    const val KEY_F12 = 301

    const val KEY_KP_0 = 320
    const val KEY_LEFT_SHIFT = 340
    const val KEY_LEFT_CONTROL = 341
    const val KEY_LEFT_ALT = 342
    const val KEY_RIGHT_SHIFT = 344
    const val KEY_RIGHT_CONTROL = 345
    const val KEY_RIGHT_ALT = 346

    const val MOD_SHIFT = 1
    const val MOD_CONTROL = 2
    const val MOD_ALT = 4

    fun isValidKey(k: Int): Boolean = k == KEY_SPACE || k in 39..96 || k in 256..348

    fun isValidMouse(b: Int): Boolean = b in 0..7

    val names: Map<Int, String> = buildMap {
        for (c in 'A'..'Z') put(c.code, c.toString())
        for (d in 0..9) put(KEY_0 + d, d.toString())
        for (f in 1..12) put(KEY_F1 + f - 1, "F$f")
        put(KEY_SPACE, "Space")
        put(KEY_ESCAPE, "Esc")
        put(KEY_ENTER, "Enter")
        put(KEY_TAB, "Tab")
        put(KEY_BACKSPACE, "Backspace")
        put(KEY_DELETE, "Delete")
        put(KEY_INSERT, "Insert")
        put(KEY_UP, "Up")
        put(KEY_DOWN, "Down")
        put(KEY_LEFT, "Left")
        put(KEY_RIGHT, "Right")
        put(KEY_PAGE_UP, "Page Up")
        put(KEY_PAGE_DOWN, "Page Down")
        put(KEY_HOME, "Home")
        put(KEY_END, "End")
        put(KEY_CAPS_LOCK, "Caps Lock")
        put(KEY_LEFT_SHIFT, "L-Shift")
        put(KEY_RIGHT_SHIFT, "R-Shift")
        put(KEY_LEFT_CONTROL, "L-Ctrl")
        put(KEY_RIGHT_CONTROL, "R-Ctrl")
        put(KEY_LEFT_ALT, "L-Alt")
        put(KEY_RIGHT_ALT, "R-Alt")
        put(KEY_MINUS, "-")
        put(KEY_EQUAL, "=")
        put(KEY_LEFT_BRACKET, "[")
        put(KEY_RIGHT_BRACKET, "]")
        put(KEY_BACKSLASH, "\\")
        put(KEY_SEMICOLON, ";")
        put(KEY_APOSTROPHE, "'")
        put(KEY_COMMA, ",")
        put(KEY_PERIOD, ".")
        put(KEY_SLASH, "/")
        put(KEY_GRAVE, "`")
    }

    fun nameOf(key: Int): String = names[key] ?: "Key $key"

    /** GLFW key codes are the game's own codes; this exists so callers do not assume it. */
    fun scancodeFor(key: Int): Int = key
}

/**
 * Android key codes → GLFW key codes.
 *
 * Android's `KeyEvent` codes are completely different from GLFW's, and the difference is the single
 * most common reason an on-screen keyboard types nothing.
 */
object AndroidKeyMap {

    private val map: Map<Int, Int> = buildMap {
        for (i in 0..25) put(29 + i, Glfw.KEY_A + i)          // KEYCODE_A..Z
        for (i in 0..9) put(7 + i, Glfw.KEY_0 + i)            // KEYCODE_0..9
        put(62, Glfw.KEY_SPACE)
        put(66, Glfw.KEY_ENTER)
        put(67, Glfw.KEY_BACKSPACE)
        put(61, Glfw.KEY_TAB)
        put(111, Glfw.KEY_ESCAPE)
        put(112, Glfw.KEY_DELETE)
        put(19, Glfw.KEY_UP)
        put(20, Glfw.KEY_DOWN)
        put(21, Glfw.KEY_LEFT)
        put(22, Glfw.KEY_RIGHT)
        put(122, Glfw.KEY_HOME)
        put(123, Glfw.KEY_END)
        put(92, Glfw.KEY_PAGE_UP)
        put(93, Glfw.KEY_PAGE_DOWN)
        put(124, Glfw.KEY_INSERT)
        put(115, Glfw.KEY_CAPS_LOCK)
        put(59, Glfw.KEY_LEFT_SHIFT)
        put(60, Glfw.KEY_LEFT_SHIFT)
        put(113, Glfw.KEY_LEFT_CONTROL)
        put(114, Glfw.KEY_RIGHT_CONTROL)
        put(57, Glfw.KEY_LEFT_ALT)
        put(58, Glfw.KEY_RIGHT_ALT)
        put(68, Glfw.KEY_GRAVE)
        put(69, Glfw.KEY_MINUS)
        put(70, Glfw.KEY_EQUAL)
        put(71, Glfw.KEY_LEFT_BRACKET)
        put(72, Glfw.KEY_RIGHT_BRACKET)
        put(73, Glfw.KEY_SEMICOLON)
        put(74, Glfw.KEY_APOSTROPHE)
        put(75, Glfw.KEY_GRAVE)
        put(55, Glfw.KEY_COMMA)
        put(56, Glfw.KEY_PERIOD)
        put(76, Glfw.KEY_SLASH)
        put(77, Glfw.KEY_GRAVE)
        put(81, Glfw.KEY_EQUAL)
        put(82, Glfw.KEY_SEMICOLON)
        put(83, Glfw.KEY_APOSTROPHE)
        put(84, Glfw.KEY_GRAVE)
        put(85, Glfw.KEY_MINUS)
        put(86, Glfw.KEY_EQUAL)
        put(87, Glfw.KEY_LEFT_BRACKET)
        put(88, Glfw.KEY_RIGHT_BRACKET)
        put(89, Glfw.KEY_BACKSLASH)
        put(90, Glfw.KEY_SLASH)
        for (f in 1..12) put(130 + f, Glfw.KEY_F1 + f - 1)
    }

    fun toGlfw(androidKeyCode: Int): Int? = map[androidKeyCode]

    /** For printable characters typed with a soft keyboard that reports a `char` instead of a key. */
    fun toGlfwForChar(c: Char): Int? = when {
        c in 'a'..'z' -> Glfw.KEY_A + (c - 'a')
        c in 'A'..'Z' -> Glfw.KEY_A + (c - 'A')
        c in '0'..'9' -> Glfw.KEY_0 + (c - '0')
        c == ' ' -> Glfw.KEY_SPACE
        c == '\n' -> Glfw.KEY_ENTER
        else -> null
    }
}

/** Something a control can do. Commands other than key/mouse go to the launcher, not the game. */
@Serializable
sealed class InputAction {

    @Serializable
    @SerialName("key")
    data class Key(val key: Int) : InputAction()

    @Serializable
    @SerialName("mouse")
    data class Mouse(val button: Int) : InputAction()

    @Serializable
    @SerialName("scroll")
    data class Scroll(val dy: Float) : InputAction()

    @Serializable
    @SerialName("mode")
    data class SwitchMode(val mode: String) : InputAction()

    @Serializable
    @SerialName("keyboard")
    data object ShowKeyboard : InputAction()

    @Serializable
    @SerialName("toggle_overlay")
    data object ToggleOverlay : InputAction()

    @Serializable
    @SerialName("record")
    data object ToggleRecording : InputAction()

    @Serializable
    @SerialName("menu")
    data object LauncherMenu : InputAction()

    @Serializable
    @SerialName("virtual_mouse")
    data object ToggleVirtualMouse : InputAction()

    fun describe(): String = when (this) {
        is Key -> Glfw.nameOf(key)
        is Mouse -> when (button) {
            0 -> "Left click"
            1 -> "Right click"
            2 -> "Middle click"
            else -> "Mouse $button"
        }
        is Scroll -> if (dy > 0) "Scroll up" else "Scroll down"
        is SwitchMode -> "Mode: $mode"
        ShowKeyboard -> "Keyboard"
        ToggleOverlay -> "Hide controls"
        ToggleRecording -> "Record"
        LauncherMenu -> "Menu"
        ToggleVirtualMouse -> "Virtual mouse"
    }

    /** True when this action is forwarded to the game rather than handled by the launcher. */
    val isGameAction: Boolean
        get() = this is Key || this is Mouse || this is Scroll
}

/**
 * Where input goes. Implemented by the JNI bridge on device.
 *
 * Centralising this in one interface is what makes the whole input stack testable: a fake sink records
 * the exact key events a gesture would produce.
 */
interface InputSink {
    fun key(key: Int, action: Int, mods: Int)
    fun mouseButton(button: Int, pressed: Boolean, mods: Int)
    fun scroll(dx: Double, dy: Double)
    fun cursorDelta(dx: Float, dy: Float)
    fun cursorAbsolute(x: Float, y: Float)
    fun command(action: InputAction)
}

/**
 * Reference-counted key and button state.
 *
 * The counter is the point: touch controls, a gamepad and a virtual key can all hold the same key at
 * once, and the game must only see RELEASE when the *last* holder lets go. Without this, releasing the
 * on-screen jump button cancels a jump the gamepad is still holding down.
 */
class InputStateTracker(private val sink: InputSink) {

    private val keys = HashMap<Int, Int>()
    private val buttons = HashMap<Int, Int>()

    fun mods(): Int {
        var m = 0
        if (isKeyDown(Glfw.KEY_LEFT_SHIFT) || isKeyDown(Glfw.KEY_RIGHT_SHIFT)) m = m or Glfw.MOD_SHIFT
        if (isKeyDown(Glfw.KEY_LEFT_CONTROL) || isKeyDown(Glfw.KEY_RIGHT_CONTROL)) m = m or Glfw.MOD_CONTROL
        if (isKeyDown(Glfw.KEY_LEFT_ALT) || isKeyDown(Glfw.KEY_RIGHT_ALT)) m = m or Glfw.MOD_ALT
        return m
    }

    fun isKeyDown(k: Int): Boolean = (keys[k] ?: 0) > 0

    fun isButtonDown(b: Int): Boolean = (buttons[b] ?: 0) > 0

    val anyDown: Boolean get() = keys.isNotEmpty() || buttons.isNotEmpty()

    fun pressKey(k: Int) {
        val count = keys[k] ?: 0
        keys[k] = count + 1
        if (count == 0) sink.key(k, Glfw.PRESS, mods())
    }

    fun releaseKey(k: Int) {
        val count = keys[k] ?: return
        if (count <= 1) {
            keys.remove(k)
            sink.key(k, Glfw.RELEASE, mods())
        } else {
            keys[k] = count - 1
        }
    }

    fun repeatKey(k: Int) {
        if (isKeyDown(k)) sink.key(k, Glfw.REPEAT, mods())
    }

    fun tapKey(k: Int) {
        pressKey(k)
        releaseKey(k)
    }

    fun pressButton(b: Int) {
        val count = buttons[b] ?: 0
        buttons[b] = count + 1
        if (count == 0) sink.mouseButton(b, true, mods())
    }

    fun releaseButton(b: Int) {
        val count = buttons[b] ?: return
        if (count <= 1) {
            buttons.remove(b)
            sink.mouseButton(b, false, mods())
        } else {
            buttons[b] = count - 1
        }
    }

    fun clickButton(b: Int) {
        pressButton(b)
        releaseButton(b)
    }

    /** Everything up — used when the game window loses focus, so no key stays stuck. */
    fun releaseAll() {
        buttons.keys.toList().forEach {
            buttons.remove(it)
            sink.mouseButton(it, false, 0)
        }
        keys.keys.toList().forEach {
            keys.remove(it)
            sink.key(it, Glfw.RELEASE, 0)
        }
    }

    val heldKeys: Set<Int> get() = keys.keys.toSet()

    val heldButtons: Set<Int> get() = buttons.keys.toSet()
}

/**
 * Turns a joystick vector into up to two movement directions, with hysteresis.
 *
 * Hysteresis is the whole trick: without it, holding the stick exactly on a diagonal makes the game
 * stutter between "forward" and "forward+right" several times a second.
 */
class DirectionResolver(private val hysteresisDeg: Double = 6.0) {

    private var lastSector = -1

    fun reset() {
        lastSector = -1
    }

    /** Sector 0 = up, then clockwise; -1 inside the dead zone. */
    fun sector(dx: Float, dy: Float, deadZone: Float): Int {
        val magnitude = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
        if (magnitude < deadZone) {
            lastSector = -1
            return -1
        }
        var angle = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), -dy.toDouble()))
        if (angle < 0) angle += 360.0
        val raw = (((angle + 22.5) % 360) / 45).toInt()
        if (lastSector >= 0 && raw != lastSector) {
            val centre = lastSector * 45.0
            var diff = kotlin.math.abs(angle - centre)
            if (diff > 180) diff = 360 - diff
            // Still inside the previous sector's widened cone: keep it, so the game does not flicker.
            if (diff < 22.5 + hysteresisDeg) return lastSector
        }
        lastSector = raw
        return raw
    }

    companion object {
        /** [up, down, left, right] for a sector index. */
        fun flags(sector: Int): BooleanArray = when (sector) {
            0 -> booleanArrayOf(true, false, false, false)
            1 -> booleanArrayOf(true, false, false, true)
            2 -> booleanArrayOf(false, false, false, true)
            3 -> booleanArrayOf(false, true, false, true)
            4 -> booleanArrayOf(false, true, false, false)
            5 -> booleanArrayOf(false, true, true, false)
            6 -> booleanArrayOf(false, false, true, false)
            7 -> booleanArrayOf(true, false, true, false)
            else -> booleanArrayOf(false, false, false, false)
        }
    }
}
