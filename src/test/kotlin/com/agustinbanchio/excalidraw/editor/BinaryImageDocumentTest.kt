package com.agustinbanchio.excalidraw.editor

import com.agustinbanchio.excalidraw.file.DrawingFormat
import java.io.IOException
import java.util.Base64
import kotlin.test.*

class BinaryImageDocumentTest {
    private val original = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1)
    private val edited = original + byteArrayOf(2)

    @Test fun `writes binary bytes and clears dirty only after successful write`() {
        val state = BinaryImageDocument(original)
        state.update(Base64.getEncoder().encodeToString(edited))
        assertTrue(state.isModified)
        assertFailsWith<IOException> { state.save({ original }) { throw IOException("read only") } }
        assertTrue(state.isModified)
        state.save({ original }) { assertContentEquals(edited, it) }
        assertFalse(state.isModified)
    }

    @Test fun `rejects invalid output and refuses to overwrite external changes`() {
        val state = BinaryImageDocument(original)
        assertFailsWith<IllegalArgumentException> { state.update(Base64.getEncoder().encodeToString("{}".toByteArray())) }
        assertFalse(state.isModified)
        state.update(Base64.getEncoder().encodeToString(edited))
        assertFailsWith<IOException> { state.save({ original + byteArrayOf(3) }) { fail("Must not overwrite external changes") } }
        assertTrue(state.isModified)
        state.reload(edited)
        assertFalse(state.isModified)
    }

    @Test fun `recognises only the intended compound image extensions`() {
        assertEquals(DrawingFormat.PNG, DrawingFormat.fromName("drawing.EXCALIDRAW.PNG"))
        assertEquals(DrawingFormat.SVG, DrawingFormat.fromName("drawing.excalidraw.svg"))
        assertEquals(DrawingFormat.JSON, DrawingFormat.fromName("drawing.excalidraw"))
        assertNull(DrawingFormat.fromName("drawing.png"))
        assertNull(DrawingFormat.fromName("drawing.svg"))
        assertNull(DrawingFormat.fromName("drawing.excalidraw.png.bak"))
    }
}
