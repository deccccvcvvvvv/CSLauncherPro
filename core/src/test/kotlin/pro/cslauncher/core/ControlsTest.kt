package pro.cslauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.cslauncher.core.controls.ButtonBehavior
import pro.cslauncher.core.controls.ControlElement
import pro.cslauncher.core.controls.ControlLayout
import pro.cslauncher.core.controls.ControlLayoutCodec
import pro.cslauncher.core.controls.ControlLayoutStore
import pro.cslauncher.core.controls.DefaultLayouts
import pro.cslauncher.core.controls.DirectionResolver
import pro.cslauncher.core.controls.ElementKind
import pro.cslauncher.core.controls.Glfw
import pro.cslauncher.core.controls.InputAction
import pro.cslauncher.core.controls.InputSink
import pro.cslauncher.core.controls.InputStateTracker
import pro.cslauncher.core.controls.LayoutEditor
import pro.cslauncher.core.controls.LayoutIds
import pro.cslauncher.core.controls.LayoutValidationException
import pro.cslauncher.core.controls.TouchEngine
import pro.cslauncher.core.controls.TouchPointer
import java.nio.file.Files

class ControlsTest {

    /** Records every event so gestures can be asserted exactly. */
    private class RecordingSink : InputSink {
        val keys = mutableListOf<Triple<Int, Int, Int>>()
        val buttons = mutableListOf<Pair<Int, Boolean>>()
        val scrolls = mutableListOf<Double>()
        val commands = mutableListOf<InputAction>()
        val cursor = mutableListOf<Pair<Float, Float>>()

        override fun key(key: Int, action: Int, mods: Int) {
            keys += Triple(key, action, mods)
        }

        override fun mouseButton(button: Int, pressed: Boolean, mods: Int) {
            buttons += button to pressed
        }

        override fun scroll(dx: Double, dy: Double) {
            scrolls += dy
        }

        override fun cursorDelta(dx: Float, dy: Float) {
            cursor += dx to dy
        }

        override fun cursorAbsolute(x: Float, y: Float) {
            cursor += x to y
        }

        override fun command(action: InputAction) {
            commands += action
        }
    }

    private fun engine(layout: ControlLayout = DefaultLayouts.defaultTouch()): Triple<TouchEngine, RecordingSink, InputStateTracker> {
        val sink = RecordingSink()
        val tracker = InputStateTracker(sink)
        val touch = TouchEngine(layout, tracker, sink, density = 1f)
        touch.updateScreenSize(1000, 2000)
        return Triple(touch, sink, tracker)
    }

    private fun element(layout: ControlLayout, id: String) = layout.element(id)!!

    @Test
    fun defaultLayoutsAreValid() {
        for (layout in DefaultLayouts.all()) {
            val problems = ControlLayoutCodec.validate(layout)
            assertTrue("${layout.id}: $problems", problems.isEmpty())
        }
    }

    @Test
    fun layoutRoundTripsThroughJson() {
        val layout = DefaultLayouts.defaultTouch()
        val text = ControlLayoutCodec.encode(layout)
        val decoded = ControlLayoutCodec.decode(text)
        assertEquals(layout.id, decoded.id)
        assertEquals(layout.modes["game"]!!.size, decoded.modes["game"]!!.size)
        assertEquals(InputAction.Key(Glfw.KEY_SPACE), decoded.element("jump")!!.actions.first())
    }

    @Test
    fun validationCatchesBadControls() {
        val bad = ControlLayout(
            id = "bad",
            name = "Bad",
            modes = mapOf(
                "game" to listOf(
                    ControlElement("dup", x = 1.5f, y = 0.5f),
                    ControlElement("dup", x = 0.5f, y = 0.5f, actions = listOf(InputAction.SwitchMode("nowhere"))),
                    ControlElement("joy", kind = ElementKind.JOYSTICK, x = 0.5f, y = 0.5f)
                )
            )
        )
        val problems = ControlLayoutCodec.validate(bad)
        assertTrue(problems.any { it.contains("outside the screen") })
        assertTrue(problems.any { it.contains("Duplicate control id") })
        assertTrue(problems.any { it.contains("unknown mode") })
        assertTrue(problems.any { it.contains("joystick without configuration") })

        val threw = try {
            ControlLayoutCodec.decode(ControlLayoutCodec.encode(bad))
            false
        } catch (_: LayoutValidationException) {
            true
        }
        assertTrue(threw)
    }

    @Test
    fun touchHoldsAndReleasesAKey() {
        val (touch, sink, tracker) = engine()
        val jump = element(DefaultLayouts.defaultTouch(), "jump")
        touch.down(TouchPointer(1, jump.x, jump.y))
        assertTrue(tracker.isKeyDown(Glfw.KEY_SPACE))
        assertEquals(Triple(Glfw.KEY_SPACE, Glfw.PRESS, 0), sink.keys.first())
        touch.up(TouchPointer(1, jump.x, jump.y))
        assertFalse(tracker.isKeyDown(Glfw.KEY_SPACE))
        assertEquals(Glfw.RELEASE, sink.keys.last().second)
    }

    @Test
    fun draggingOffAHoldButtonReleasesIt() {
        val (touch, _, tracker) = engine()
        val jump = element(DefaultLayouts.defaultTouch(), "jump")
        touch.down(TouchPointer(1, jump.x, jump.y))
        assertTrue(tracker.isKeyDown(Glfw.KEY_SPACE))
        // Drag far away: the key must not stay stuck.
        touch.move(TouchPointer(1, 0.05f, 0.05f))
        assertFalse("A key held by a finger that slid away must be released", tracker.isKeyDown(Glfw.KEY_SPACE))
    }

    @Test
    fun twoFingersOnOneButtonDoNotCancelEachOther() {
        val (touch, _, tracker) = engine()
        val jump = element(DefaultLayouts.defaultTouch(), "jump")
        touch.down(TouchPointer(1, jump.x, jump.y))
        touch.down(TouchPointer(2, jump.x, jump.y))
        touch.up(TouchPointer(1, jump.x, jump.y))
        assertTrue("The second finger still holds the button", tracker.isKeyDown(Glfw.KEY_SPACE))
        touch.up(TouchPointer(2, jump.x, jump.y))
        assertFalse(tracker.isKeyDown(Glfw.KEY_SPACE))
    }

    @Test
    fun toggleButtonTogglesOnEachTap() {
        val layout = DefaultLayouts.defaultTouch()
        val (touch, _, tracker) = engine(layout)
        val sneak = element(layout, "sneak")
        assertEquals(ButtonBehavior.TOGGLE, sneak.behavior)
        touch.down(TouchPointer(1, sneak.x, sneak.y))
        touch.up(TouchPointer(1, sneak.x, sneak.y))
        assertTrue(tracker.isKeyDown(Glfw.KEY_LEFT_SHIFT))
        touch.down(TouchPointer(2, sneak.x, sneak.y))
        touch.up(TouchPointer(2, sneak.x, sneak.y))
        assertFalse(tracker.isKeyDown(Glfw.KEY_LEFT_SHIFT))
    }

    @Test
    fun joystickEmitsDirectionsAndAutoSprint() {
        val layout = DefaultLayouts.defaultTouch()
        val (touch, _, tracker) = engine(layout)
        val stick = element(layout, "move")
        touch.down(TouchPointer(1, stick.x, stick.y))
        // Push the stick fully up.
        touch.move(TouchPointer(1, stick.x, stick.y - 0.5f))
        assertTrue("Up must press forward", tracker.isKeyDown(Glfw.KEY_W))
        assertFalse(tracker.isKeyDown(Glfw.KEY_S))
        assertTrue("Full deflection must sprint", tracker.isKeyDown(Glfw.KEY_LEFT_CONTROL))
        // Release the stick: nothing may stay pressed.
        touch.up(TouchPointer(1, stick.x, stick.y))
        assertFalse(tracker.isKeyDown(Glfw.KEY_W))
        assertFalse(tracker.isKeyDown(Glfw.KEY_LEFT_CONTROL))
    }

    @Test
    fun joystickDiagonalPressesTwoDirections() {
        val layout = DefaultLayouts.defaultTouch()
        val (touch, _, tracker) = engine(layout)
        val stick = element(layout, "move")
        touch.down(TouchPointer(1, stick.x, stick.y))
        touch.move(TouchPointer(1, stick.x + 0.4f, stick.y - 0.4f))
        assertTrue(tracker.isKeyDown(Glfw.KEY_W))
        assertTrue(tracker.isKeyDown(Glfw.KEY_D))
    }

    @Test
    fun directionResolverHasHysteresis() {
        val resolver = DirectionResolver(hysteresisDeg = 6.0)
        assertEquals(0, resolver.sector(0f, -1f, 0.1f))
        // 25° is past the 22.5° boundary (so raw sector 1) but inside sector 0's widened cone.
        val angle = Math.toRadians(25.0)
        val dx = kotlin.math.sin(angle).toFloat()
        val dy = (-kotlin.math.cos(angle)).toFloat()
        assertEquals("Hysteresis must keep the previous sector", 0, resolver.sector(dx, dy, 0.1f))
        assertEquals(-1, resolver.sector(0f, 0f, 0.1f))
    }

    @Test
    fun commandButtonsReachTheLauncher() {
        val (touch, sink, _) = engine()
        val keyboard = element(DefaultLayouts.defaultTouch(), "keyboard")
        touch.down(TouchPointer(1, keyboard.x, keyboard.y))
        assertTrue(sink.commands.contains(InputAction.ShowKeyboard))
    }

    @Test
    fun repeatFiresOnlyAfterTheDelay() {
        val layout = ControlLayout(
            id = "repeat-test", name = "Repeat",
            modes = mapOf("game" to listOf(
                ControlElement(
                    "scroll", x = 0.5f, y = 0.5f, behavior = ButtonBehavior.REPEAT,
                    actions = listOf(InputAction.Scroll(1f)), repeatDelayMs = 300, repeatIntervalMs = 50
                )
            ))
        )
        val (touch, sink, _) = engine(layout)
        touch.down(TouchPointer(1, 0.5f, 0.5f))
        val initial = sink.scrolls.size
        val start = System.currentTimeMillis()
        touch.tick(start + 100)
        assertEquals("No repeat before the delay", initial, sink.scrolls.size)
        touch.tick(start + 400)
        assertTrue("Repeats after the delay", sink.scrolls.size > initial)
        val afterFirst = sink.scrolls.size
        touch.tick(start + 420)
        assertEquals("The interval is respected", afterFirst, sink.scrolls.size)
        touch.tick(start + 460)
        assertTrue(sink.scrolls.size > afterFirst)
    }

    @Test
    fun cursorShownSwitchesToInventoryMode() {
        val (touch, _, _) = engine()
        assertEquals("game", touch.mode)
        touch.setCursorShown(true)
        assertEquals(ControlLayout.INVENTORY_MODE, touch.mode)
        touch.setCursorShown(false)
        assertEquals("game", touch.mode)
    }

    @Test
    fun modifierGateBlocksTheAction() {
        val layout = ControlLayout(
            id = "gate", name = "Gate",
            modes = mapOf("game" to listOf(
                ControlElement(
                    "gated", x = 0.5f, y = 0.5f, actions = listOf(InputAction.Key(Glfw.KEY_SPACE)),
                    requiresModifier = Glfw.KEY_LEFT_SHIFT
                )
            ))
        )
        val (touch, _, tracker) = engine(layout)
        touch.down(TouchPointer(1, 0.5f, 0.5f))
        assertFalse("Without the modifier nothing happens", tracker.isKeyDown(Glfw.KEY_SPACE))
    }

    @Test
    fun layoutStoreFallsBackToTheDefault() {
        val dir = Files.createTempDirectory("layouts")
        val store = ControlLayoutStore(dir)
        assertEquals(ControlLayoutStore.DEFAULT_ID, store.list().first().id)
        assertEquals(ControlLayoutStore.DEFAULT_ID, store.getOrDefinitive("does-not-exist").id)

        val custom = DefaultLayouts.pvp().let { it.copy(id = "mine", name = "Mine") }
        store.save(custom)
        assertEquals(2, store.list().size)
        assertEquals("pvp", DefaultLayouts.byId("pvp")!!.id)
        assertTrue(store.delete("mine"))
        val refused = try {
            store.delete(ControlLayoutStore.DEFAULT_ID)
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue("The built-in default can never be deleted", refused)
    }

    @Test
    fun editorCoalescesGesturesAndUndoes() {
        val editor = LayoutEditor(DefaultLayouts.defaultTouch())
        assertFalse(editor.dirty)
        val jump = editor.current.element("jump")!!
        editor.move("game", "jump", 0.5f, 0.5f)
        editor.move("game", "jump", 0.6f, 0.6f)
        editor.move("game", "jump", 0.7f, 0.7f)
        editor.endGesture()
        assertEquals(0.7f, editor.current.element("jump")!!.x, 0.001f)
        // One undo must return to the position before the drag started, not one pixel back.
        editor.undo()
        assertEquals(jump.x, editor.current.element("jump")!!.x, 0.001f)
        assertTrue(editor.canRedo)
        editor.redo()
        assertEquals(0.7f, editor.current.element("jump")!!.x, 0.001f)
        assertTrue(editor.dirty)
        editor.markSaved()
        assertFalse(editor.dirty)
    }

    @Test
    fun layoutIdsAreUniqueAndSafe() {
        assertEquals("my-layout", LayoutIds.fromName("My Layout!", emptySet()))
        assertEquals("my-layout-2", LayoutIds.fromName("My Layout!", setOf("my-layout")))
        assertEquals("layout", LayoutIds.fromName("!!!", emptySet()))
    }

    @Test
    fun touchSinkNeverLeavesKeysStuckOnReleaseAll() {
        val layout = DefaultLayouts.defaultTouch()
        val (touch, _, tracker) = engine(layout)
        val jump = element(layout, "jump")
        val attack = element(layout, "attack")
        touch.down(TouchPointer(1, jump.x, jump.y))
        touch.down(TouchPointer(2, attack.x, attack.y))
        touch.releaseAll()
        assertFalse(tracker.anyDown)
        assertNull(tracker.heldKeys.firstOrNull())
        assertNotNull(tracker.heldButtons)
    }
}
