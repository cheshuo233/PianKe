package app.pianke.develop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One worker (`Dispatchers.Default.limitedParallelism(1)`, launched in [scope]) developing frames in arrival order.
 * Owns rule 11 (capacity 2, reservations count) and rule 15 (failure -> [KeptReason]).
 *
 * `inFlight` = waiting + developing. Reservations are counted separately until [submit] turns one into a shot.
 * The counters change before the outcome is emitted, so `inFlight` is already correct when a collector sees it.
 */
class DevelopQueue(
    private val scope: CoroutineScope,
    private val store: PendingStore,
    private val developer: Developer,
) {
    private val inFlightState = MutableStateFlow(0)
    private val outcomeFlow = MutableSharedFlow<Pair<Shot, Outcome>>(extraBufferCapacity = 8)

    val inFlight: StateFlow<Int> = inFlightState.asStateFlow()
    val outcomes: SharedFlow<Pair<Shot, Outcome>> = outcomeFlow.asSharedFlow()

    /** True and a reservation taken only if `reserved + inFlight < CAPACITY`. */
    fun tryReserve(): Boolean = TODO()

    /** Returns a reservation after a failed capture. */
    fun release(): Unit = TODO()

    /** Consumes one reservation and enqueues the shot. */
    fun submit(shot: Shot): Unit = TODO()

    /** Startup recovery: enqueue without reservations; may exceed [CAPACITY]. */
    fun submitRecovered(shots: List<Shot>): Unit = TODO()

    /** Develops one shot; on success deletes it from the store and emits Saved, on failure emits Kept(keptReason). */
    private suspend fun process(shot: Shot): Unit = TODO()

    /** Rule 15, the only mapping: ENOSPC anywhere in the cause chain -> STORAGE_FULL; anything else -> DEVELOP_FAILED. */
    private fun keptReason(failure: Throwable): KeptReason = TODO()

    companion object {
        const val CAPACITY = 2
    }
}
