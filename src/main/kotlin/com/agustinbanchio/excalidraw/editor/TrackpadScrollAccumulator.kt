package com.agustinbanchio.excalidraw.editor

internal data class TrackpadScrollDispatch(
    val sequence: Long,
    val deltaX: Double,
    val deltaY: Double,
)

internal class TrackpadScrollAccumulator {
    private var nextSequence = 1L
    private var pendingDeltaX = 0.0
    private var pendingDeltaY = 0.0

    var inFlightSequence: Long? = null
        private set

    val hasPending: Boolean
        get() = pendingDeltaX != 0.0 || pendingDeltaY != 0.0

    fun add(deltaX: Double, deltaY: Double) {
        pendingDeltaX += deltaX
        pendingDeltaY += deltaY
    }

    fun dispatchIfIdle(): TrackpadScrollDispatch? {
        if (inFlightSequence != null || !hasPending) return null

        val dispatch = TrackpadScrollDispatch(nextSequence++, pendingDeltaX, pendingDeltaY)
        pendingDeltaX = 0.0
        pendingDeltaY = 0.0
        inFlightSequence = dispatch.sequence
        return dispatch
    }

    fun acknowledge(sequence: Long): Boolean {
        if (sequence != inFlightSequence) return false

        inFlightSequence = null
        return true
    }

    fun reset() {
        pendingDeltaX = 0.0
        pendingDeltaY = 0.0
        inFlightSequence = null
    }
}
