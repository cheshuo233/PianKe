package app.pianke.develop

import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One worker (`Dispatchers.Default.limitedParallelism(1)`, launched in `scope`) developing frames in arrival order.
 * Owns rule 11 (capacity 2, reservations count) and rule 15 (failure -> [KeptReason]).
 *
 * `inFlight` = waiting + developing. Reservations are counted separately until [submit] turns one into a shot.
 * [tryReserve], [release], [submit] and [submitRecovered] run on the main thread, the decrement on the worker;
 * `reserved` and `inFlightState` change only under `lock`. The decrement happens before the outcome is emitted,
 * so `inFlight` already leaves that shot out when a collector sees the outcome.
 * Outcomes emitted before the first subscriber are dropped; a `Kept` frame stays in `pending/` regardless.
 */
class DevelopQueue(
    scope: CoroutineScope,
    private val store: PendingStore,
    private val developer: Developer,
) {
    private val lock = Any()
    private var reserved = 0
    private val inFlightState = MutableStateFlow(0)
    private val outcomeFlow = MutableSharedFlow<Pair<Shot, Outcome>>()

    /** Unbounded on purpose: capacity is enforced by the counters, and [submit] must never fail to send. */
    private val queued = Channel<Shot>(Channel.UNLIMITED)

    val inFlight: StateFlow<Int> = inFlightState.asStateFlow()
    val outcomes: SharedFlow<Pair<Shot, Outcome>> = outcomeFlow.asSharedFlow()

    init {
        @OptIn(ExperimentalCoroutinesApi::class)
        val worker = Dispatchers.Default.limitedParallelism(1)
        scope.launch(worker) { for (shot in queued) process(shot) }
    }

    /** True, with a reservation taken, only if `reserved + inFlight < CAPACITY`. The only reader of [CAPACITY]. */
    fun tryReserve(): Boolean = synchronized(lock) {
        if (reserved + inFlightState.value < CAPACITY) {
            reserved++
            true
        } else {
            false
        }
    }

    /** Returns a reservation after a failed capture. */
    fun release() = synchronized(lock) {
        check(reserved > 0) { "release without a reservation" }
        reserved--
    }

    /** Consumes one reservation and enqueues the shot; capacity was checked at [tryReserve]. */
    fun submit(shot: Shot) = synchronized(lock) {
        check(reserved > 0) { "submit without a reservation" }
        reserved--
        enqueue(shot)
    }

    /** Startup recovery: enqueue without reservations; may exceed [CAPACITY]. */
    fun submitRecovered(shots: List<Shot>) = synchronized(lock) { shots.forEach(::enqueue) }

    /** Callers hold [lock]. */
    private fun enqueue(shot: Shot) {
        inFlightState.update { it + 1 }
        queued.trySend(shot).getOrThrow()
    }

    /** Develops one shot; the worker never dies: every failure becomes `Kept`, only cancellation propagates. */
    private suspend fun process(shot: Shot) {
        val outcome: Outcome = try {
            Outcome.Saved(developer.develop(shot, store.frame(shot)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "develop ${shot.id} failed", e)
            Outcome.Kept(keptReason(e))
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "develop ${shot.id} ran out of memory", e)
            Outcome.Kept(keptReason(e))
        }
        if (outcome is Outcome.Saved) store.delete(shot)
        synchronized(lock) { inFlightState.update { it - 1 } }
        outcomeFlow.emit(shot to outcome)
    }

    /** Rule 15, the only mapping: ENOSPC anywhere in the cause chain -> STORAGE_FULL; anything else -> DEVELOP_FAILED. */
    private fun keptReason(failure: Throwable): KeptReason =
        if (generateSequence(failure) { it.cause }.any { it is ErrnoException && it.errno == OsConstants.ENOSPC }) {
            KeptReason.STORAGE_FULL
        } else {
            KeptReason.DEVELOP_FAILED
        }

    companion object {
        const val CAPACITY = 2
        private const val TAG = "DevelopQueue"
    }
}
