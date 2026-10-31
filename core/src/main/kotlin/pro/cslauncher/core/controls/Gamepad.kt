package pro.cslauncher.core.controls

import kotlinx.serialization.Serializable

/** Android gamepad button codes (`KeyEvent` KEYCODE_BUTTON_* / DPAD_*). */
object PadKeys {
    const val A = 96          // KEYCODE_BUTTON_A
    const val B = 97
    const val C = 98
    const val X = 99
    const val Y = 100
    const val Z = 101
    const val L1 = 102
    const val R1 = 103
    const val L2 = 104
    const val R2 = 105
    const val THUMBL = 106
    const val THUMBR = 107
    const val START = 108
    const val SELECT = 109

    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
}

/**
 * Controller mapping. Defaults are the layout every Minecraft player already knows, so plugging a
 * pad in and pressing A jumps without any configuration.
 */
@Serializable
data class GamepadMapping(
    val buttons: Map<Int, InputAction> = mapOf(
        PadKeys.A to InputAction.Key(Glfw.KEY_SPACE),
        PadKeys.B to InputAction.Key(Glfw.KEY_LEFT_SHIFT),
        PadKeys.X to InputAction.Key(Glfw.KEY_E),
        PadKeys.Y to InputAction.Key(Glfw.KEY_Q),
        PadKeys.L1 to InputAction.Scroll(1f),
        PadKeys.R1 to InputAction.Scroll(-1f),
        PadKeys.START to InputAction.Key(Glfw.KEY_ESCAPE),
        PadKeys.SELECT to InputAction.LauncherMenu,
        PadKeys.THUMBL to InputAction.Key(Glfw.KEY_LEFT_CONTROL),
        PadKeys.THUMBR to InputAction.Key(Glfw.KEY_F5),
        PadKeys.DPAD_UP to InputAction.Key(Glfw.KEY_UP),
        PadKeys.DPAD_DOWN to InputAction.Key(Glfw.KEY_DOWN),
        PadKeys.DPAD_LEFT to InputAction.Key(Glfw.KEY_LEFT),
        PadKeys.DPAD_RIGHT to InputAction.Key(Glfw.KEY_RIGHT)
    ),
    val leftTrigger: InputAction = InputAction.Mouse(Glfw.MOUSE_RIGHT),
    val rightTrigger: InputAction = InputAction.Mouse(Glfw.MOUSE_LEFT),
    val deadZone: Float = 0.18f,
    val lookSpeedDpPerMs: Float = 0.9f,
    val menuCursorSpeedDpPerMs: Float = 0.7f,
    val triggerThreshold: Float = 0.5f
)

/**
 * Translates a gamepad into the same key/mouse stream the touch controls produce.
 *
 * Two details that are easy to get wrong and painful in practice:
 * * the left stick is **direction-based with hysteresis** (see [DirectionResolver]); an analogue
 *   value that lands on a diagonal must not flicker between two key combinations;
 * * a button held by both the pad and an on-screen control is reference-counted by
 *   [InputStateTracker], so releasing one does not cancel the other.
 */
class GamepadEngine(
    var mapping: GamepadMapping,
    private val tracker: InputStateTracker,
    private val sink: InputSink,
    private val density: Float,
    private val cursor: DynamicCursor? = null
) {
    private val resolver = DirectionResolver()
    private val buttonHeld = HashSet<Int>()
    private val moveHeld = HashSet<Int>()
    private var leftTriggerDown = false
    private var rightTriggerDown = false

    /** When true the right stick moves the virtual cursor; when false it does nothing. */
    var cursorGrabbed: Boolean = true
        set(value) {
            if (value != field) {
                // Hand the cursor over cleanly: never leave a button pressed across the switch.
                releaseTriggers()
            }
            field = value
        }

    /** Returns true when the event was consumed (so the platform does not also deliver it). */
    fun onButton(code: Int, down: Boolean): Boolean {
        val action = mapping.buttons[code] ?: return false
        if (down) {
            if (!buttonHeld.add(code)) return true
            press(action)
        } else {
            if (!buttonHeld.remove(code)) return true
            release(action)
        }
        return true
    }

    fun onAxes(lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float) {
        // ---------------------------------------------------------------- movement
        val flags = DirectionResolver.flags(resolver.sector(lx, ly, mapping.deadZone))
        val keys = listOf(Glfw.KEY_W, Glfw.KEY_S, Glfw.KEY_A, Glfw.KEY_D)
        val want = keys.filterIndexed { i, _ -> flags[i] }.toSet()
        for (key in want - moveHeld) tracker.pressKey(key)
        for (key in moveHeld - want) tracker.releaseKey(key)
        moveHeld.clear()
        moveHeld.addAll(want)

        // Sprint at full deflection, exactly like the touch stick.
        val magnitude = kotlin.math.hypot(lx.toDouble(), ly.toDouble()).toFloat()
        val wantSprint = flags[0] && magnitude >= 0.94f
        if (wantSprint && !tracker.isKeyDown(Glfw.KEY_LEFT_CONTROL)) {
            tracker.pressKey(Glfw.KEY_LEFT_CONTROL)
            sprintFromPad = true
        } else if (!wantSprint && sprintFromPad) {
            tracker.releaseKey(Glfw.KEY_LEFT_CONTROL)
            sprintFromPad = false
        }

        // ---------------------------------------------------------------- look / cursor
        val rxDead = if (kotlin.math.abs(rx) < mapping.deadZone) 0f else rx
        val ryDead = if (kotlin.math.abs(ry) < mapping.deadZone) 0f else ry
        if (rxDead != 0f || ryDead != 0f) {
            if (cursorGrabbed) {
                sink.cursorDelta(rxDead * mapping.lookSpeedDpPerMs * 18f, ryDead * mapping.lookSpeedDpPerMs * 18f)
            } else {
                cursor?.move(rxDead * mapping.menuCursorSpeedDpPerMs * 18f, ryDead * mapping.menuCursorSpeedDpPerMs * 18f)
            }
        }

        // ---------------------------------------------------------------- triggers
        val wantLeft = lt >= mapping.triggerThreshold
        if (wantLeft != leftTriggerDown) {
            if (wantLeft) press(mapping.leftTrigger) else release(mapping.leftTrigger)
            leftTriggerDown = wantLeft
        }
        val wantRight = rt >= mapping.triggerThreshold
        if (wantRight != rightTriggerDown) {
            if (wantRight) press(mapping.rightTrigger) else release(mapping.rightTrigger)
            rightTriggerDown = wantRight
        }
    }

    private var sprintFromPad = false

    /** A mapping change must release everything the old mapping was holding. */
    fun updateMapping(newMapping: GamepadMapping) {
        releaseAll()
        mapping = newMapping
    }

    private fun press(action: InputAction) {
        when (action) {
            is InputAction.Key -> tracker.pressKey(action.key)
            is InputAction.Mouse -> tracker.pressButton(action.button)
            is InputAction.Scroll -> sink.scroll(0.0, action.dy.toDouble())
            else -> sink.command(action)
        }
    }

    private fun release(action: InputAction) {
        when (action) {
            is InputAction.Key -> tracker.releaseKey(action.key)
            is InputAction.Mouse -> tracker.releaseButton(action.button)
            else -> {}
        }
    }

    private fun releaseTriggers() {
        if (leftTriggerDown) {
            release(mapping.leftTrigger)
            leftTriggerDown = false
        }
        if (rightTriggerDown) {
            release(mapping.rightTrigger)
            rightTriggerDown = false
        }
    }

    /** Disconnect, pause, or mapping change: nothing may stay pressed. */
    fun releaseAll() {
        buttonHeld.clear()
        moveHeld.forEach { tracker.releaseKey(it) }
        moveHeld.clear()
        if (sprintFromPad) {
            tracker.releaseKey(Glfw.KEY_LEFT_CONTROL)
            sprintFromPad = false
        }
        releaseTriggers()
        resolver.reset()
    }

    val connected: Boolean get() = true
}
