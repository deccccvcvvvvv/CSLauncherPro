package pro.cslauncher.core.controls

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.int
import pro.cslauncher.core.util.parseJsonObject

@Serializable
enum class ElementKind { BUTTON, JOYSTICK, TOUCHPAD }

@Serializable
enum class ButtonBehavior {
    /** Held while the finger is down (movement, attack). */
    HOLD,

    /** Toggles on each tap (sneak, sprint). */
    TOGGLE,

    /** Single press-and-release (inventory, chat). */
    TAP,

    /** Fires only on a double tap. */
    DOUBLE_TAP,

    /** Repeats while held, e.g. scrolling. */
    REPEAT
}

@Serializable
enum class Visibility {
    ALWAYS,

    /** Only while the game does not show a cursor. */
    IN_GAME,

    /** Only while the game shows a cursor (inventory, chat). */
    IN_MENU
}

@Serializable
enum class JoystickRole { MOVEMENT, LOOK }

@Serializable
data class JoystickConfig(
    val role: JoystickRole = JoystickRole.MOVEMENT,
    val deadZone: Float = 0.18f,
    val sensitivity: Float = 1f,
    /** The stick appears wherever the thumb lands instead of a fixed centre. */
    val floating: Boolean = false,
    /** Deflection above which auto-sprint engages; 0 disables it. */
    val sprintThreshold: Float = 0.94f,
    val showIndicator: Boolean = true,
    val forward: Int = Glfw.KEY_W,
    val back: Int = Glfw.KEY_S,
    val left: Int = Glfw.KEY_A,
    val right: Int = Glfw.KEY_D,
    val sprintKey: Int = Glfw.KEY_LEFT_CONTROL
)

@Serializable
data class TouchpadConfig(val sensitivity: Float = 1f, val tapToClick: Boolean = true)

/**
 * One on-screen control. Positions are normalised to the safe area so a layout looks the same on every
 * screen shape, and sizes are in dp so it also looks the same on every density.
 */
@Serializable
@SerialName("element")
data class ControlElement(
    val id: String,
    val kind: ElementKind = ElementKind.BUTTON,
    val label: String = "",
    val icon: String? = null,
    /** Centre of the control, 0..1 within the safe area. */
    val x: Float,
    val y: Float,
    val widthDp: Float = 56f,
    val heightDp: Float = 56f,
    val opacity: Float = 0.55f,
    /** Corner radius as a fraction of the smaller side (0 = square-ish, 0.5 = circle). */
    val cornerRadius: Float = 0.3f,
    val colorArgb: Long = 0xFF1E1E26,
    val actions: List<InputAction> = emptyList(),
    val behavior: ButtonBehavior = ButtonBehavior.HOLD,
    val repeatDelayMs: Int = 350,
    val repeatIntervalMs: Int = 70,
    val doubleTapMs: Int = 300,
    val visibility: Visibility = Visibility.ALWAYS,
    /** Swiping across the control fires it repeatedly (hotbar selection). */
    val swipeable: Boolean = false,
    /** Key that must be held for this control to do anything (a shift modifier button). */
    val requiresModifier: Int? = null,
    val joystick: JoystickConfig? = null,
    val touchpad: TouchpadConfig? = null
) {
    val isVisibleInGame: Boolean get() = visibility != Visibility.IN_MENU
    val isVisibleInMenu: Boolean get() = visibility != Visibility.IN_GAME

    fun visible(cursorShown: Boolean): Boolean = if (cursorShown) isVisibleInMenu else isVisibleInGame

    fun primaryActionLabel(): String = actions.firstOrNull()?.describe() ?: kind.name.lowercase()
}

@Serializable
data class ControlLayout(
    val formatVersion: Int = ControlLayoutCodec.FORMAT,
    val id: String,
    val name: String,
    /** Mode name → controls. Modes are switched by `InputAction.SwitchMode` or automatically. */
    val modes: Map<String, List<ControlElement>>,
    val defaultMode: String = "game",
    /** Switch to the "inventory" mode automatically when the game shows a cursor. */
    val autoModes: Boolean = true
) {
    fun elements(mode: String): List<ControlElement> = modes[mode] ?: modes[defaultMode].orEmpty()

    fun element(id: String): ControlElement? = modes.values.flatten().firstOrNull { it.id == id }

    val modeNames: List<String> get() = modes.keys.toList()

    companion object {
        const val INVENTORY_MODE = "inventory"
        const val GAME_MODE = "game"
    }
}

class LayoutValidationException(val problems: List<String>) : Exception(problems.joinToString("; "))

/**
 * Reads and writes layouts.
 *
 * A layout is user data that the game will act on, so validation is strict and every problem is
 * reported at once instead of failing on the first one. Unknown formats are refused rather than
 * half-loaded, because a partially understood layout puts buttons in wrong places.
 */
object ControlLayoutCodec {

    const val FORMAT = 1
    const val MAX_ELEMENTS_PER_MODE = 160

    private val MODE_NAME = Regex("^[a-z0-9_-]{1,24}$")

    fun encode(layout: ControlLayout): String = CsJson.encodeToString(ControlLayout.serializer(), layout)

    fun decode(text: String): ControlLayout {
        val root = parseJsonObject(text, "Control layout")
        val version = root.int("formatVersion")
            ?: throw LayoutValidationException(listOf("Missing formatVersion"))
        if (version > FORMAT) {
            throw LayoutValidationException(listOf("Layout format $version is newer than supported ($FORMAT)"))
        }
        val layout = try {
            CsJson.decodeFromJsonElement(ControlLayout.serializer(), root)
        } catch (e: Exception) {
            throw LayoutValidationException(listOf("Malformed layout: ${e.message?.take(200)}"))
        }
        val problems = validate(layout)
        if (problems.isNotEmpty()) throw LayoutValidationException(problems)
        return layout
    }

    fun validate(layout: ControlLayout): List<String> = buildList {
        if (layout.name.isBlank() || layout.name.length > 48) add("Layout name must be 1-48 characters")
        if (!MODEL_ID.matches(layout.id)) add("Invalid layout id '${layout.id}'")
        if (layout.modes.isEmpty()) add("Layout has no modes")
        if (layout.defaultMode !in layout.modes) add("Default mode '${layout.defaultMode}' does not exist")

        for ((mode, elements) in layout.modes) {
            if (!MODE_NAME.matches(mode)) add("Invalid mode name '$mode'")
            if (elements.size > MAX_ELEMENTS_PER_MODE) add("Mode '$mode' has more than $MAX_ELEMENTS_PER_MODE controls")
            elements.groupBy { it.id }.filter { it.value.size > 1 }.keys
                .forEach { add("Duplicate control id '$it' in '$mode'") }

            for (e in elements) {
                if (!ELEMENT_ID.matches(e.id)) add("Invalid control id '${e.id}'")
                if (e.x !in 0f..1f || e.y !in 0f..1f) add("${e.id}: position outside the screen")
                if (e.widthDp !in 16f..480f || e.heightDp !in 16f..480f) add("${e.id}: size out of range")
                if (e.opacity !in 0f..1f) add("${e.id}: opacity out of range")
                if (e.cornerRadius !in 0f..0.5f) add("${e.id}: corner radius out of range")
                if (e.label.length > 24) add("${e.id}: label too long")
                if (e.kind == ElementKind.JOYSTICK && e.joystick == null) add("${e.id}: joystick without configuration")
                if (e.kind == ElementKind.TOUCHPAD && e.touchpad == null) add("${e.id}: touchpad without configuration")

                e.joystick?.let { j ->
                    if (j.deadZone !in 0f..0.9f) add("${e.id}: dead zone out of range")
                    if (j.sensitivity !in 0.1f..5f) add("${e.id}: joystick sensitivity out of range")
                    listOf(j.forward, j.back, j.left, j.right, j.sprintKey).filterNot(Glfw::isValidKey)
                        .forEach { add("${e.id}: invalid key $it") }
                }
                e.requiresModifier?.let { if (!Glfw.isValidKey(it)) add("${e.id}: invalid modifier key") }

                for (action in e.actions) {
                    when (action) {
                        is InputAction.Key -> if (!Glfw.isValidKey(action.key)) add("${e.id}: invalid key code ${action.key}")
                        is InputAction.Mouse -> if (!Glfw.isValidMouse(action.button)) add("${e.id}: invalid mouse button ${action.button}")
                        is InputAction.SwitchMode -> if (action.mode !in layout.modes) add("${e.id}: switches to unknown mode '${action.mode}'")
                        is InputAction.Scroll -> if (action.dy == 0f || action.dy !in -10f..10f) add("${e.id}: invalid scroll amount")
                        else -> {}
                    }
                }
            }
        }
    }

    private val MODEL_ID = Regex("^[A-Za-z][A-Za-z0-9._-]{0,63}$")
    private val ELEMENT_ID = Regex("^[A-Za-z0-9._-]{1,40}$")
}

/**
 * The layouts shipped with the launcher.
 *
 * Every one of them is a real, playable arrangement rather than a placeholder: default touch, compact,
 * PvP, creative and a controller-hybrid layout with no movement controls at all. Positions are chosen
 * to stay inside the safe area on a 20:9 phone with a notch.
 */
object DefaultLayouts {

    private fun key(
        id: String,
        label: String,
        x: Float,
        y: Float,
        k: Int,
        w: Float = 52f,
        h: Float = 52f,
        behavior: ButtonBehavior = ButtonBehavior.HOLD,
        vis: Visibility = Visibility.ALWAYS,
        swipe: Boolean = false
    ) = ControlElement(
        id, ElementKind.BUTTON, label, null, x, y, w, h,
        actions = listOf(InputAction.Key(k)), behavior = behavior, visibility = vis, swipeable = swipe
    )

    private fun mouse(id: String, label: String, x: Float, y: Float, button: Int, size: Float = 56f) =
        ControlElement(
            id, ElementKind.BUTTON, label, null, x, y, size, size,
            actions = listOf(InputAction.Mouse(button)), visibility = Visibility.IN_GAME
        )

    private fun command(id: String, label: String, x: Float, y: Float, action: InputAction, w: Float = 44f, h: Float = 40f) =
        ControlElement(id, ElementKind.BUTTON, label, null, x, y, w, h, actions = listOf(action), behavior = ButtonBehavior.TAP)

    private fun hotbar(y: Float = 0.955f): List<ControlElement> = (1..9).map { i ->
        ControlElement(
            "hotbar$i", ElementKind.BUTTON, "", null, 0.5f + (i - 5) * 0.046f, y, 40f, 40f,
            opacity = 0.0f, actions = listOf(InputAction.Key(Glfw.KEY_0 + i)),
            behavior = ButtonBehavior.TAP, visibility = Visibility.IN_GAME, swipeable = true
        )
    }

    private fun joystick(x: Float = 0.14f, y: Float = 0.72f, floating: Boolean = false, size: Float = 150f) =
        ControlElement(
            "move", ElementKind.JOYSTICK, "", null, x, y, size, size, opacity = 0.5f,
            visibility = Visibility.IN_GAME, joystick = JoystickConfig(floating = floating)
        )

    private val menuRow = listOf(
        key("esc", "Esc", 0.04f, 0.07f, Glfw.KEY_ESCAPE, 46f, 36f, ButtonBehavior.TAP),
        key("chat", "T", 0.10f, 0.07f, Glfw.KEY_T, 40f, 36f, ButtonBehavior.TAP, Visibility.IN_GAME),
        key("perspective", "F5", 0.16f, 0.07f, Glfw.KEY_F5, 40f, 36f, ButtonBehavior.TAP, Visibility.IN_GAME),
        key("debug", "F3", 0.22f, 0.07f, Glfw.KEY_F3, 40f, 36f, ButtonBehavior.TAP, Visibility.IN_GAME),
        command("keyboard", "⌨", 0.96f, 0.07f, InputAction.ShowKeyboard, 40f, 36f),
        command("menu", "☰", 0.90f, 0.07f, InputAction.LauncherMenu, 40f, 36f)
    )

    private val gameButtons = listOf(
        key("jump", "Jump", 0.89f, 0.72f, Glfw.KEY_SPACE, 64f, 64f, vis = Visibility.IN_GAME),
        key("sneak", "Sneak", 0.14f, 0.40f, Glfw.KEY_LEFT_SHIFT, 52f, 44f, ButtonBehavior.TOGGLE, Visibility.IN_GAME),
        key("inventory", "Inv", 0.79f, 0.88f, Glfw.KEY_E, 46f, 40f, ButtonBehavior.TAP, Visibility.IN_GAME),
        key("drop", "Q", 0.72f, 0.88f, Glfw.KEY_Q, 40f, 40f, ButtonBehavior.TAP, Visibility.IN_GAME),
        key("swap", "F", 0.86f, 0.88f, Glfw.KEY_F, 40f, 40f, ButtonBehavior.TAP, Visibility.IN_GAME),
        mouse("attack", "⚔", 0.80f, 0.60f, Glfw.MOUSE_LEFT),
        mouse("use", "✋", 0.80f, 0.42f, Glfw.MOUSE_RIGHT)
    )

    private val inventoryMode = listOf(
        key("inv_close", "Close", 0.92f, 0.10f, Glfw.KEY_E, 56f, 40f, ButtonBehavior.TAP),
        key("inv_shift", "Shift", 0.06f, 0.75f, Glfw.KEY_LEFT_SHIFT, 56f, 44f, ButtonBehavior.HOLD),
        key("inv_esc", "Esc", 0.06f, 0.10f, Glfw.KEY_ESCAPE, 46f, 36f, ButtonBehavior.TAP),
        command("inv_keyboard", "⌨", 0.06f, 0.30f, InputAction.ShowKeyboard),
        ControlElement(
            "inv_scroll_up", ElementKind.BUTTON, "▲", null, 0.94f, 0.45f, 44f, 40f,
            actions = listOf(InputAction.Scroll(1f)), behavior = ButtonBehavior.REPEAT
        ),
        ControlElement(
            "inv_scroll_down", ElementKind.BUTTON, "▼", null, 0.94f, 0.60f, 44f, 40f,
            actions = listOf(InputAction.Scroll(-1f)), behavior = ButtonBehavior.REPEAT
        ),
        command("inv_mouse", "Mouse", 0.06f, 0.52f, InputAction.ToggleVirtualMouse, 56f, 40f),
        command("inv_menu", "☰", 0.80f, 0.10f, InputAction.LauncherMenu, 40f, 36f)
    )

    private val chatMode = listOf(
        command("chat_keyboard", "⌨ Type", 0.5f, 0.12f, InputAction.ShowKeyboard, 96f, 40f),
        key("chat_send", "Send", 0.90f, 0.12f, Glfw.KEY_ENTER, 64f, 40f, ButtonBehavior.TAP),
        key("chat_esc", "Esc", 0.06f, 0.12f, Glfw.KEY_ESCAPE, 46f, 40f, ButtonBehavior.TAP),
        key("chat_up", "↑", 0.80f, 0.12f, Glfw.KEY_UP, 40f, 40f, ButtonBehavior.REPEAT),
        key("chat_tab", "Tab", 0.70f, 0.12f, Glfw.KEY_TAB, 46f, 40f, ButtonBehavior.TAP),
        command("chat_menu", "☰", 0.30f, 0.12f, InputAction.LauncherMenu, 40f, 40f)
    )

    fun defaultTouch() = ControlLayout(
        id = "default-touch",
        name = "Default Touch",
        modes = mapOf(
            "game" to (listOf(joystick()) + gameButtons + menuRow + hotbar()),
            "inventory" to inventoryMode,
            "chat" to chatMode
        )
    )

    fun compact() = ControlLayout(
        id = "compact",
        name = "Compact",
        modes = mapOf(
            "game" to (
                listOf(joystick(size = 120f)) +
                    gameButtons.filter { it.id in setOf("jump", "sneak", "inventory") }
                        .map { it.copy(widthDp = it.widthDp * 0.85f, heightDp = it.heightDp * 0.85f) } +
                    menuRow.take(2) + menuRow.takeLast(2) + hotbar()
                ),
            "inventory" to inventoryMode,
            "chat" to chatMode
        )
    )

    fun pvp() = ControlLayout(
        id = "pvp",
        name = "PvP",
        modes = mapOf(
            "game" to (
                listOf(joystick(floating = true)) +
                    gameButtons.map { if (it.id == "attack") it.copy(widthDp = 72f, heightDp = 72f) else it } +
                    key("sprint", "Sprint", 0.14f, 0.52f, Glfw.KEY_LEFT_CONTROL, 52f, 44f, ButtonBehavior.TOGGLE, Visibility.IN_GAME) +
                    menuRow + hotbar()
                ),
            "inventory" to inventoryMode,
            "chat" to chatMode
        )
    )

    fun creative() = ControlLayout(
        id = "creative",
        name = "Creative",
        modes = mapOf(
            "game" to (
                listOf(joystick()) +
                    gameButtons +
                    key("fly_down", "▼", 0.89f, 0.88f, Glfw.KEY_LEFT_SHIFT, 52f, 44f, vis = Visibility.IN_GAME) +
                    mouse("pick", "Pick", 0.70f, 0.42f, Glfw.MOUSE_MIDDLE, 48f) +
                    menuRow + hotbar()
                ),
            "inventory" to inventoryMode,
            "chat" to chatMode
        )
    )

    /** For a physical controller: no movement stick, only the buttons that cannot be reached. */
    fun controllerHybrid() = ControlLayout(
        id = "controller-hybrid",
        name = "Controller Hybrid",
        modes = mapOf("game" to (menuRow + hotbar()), "inventory" to inventoryMode, "chat" to chatMode)
    )

    fun all(): List<ControlLayout> = listOf(defaultTouch(), compact(), pvp(), creative(), controllerHybrid())

    fun byId(id: String): ControlLayout? = all().firstOrNull { it.id == id }
}
