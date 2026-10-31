package com.agustinbanchio.excalidraw.editor

import com.intellij.testFramework.LightVirtualFile
import com.intellij.openapi.project.Project
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ExcalidrawSaveGuardTest {
    private val entries = mutableListOf<ExcalidrawSaveGuard.Entry>()
    private val guard = ExcalidrawSaveGuard()

    @AfterTest
    fun unregisterEditors() {
        ExcalidrawSaveGuard.editors.removeAll(entries.toSet())
    }

    @Test
    fun `closing a file checks every split editor and leaves other files alone`() {
        val drawing = LightVirtualFile("drawing.excalidraw.png")
        val other = LightVirtualFile("other.excalidraw.svg")
        val saved = mutableListOf<String>()
        register(drawing) { saved.add("first split"); true }
        register(drawing) { saved.add("second split"); false }
        register(other) { fail("Closing one file must not close unrelated drawings") }

        assertFalse(guard.canCloseFile(drawing))
        assertEquals(listOf("first split", "second split"), saved)
    }

    @Test
    fun `Close All is vetoed on a failed save and succeeds after retry`() {
        val first = LightVirtualFile("first.excalidraw.svg")
        val second = LightVirtualFile("second.excalidraw.png")
        var saveFailure: Throwable? = IOException("Read-only file")
        var saves = 0
        val close = SaveBeforeClose(
            awaitSave = { saves++; saveFailure },
            confirmDiscard = { false },
            discardPending = { fail("Keep Editing must retain pending work") },
        )
        register(first) { true }
        register(second, close::canClose)

        assertFalse(guard.canCloseFiles(listOf(first, second)))
        assertFalse(close.closeAllowed)
        saveFailure = null
        assertTrue(guard.canCloseFiles(listOf(first, second)))
        assertTrue(close.closeAllowed)
        assertEquals(2, saves)
    }

    @Test
    fun `closed editors do not block subsequent closes`() {
        val drawing = LightVirtualFile("drawing.excalidraw.png")
        val entry = register(drawing) { false }
        assertFalse(guard.canCloseFile(drawing))
        ExcalidrawSaveGuard.editors.remove(entry)
        assertTrue(guard.canCloseFile(drawing))
        assertTrue(guard.canCloseFiles(emptyList()))
    }

    @Test
    fun `project shutdown uses the same save veto and explicit discard decision as tab closing`() {
        val project = project("drawing project")
        val otherProject = project("other project")
        var discard = false
        var saves = 0
        var cancelled = false
        val close = SaveBeforeClose(
            awaitSave = { saves++; IOException("Disk full") },
            confirmDiscard = { discard },
            discardPending = { cancelled = true },
        )
        val listener = ExcalidrawSaveGuard.ProjectClose(project, close::canClose)
        assertTrue(listener.canClose(otherProject))
        assertEquals(0, saves, "Closing another project must not save or prompt for this drawing")
        assertFalse(listener.canClose(project))
        assertFalse(cancelled)
        assertFalse(close.closeAllowed)

        discard = true
        assertTrue(listener.canClose(project))
        assertTrue(close.closeAllowed)
        assertTrue(cancelled)
        assertEquals(2, saves)
    }

    private fun project(name: String): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader, arrayOf(Project::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "equals" -> proxy === arguments?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> name
            else -> error("Unexpected project access: ${method.name}")
        }
    } as Project

    private fun register(file: LightVirtualFile, save: () -> Boolean): ExcalidrawSaveGuard.Entry =
        ExcalidrawSaveGuard.Entry(file, save).also {
            entries.add(it)
            ExcalidrawSaveGuard.editors.add(it)
        }
}
