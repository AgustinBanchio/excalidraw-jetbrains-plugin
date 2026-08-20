package com.agustinbanchio.excalidraw.editor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighResolutionWheelStreamDetectorTest {
    private val detector = HighResolutionWheelStreamDetector(continuationWindowMs = 250)

    @Test
    fun `fractional precision identifies a high-resolution stream`() {
        assertTrue(detector.isHighResolutionStream(1_000, 0.125, 1))
    }

    @Test
    fun `integer samples immediately following precision remain in the stream`() {
        assertTrue(detector.isHighResolutionStream(1_000, 0.125, 1))
        assertTrue(detector.isHighResolutionStream(1_200, 1.0, 1))
        assertFalse(detector.isHighResolutionStream(1_300, 1.0, 1))
    }

    @Test
    fun `ordinary integer mouse wheels are not identified as high resolution`() {
        assertFalse(detector.isHighResolutionStream(1_000, -1.0, -1))
        assertFalse(detector.isHighResolutionStream(1_100, -1.0, -1))
    }

    @Test
    fun `reset ends a detected stream`() {
        assertTrue(detector.isHighResolutionStream(1_000, 0.25, 1))
        detector.reset()
        assertFalse(detector.isHighResolutionStream(1_100, 1.0, 1))
    }
}
