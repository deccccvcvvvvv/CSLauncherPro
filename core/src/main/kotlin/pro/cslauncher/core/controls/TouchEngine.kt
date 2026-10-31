package pro.cslauncher.core.controls

/**
 * A pointer (finger) as the engine sees it: normalised position plus an id, so two thumbs never
 * interfere.
 */
data class TouchPointer(val id: Long, val x: Float, val y: Float)

/**
 * The result of feeding a pointer event in: which control it hit and what the launcher should do.
 * Returning this instead of performing side effects keeps the whole gesture model unit-testable.
 */
data class TouchOutcome(
    val element: ControlElement?,
    val action: InputAction?,
    /** True when the launcher itself must react (menu, keyboard, recording…). */
    val command: InputAction? = null
)

/**
 * Turns raw touches into key/mouse events for the game.
 *
 * The behaviours that make touch Minecraft actually playable, all implemented here so they are
 * testable without a device:
 *
 * * **slide-off release** — dragging off a button releases it, but dragging *between* swipeable
 *   controls (hotbar) keeps firing them;
 * * **reference-counted holds** — two thumbs on the same button do not send a spurious RELEASE;
 * * **toggle vs hold** — sneak toggles, jump holds;
 * * **repeat** — scrolling repeats while held, with a delay before the first repeat;
 * * **double tap** — only fires when the second tap arrives inside the window;
 * * **modifier gating** — a control that needs shift does nothing unless shift is held;
 * * **release everything on focus loss** — a stuck "forward" key is the classic touch bug.
 */
class TouchEngine(
    layout: ControlLayout,
    private val tracker: InputStateTracker,
    private val sink: InputSink,
    private val density: Float
) {
    var layout: ControlLayout = layout
        private set

    var mode: String = layout.defaultMode
        private set

    private var cursorShown: Boolean = false

    private val active = HashMap<Long, ActiveTouch>()
    private val toggled = HashMap<String, Boolean>()
    private val lastTapAt = HashMap<String, Long>()
    private val joystickState = HashMap<Long, JoystickState>()

    private data class ActiveTouch(
        val elementId: String,
        var lastRepeatAt: Long = 0L,
        val startedAt: Long = System.currentTimeMillis()
    )

    private data class JoystickState(
        val elementId: String,
        var centreX: Float = 0f,
        var centreY: Float = 0f,
        var emitted: Set<Int> = emptySet(),
        var sprinting: Boolean = false,
        val resolver: DirectionResolver = DirectionResolver()
    )

    fun setLayout(newLayout: ControlLayout) {
        releaseAll()
        layout = newLayout
        if (mode !in newLayout.modes) mode = newLayout.defaultMode
    }

    /** Called when the game opens or closes a GUI; drives automatic mode switching. */
    fun setCursorShown(shown: Boolean) {
        cursorShown = shown
        if (!layout.autoModes || shown == (mode == ControlLayout.INVENTORY_MODE)) return
        mode = if (shown) ControlLayout.INVENTORY_MODE else layout.defaultMode
    }

    fun switchMode(newMode: String): Boolean {
        if (newMode !in layout.modes) return false
        releaseAll()
        mode = newMode
        return true
    }

    fun elements(): List<ControlElement> = layout.elements(mode).filter { it.visible(cursorShown) }

    // ---------------------------------------------------------------- pointer events

    fun down(pointer: TouchPointer): TouchOutcome {
        val element = hitTest(pointer) ?: return TouchOutcome(null, null)
        activate(element, pointer)
        return lastCommand?.let { TouchOutcome(element, null, it) }
            ?: TouchOutcome(element, element.actions.firstOrNull())
    }

    fun move(pointer: TouchPointer): TouchOutcome {
        val touch = active[pointer.id] ?: return TouchOutcome(null, null)
        val element = layout.element(touch.elementId) ?: return TouchOutcome(null, null)

        // Joysticks follow the thumb; everything else is hit-tested again so sliding off releases.
        if (element.kind != ElementKind.BUTTON) {
            joystickState[pointer.id]?.let { updateJoystick(element, it, pointer) }
            return TouchOutcome(element, null)
        }

        val over = hitTest(pointer)
        if (element.swipeable) {
            if (over != null && over.id != element.id && over.swipeable) {
                // Sliding across the hotbar keeps selecting: release the old, press the new.
                release(touch.elementId)
                activate(over, pointer)
                return TouchOutcome(over, over.actions.firstOrNull())
            }
            return TouchOutcome(element, null)
        }

        if (over?.id != element.id) {
            // Dragged off a hold button: release it so the player does not keep moving.
            if (element.behavior == ButtonBehavior.HOLD) {
                release(touch.elementId)
                return TouchOutcome(element, null)
            }
        }
        return TouchOutcome(element, null)
    }

    fun up(pointer: TouchPointer): TouchOutcome {
        val touch = active.remove(pointer.id) ?: return TouchOutcome(null, null)
        val element = layout.element(touch.elementId) ?: return TouchOutcome(null, null)
        if (element.kind != ElementKind.BUTTON) {
            joystickState.remove(pointer.id)?.let { state ->
                state.emitted.forEach { tracker.releaseKey(it) }
                if (state.sprinting) tracker.releaseKey(element.joystick?.sprintKey ?: Glfw.KEY_LEFT_CONTROL)
            }
            return TouchOutcome(element, null)
        }
        if (element.behavior != ButtonBehavior.TOGGLE) release(element.id)
        return TouchOutcome(element, null)
    }

    /** Called periodically (every frame or every ~16 ms) to drive repeats and joysticks. */
    fun tick(nowMs: Long = System.currentTimeMillis()) {
        for ((pointerId, touch) in active.toList()) {
            val element = layout.element(touch.elementId) ?: continue
            if (element.behavior != ButtonBehavior.REPEAT) continue
            // A delay before the first repeat, then a steady cadence — like a held keyboard key.
            if (nowMs - touch.startedAt < element.repeatDelayMs) continue
            if (nowMs - touch.lastRepeatAt < element.repeatIntervalMs) continue
            element.actions.forEach { fire(it) }
            active[pointerId] = touch.copy(lastRepeatAt = nowMs)
        }
    }

    /** Everything up. Called on focus loss, pause and every mode/layout switch. */
    fun releaseAll() {
        active.values.forEach { touch ->
            val element = layout.element(touch.elementId) ?: return@forEach
            if (element.kind == ElementKind.BUTTON && element.behavior != ButtonBehavior.TOGGLE) release(element.id)
        }
        active.clear()
        joystickState.clear()
        tracker.releaseAll()
    }

    val anyTouchActive: Boolean get() = active.isNotEmpty()

    // ---------------------------------------------------------------- internals

    private var lastCommand: InputAction? = null

    private fun activate(element: ControlElement, pointer: TouchPointer) {
        lastCommand = null
        element.requiresModifier?.let { modifier ->
            if (!tracker.isKeyDown(modifier)) return
        }
        when (element.kind) {
            ElementKind.JOYSTICK -> {
                joystickState[pointer.id] = JoystickState(
                    elementId = element.id,
                    centreX = pointer.x,
                    centreY = pointer.y
                )
                active[pointer.id] = ActiveTouch(element.id)
            }
            ElementKind.TOUCHPAD -> {
                active[pointer.id] = ActiveTouch(element.id)
            }
            ElementKind.BUTTON -> when (element.behavior) {
                ButtonBehavior.HOLD -> {
                    element.actions.forEach { fire(it) }
                    active[pointer.id] = ActiveTouch(element.id)
                }
                ButtonBehavior.TAP -> {
                    element.actions.forEach { fire(it) }
                    active[pointer.id] = ActiveTouch(element.id)
                }
                ButtonBehavior.TOGGLE -> {
                    val on = toggled[element.id] == true
                    if (on) release(element.id) else element.actions.forEach { fire(it) }
                    toggled[element.id] = !on
                    active[pointer.id] = ActiveTouch(element.id)
                }
                ButtonBehavior.REPEAT -> {
                    element.actions.forEach { fire(it) }
                    active[pointer.id] = ActiveTouch(element.id, lastRepeatAt = System.currentTimeMillis())
                }
                ButtonBehavior.DOUBLE_TAP -> {
                    val now = System.currentTimeMillis()
                    val last = lastTapAt[element.id]
                    if (last != null && now - last <= element.doubleTapMs) {
                        element.actions.forEach { fire(it) }
                        lastTapAt.remove(element.id)
                    } else {
                        lastTapAt[element.id] = now
                    }
                    active[pointer.id] = ActiveTouch(element.id)
                }
            }
        }
    }

    private fun release(elementId: String) {
        val element = layout.element(elementId) ?: return
        element.actions.forEach { action ->
            when (action) {
                is InputAction.Key -> tracker.releaseKey(action.key)
                is InputAction.Mouse -> tracker.releaseButton(action.button)
                else -> {}
            }
        }
        toggled[elementId] = false
    }

    private fun fire(action: InputAction) {
        when (action) {
            is InputAction.Key -> tracker.pressKey(action.key)
            is InputAction.Mouse -> tracker.pressButton(action.button)
            is InputAction.Scroll -> sink.scroll(0.0, action.dy.toDouble())
            is InputAction.SwitchMode -> switchMode(action.mode)
            else -> {
                lastCommand = action
                sink.command(action)
            }
        }
    }

    private fun updateJoystick(element: ControlElement, state: JoystickState, pointer: TouchPointer) {
        val config = element.joystick ?: return
        val radiusPx = (element.widthDp / 2f) * density
        if (radiusPx <= 0f) return
        val centreX = if (config.floating) state.centreX else element.x
        val centreY = if (config.floating) state.centreY else element.y

        var dx = (pointer.x - centreX) / (element.widthDp / screenWidthPx / 2f).coerceAtLeast(1e-5f)
        var dy = (pointer.y - centreY) / (element.heightDp / screenHeightPx / 2f).coerceAtLeast(1e-5f)
        val magnitude = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (magnitude > 1f) {
            dx /= magnitude
            dy /= magnitude
        }

        if (config.role == JoystickRole.LOOK) {
            // Look stick: movement is relative, so it is emitted as a cursor delta each tick.
            val mag = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (mag > config.deadZone) {
                sink.cursorDelta(dx * config.sensitivity * 40f, dy * config.sensitivity * 40f)
            }
            return
        }

        val wanted = DirectionResolver.flags(state.resolver.sector(dx, dy, config.deadZone))
        val keys = listOf(config.forward, config.back, config.left, config.right)
        val want = keys.filterIndexed { i, _ -> wanted[i] }.toSet()
        for (key in want - state.emitted) tracker.pressKey(key)
        for (key in state.emitted - want) tracker.releaseKey(key)
        state.emitted = want

        // Auto-sprint: full deflection forwards for a moment engages sprint. Teleport-free and it
        // matches what a real analogue stick does in the game.
        if (config.sprintThreshold > 0f) {
            val forwards = wanted[0]
            val deflection = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val shouldSprint = forwards && deflection >= config.sprintThreshold
            if (shouldSprint && !state.sprinting) {
                tracker.pressKey(config.sprintKey)
                state.sprinting = true
            } else if (!shouldSprint && state.sprinting) {
                tracker.releaseKey(config.sprintKey)
                state.sprinting = false
            }
        }
    }

    /**
     * dp → normalised coordinates. Later elements are drawn on top, so they win a hit: the list is
     * iterated in reverse.
     */
    private fun hitTest(pointer: TouchPointer): ControlElement? = elements().lastOrNull { element ->
        val halfWidth = (element.widthDp * density / 2f) / screenWidthPx
        val halfHeight = (element.heightDp * density / 2f) / screenHeightPx
        kotlin.math.abs(pointer.x - element.x) <= halfWidth &&
            kotlin.math.abs(pointer.y - element.y) <= halfHeight
    }

    /**
     * Screen size in pixels. The engine needs it to convert dp sizes into normalised hit areas; the app
     * sets it once on layout. Kept mutable so a rotation does not need a new engine.
     */
    var screenWidthPx: Float = 1080f
    var screenHeightPx: Float = 2400f

    fun updateScreenSize(widthPx: Int, heightPx: Int) {
        screenWidthPx = widthPx.toFloat().coerceAtLeast(1f)
        screenHeightPx = heightPx.toFloat().coerceAtLeast(1f)
    }
}
