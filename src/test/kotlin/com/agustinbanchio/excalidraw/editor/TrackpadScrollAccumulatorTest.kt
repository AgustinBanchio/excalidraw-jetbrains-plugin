package com.agustinbanchio.excalidraw.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackpadScrollAccumulatorTest {
    @Test
    fun `coalesces samples before dispatch`() {
        val accumulator = TrackpadScrollAccumulator()
        accumulator.add(10.0, 0.0)
        accumulator.add(-2.0, 5.0)

        assertEquals(TrackpadScrollDispatch(1, 8.0, 5.0), accumulator.dispatchIfIdle())
    }

    @Test
    fun `keeps accumulating while a dispatch is in flight`() {
        val accumulator = TrackpadScrollAccumulator()
        accumulator.add(10.0, 0.0)
        val first = accumulator.dispatchIfIdle()!!
        accumulator.add(4.0, 7.0)
        accumulator.add(1.0, -2.0)

        assertNull(accumulator.dispatchIfIdle())
        assertFalse(accumulator.acknowledge(first.sequence + 1))
        assertTrue(accumulator.acknowledge(first.sequence))
        assertEquals(TrackpadScrollDispatch(2, 5.0, 5.0), accumulator.dispatchIfIdle())
    }

    @Test
    fun `reset drops pending and in-flight movement`() {
        val accumulator = TrackpadScrollAccumulator()
        accumulator.add(10.0, 20.0)
        val dispatch = accumulator.dispatchIfIdle()!!

        accumulator.reset()

        assertFalse(accumulator.acknowledge(dispatch.sequence))
        assertNull(accumulator.dispatchIfIdle())
    }
}
