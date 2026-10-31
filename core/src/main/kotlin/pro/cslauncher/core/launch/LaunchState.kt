package pro.cslauncher.core.launch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class LaunchState {
    IDLE, VALIDATING, PREPARING_RUNTIME, PREPARING_RENDERER, INITIALIZING_GAME, LOADING_GAME, RUNNING, EXITED, FAILED;

    val isRunning: Boolean get() = this == INITIALIZING_GAME || this == LOADING_GAME || this == RUNNING
    val isTerminal: Boolean get() = this == EXITED || this == FAILED
}

data class LaunchStatus(
    val state: LaunchState,
    /** The stage that failed, so the UI can point at the real problem instead of "something broke". */
    val failedAt: LaunchState? = null,
    val message: String? = null
)

class IllegalLaunchTransition(from: LaunchState, to: LaunchState) :
    IllegalStateException("Illegal launch transition $from -> $to")

/**
 * The explicit launch state machine.
 *
 * The reason this is a class rather than a bare enum: a launcher's worst bug class is a stuck state
 * ("Play" does nothing forever). Every transition is validated, every failure records the stage it
 * happened in, and [reset] always returns to a usable IDLE.
 */
class LaunchStateMachine {

    private val order = listOf(
        LaunchState.IDLE,
        LaunchState.VALIDATING,
        LaunchState.PREPARING_RUNTIME,
        LaunchState.PREPARING_RENDERER,
        LaunchState.INITIALIZING_GAME,
        LaunchState.LOADING_GAME,
        LaunchState.RUNNING,
        LaunchState.EXITED
    )

    private val _status = MutableStateFlow(LaunchStatus(LaunchState.IDLE))
    val status: StateFlow<LaunchStatus> = _status.asStateFlow()

    val state: LaunchState get() = _status.value.state
    val message: String? get() = _status.value.message

    @Synchronized
    fun advance(to: LaunchState, message: String? = null) {
        val from = state
        val ok = when (to) {
            LaunchState.FAILED -> from != LaunchState.EXITED && from != LaunchState.FAILED && from != LaunchState.IDLE
            LaunchState.EXITED -> from in setOf(LaunchState.INITIALIZING_GAME, LaunchState.LOADING_GAME, LaunchState.RUNNING)
            LaunchState.VALIDATING -> from in setOf(LaunchState.IDLE, LaunchState.EXITED, LaunchState.FAILED)
            // Forward only. A jump is legitimate (log detection moves straight to RUNNING when the
            // title screen appears); going backwards, or moving after a terminal state, is not.
            else -> order.indexOf(to) > order.indexOf(from) &&
                from != LaunchState.EXITED && from != LaunchState.FAILED
        }
        if (!ok) throw IllegalLaunchTransition(from, to)
        _status.value = LaunchStatus(to, message = message)
    }

    /** Tries to advance, ignoring an illegal transition. Used where a late callback must not throw. */
    @Synchronized
    fun advanceIfAllowed(to: LaunchState, message: String? = null): Boolean = try {
        advance(to, message)
        true
    } catch (_: IllegalLaunchTransition) {
        false
    }

    /** Updates the status message without changing state (e.g. "Unpacking Java 21…"). */
    @Synchronized
    fun progress(message: String) {
        _status.value = _status.value.copy(message = message)
    }

    @Synchronized
    fun fail(message: String) {
        val from = state
        if (from == LaunchState.FAILED || from == LaunchState.EXITED || from == LaunchState.IDLE) return
        _status.value = LaunchStatus(LaunchState.FAILED, failedAt = from, message = message)
    }

    @Synchronized
    fun reset() {
        _status.value = LaunchStatus(LaunchState.IDLE)
    }
}

/**
 * Per-profile launch lock. A second Play press while the game is starting must be ignored, not
 * started — two JVMs in one process is unrecoverable.
 */
object LaunchLocks {

    private val held = ConcurrentHashMap.newKeySet<String>()

    fun tryAcquire(profileId: String): Boolean = held.add(profileId)

    fun release(profileId: String) {
        held.remove(profileId)
    }

    fun isHeld(profileId: String): Boolean = profileId in held

    val heldProfiles: Set<String> get() = held.toSet()
}
