package com.freefcc.app

/**
 * Process-local desired-state gate for one-shot Auto-FCC runs.
 *
 * A generation changes only on a real OFF -> ON or ON -> OFF transition.
 * Reasserting an already active request is idempotent. Workers must retain the
 * generation they started with and stop acting as soon as it is no longer
 * current, so an older worker can never finalize a newer request.
 */
internal class AutoFccRequestGate {
    data class Request(
        val generation: Long,
        val newlyRequested: Boolean
    )

    private var generation = 0L
    private var requested = false
    private var deliveredGeneration: Long? = null

    @Synchronized
    fun request(): Request {
        if (!requested) {
            generation++
            requested = true
            return Request(generation, newlyRequested = true)
        }
        return Request(generation, newlyRequested = false)
    }

    @Synchronized
    fun restoreRequested(): Long {
        if (!requested) {
            generation++
            requested = true
        }
        return generation
    }

    @Synchronized
    fun cancel(): Long {
        generation++
        requested = false
        deliveredGeneration = null
        return generation
    }

    @Synchronized
    fun rollbackNewRequest(request: Request) {
        if (request.newlyRequested && requested && generation == request.generation) {
            generation++
            requested = false
            deliveredGeneration = null
        }
    }

    /** Marks that this generation's ACTION_START reached the service instance. */
    @Synchronized
    fun markDelivered(candidate: Long): Boolean {
        if (!requested || generation != candidate) return false
        deliveredGeneration = candidate
        return true
    }

    @Synchronized
    fun currentGeneration(): Long? = generation.takeIf { requested }

    /** A worker may run only after Android delivered its start command. */
    @Synchronized
    fun currentDeliveredGeneration(): Long? =
        deliveredGeneration?.takeIf { requested && generation == it }

    @Synchronized
    fun isCurrent(candidate: Long): Boolean = requested && generation == candidate

    /** Returns true only when this worker owned and completed the active request. */
    @Synchronized
    fun complete(candidate: Long): Boolean {
        if (!requested || generation != candidate) return false
        generation++
        requested = false
        deliveredGeneration = null
        return true
    }
}
