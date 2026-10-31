package com.agustinbanchio.excalidraw.editor

import java.io.IOException
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class SaveBeforeCloseTest {
    private val original = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1)
    private val edited = original + byteArrayOf(2)

    @Test
    fun `immediate close waits for the image export and disk write`() {
        val document = BinaryImageDocument(original)
        var disk = original
        val saveFinished = CompletableFuture<Throwable?>()
        val waiting = CountDownLatch(1)
        val guard = SaveBeforeClose(
            awaitSave = { waiting.countDown(); saveFinished.get(5, TimeUnit.SECONDS) },
            confirmDiscard = { fail("A successful save must not ask to discard") },
            discardPending = { fail("A successful save must not discard pending work") },
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val closing = executor.submit<Boolean> { guard.canClose() }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            assertFalse(closing.isDone, "The browser must remain alive while exporting")
            assertFalse(guard.closeAllowed)

            // The asynchronous renderer has now delivered its final PNG to the IDE.
            document.update(Base64.getEncoder().encodeToString(edited))
            document.save({ disk }) { disk = it }
            saveFinished.complete(null)

            assertTrue(closing.get(5, TimeUnit.SECONDS))
            assertTrue(guard.closeAllowed)
            assertContentEquals(edited, disk)
            assertFalse(document.isModified)
        } finally {
            saveFinished.complete(TimeoutException("Test interrupted"))
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed save with Keep Editing preserves changes and allows retry`() {
        val document = BinaryImageDocument(original)
        document.update(Base64.getEncoder().encodeToString(edited))
        var disk = original
        var writable = false
        var prompts = 0
        val guard = SaveBeforeClose(
            awaitSave = {
                try {
                    document.save({ disk }) {
                        if (!writable) throw IOException("Read-only file")
                        disk = it
                    }
                    null
                } catch (error: IOException) { error }
            },
            confirmDiscard = { assertIs<IOException>(it); prompts++; false },
            discardPending = { fail("Keep Editing must retain pending changes") },
        )

        assertFalse(guard.canClose())
        assertFalse(guard.closeAllowed)
        assertTrue(document.isModified)
        assertContentEquals(original, disk)

        writable = true
        assertTrue(guard.canClose())
        assertTrue(guard.closeAllowed)
        assertFalse(document.isModified)
        assertContentEquals(edited, disk)
        assertEquals(1, prompts)
    }

    @Test
    fun `Discard Changes permits close and cancels pending work without writing`() {
        val document = BinaryImageDocument(original)
        document.update(Base64.getEncoder().encodeToString(edited))
        var pendingExport = true
        val guard = SaveBeforeClose(
            awaitSave = { IOException("Disk full") },
            confirmDiscard = { true },
            discardPending = { pendingExport = false },
        )

        assertTrue(guard.canClose())
        assertTrue(guard.closeAllowed, "Disposal must not retry a save after explicit discard")
        assertFalse(pendingExport)
        assertTrue(document.isModified, "Discard must not pretend a failed write succeeded")

        guard.markChanged()
        assertFalse(guard.closeAllowed, "A later edit invalidates previous permission to close")
    }

    @Test
    fun `renderer timeout keeps the editor open unless discard is confirmed`() {
        var discard = false
        var cancelled = false
        val guard = SaveBeforeClose(
            awaitSave = { TimeoutException("Renderer did not finish") },
            confirmDiscard = { assertIs<TimeoutException>(it); discard },
            discardPending = { cancelled = true },
        )
        assertFalse(guard.canClose())
        assertFalse(cancelled)
        discard = true
        assertTrue(guard.canClose())
        assertTrue(cancelled)
    }
}
