package com.agustinbanchio.excalidraw.editor

import kotlin.math.abs

internal class HighResolutionWheelStreamDetector(
    private val continuationWindowMs: Long,
) {
    private var lastHighResolutionEventAt: Long? = null

    fun isHighResolutionStream(timestampMs: Long, preciseRotation: Double, wheelRotation: Int): Boolean {
        val isHighResolution = preciseRotation.isFinite() &&
            abs(preciseRotation - wheelRotation.toDouble()) > ROTATION_EPSILON
        if (isHighResolution) {
            lastHighResolutionEventAt = timestampMs
            return true
        }

        val elapsed = timestampMs - (lastHighResolutionEventAt ?: return false)
        return elapsed in 0..continuationWindowMs
    }

    fun reset() {
        lastHighResolutionEventAt = null
    }

    private companion object {
        private const val ROTATION_EPSILON = 0.000_001
    }
}
