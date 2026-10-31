package pro.cslauncher.core.controls

/**
 * Undoable editing of a [ControlLayout].
 *
 * Two details are what make this feel right rather than merely functional:
 * * **gesture coalescing** — dragging a button produces hundreds of position updates but must be a
 *   single undo step, so consecutive edits sharing a `coalesceKey` are merged;
 * * **an explicit [markSaved]** — `dirty` is only ever true against the last saved state, so the
 *   "unsaved changes" prompt never lies.
 */
class LayoutEditor(initial: ControlLayout, private val maxHistory: Int = 100) {

    var current: ControlLayout = initial
        private set

    private var saved = initial
    private val undoStack = ArrayDeque<ControlLayout>()
    private val redoStack = ArrayDeque<ControlLayout>()
    private var lastCoalesceKey: String? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val dirty: Boolean get() = current != saved

    fun apply(coalesceKey: String? = null, change: (ControlLayout) -> ControlLayout) {
        val next = change(current)
        if (next == current) return
        if (coalesceKey == null || coalesceKey != lastCoalesceKey) {
            undoStack.addLast(current)
            if (undoStack.size > maxHistory) undoStack.removeFirst()
        }
        redoStack.clear()
        current = next
        lastCoalesceKey = coalesceKey
    }

    /** Call when the finger/mouse lifts, so the next drag starts a fresh undo step. */
    fun endGesture() {
        lastCoalesceKey = null
    }

    fun markSaved() {
        saved = current
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        current = previous
        lastCoalesceKey = null
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        current = next
        lastCoalesceKey = null
    }

    private fun mapMode(
        layout: ControlLayout,
        mode: String,
        transform: (List<ControlElement>) -> List<ControlElement>
    ) = layout.copy(modes = layout.modes + (mode to transform(layout.modes[mode].orEmpty())))

    fun update(mode: String, id: String, coalesce: Boolean = false, transform: (ControlElement) -> ControlElement) =
        apply(if (coalesce) "update:$mode:$id" else null) { layout ->
            mapMode(layout, mode) { elements -> elements.map { if (it.id == id) transform(it) else it } }
        }

    /** Moves a control, optionally snapping to a grid. Out-of-screen positions are clamped. */
    fun move(mode: String, id: String, x: Float, y: Float, grid: Float? = null) =
        update(mode, id, coalesce = true) { element ->
            val snappedX = grid?.let { g -> (Math.round(x / g) * g) } ?: x
            val snappedY = grid?.let { g -> (Math.round(y / g) * g) } ?: y
            element.copy(x = snappedX.coerceIn(0f, 1f), y = snappedY.coerceIn(0f, 1f))
        }

    fun resize(mode: String, id: String, widthDp: Float, heightDp: Float) =
        update(mode, id, coalesce = true) {
            it.copy(widthDp = widthDp.coerceIn(16f, 480f), heightDp = heightDp.coerceIn(16f, 480f))
        }

    fun add(mode: String, element: ControlElement) = apply { layout ->
        mapMode(layout, mode) { it + element.copy(id = uniqueId(layout, mode, element.id)) }
    }

    fun delete(mode: String, id: String) = apply { layout ->
        mapMode(layout, mode) { elements -> elements.filterNot { it.id == id } }
    }

    fun duplicate(mode: String, id: String) = apply { layout ->
        val source = layout.modes[mode]?.firstOrNull { it.id == id } ?: return@apply layout
        mapMode(layout, mode) {
            it + source.copy(
                id = uniqueId(layout, mode, source.id),
                x = (source.x + 0.04f).coerceAtMost(1f),
                y = (source.y + 0.04f).coerceAtMost(1f)
            )
        }
    }

    /**
     * Nudges a control with the arrow keys. A larger step with shift keeps keyboard editing usable
     * without a mouse.
     */
    fun nudge(mode: String, id: String, dx: Float, dy: Float) = update(mode, id, coalesce = true) {
        it.copy(
            x = (it.x + dx).coerceIn(0f, 1f),
            y = (it.y + dy).coerceIn(0f, 1f)
        )
    }

    /** Restores one control to the value it has in [defaults]; the rest of the layout is untouched. */
    fun resetElement(mode: String, id: String, defaults: ControlLayout) {
        val default = defaults.modes[mode]?.firstOrNull { it.id == id } ?: return
        update(mode, id) { default }
    }

    /** Restores every element to the defaults, keeping the user's id and title. */
    fun resetAll(defaults: ControlLayout) = apply { defaults.copy(id = it.id, name = it.name) }

    fun addMode(name: String) = apply { layout ->
        if (name in layout.modes) layout else layout.copy(modes = layout.modes + (name to emptyList()))
    }

    fun removeMode(name: String) = apply { layout ->
        if (name == layout.defaultMode || layout.modes.size <= 1) layout
        else layout.copy(modes = layout.modes - name)
    }

    fun rename(name: String) = apply { it.copy(name = name.take(48)) }

    /** Exports the current state, refusing to hand back something the game would reject. */
    fun validated(): ControlLayout {
        val problems = ControlLayoutCodec.validate(current)
        if (problems.isNotEmpty()) throw LayoutValidationException(problems)
        return current
    }

    private fun uniqueId(layout: ControlLayout, mode: String, base: String): String {
        val ids = layout.modes[mode].orEmpty().map { it.id }.toSet()
        if (base !in ids) return base
        var i = 2
        while ("$base-$i" in ids) i++
        return "$base-$i"
    }
}

/** Turns a human name into a valid, unique layout id. */
object LayoutIds {

    fun fromName(name: String, taken: Set<String>): String {
        val base = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(32)
            .ifBlank { "layout" }
        var id = base
        var n = 2
        while (id in taken) id = "$base-${n++}"
        return id
    }
}
