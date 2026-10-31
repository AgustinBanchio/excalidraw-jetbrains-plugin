package com.agustinbanchio.excalidraw.editor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFilePreCloseCheck
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.VetoableProjectManagerListener
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Keep the browser alive until its final async image export has reached disk.
 * This experimental EP is intentional: beforeFileClosed cannot veto a close
 * when saving fails and the user chooses Keep Editing. Do not replace it with
 * a notification listener or suppress its Plugin Verifier warnings.
 */
class ExcalidrawSaveGuard : VirtualFilePreCloseCheck {
    override fun canCloseFile(file: VirtualFile): Boolean =
        editors.filter { it.file == file }.all { it.saveBeforeClose() }

    // Implement explicitly: this default interface method is absent on 2025.3/2026.1.
    override fun canCloseFiles(files: Collection<VirtualFile>): Boolean = files.all(::canCloseFile)

    companion object {
        internal val editors = CopyOnWriteArraySet<Entry>()
    }

    internal class Entry(val file: VirtualFile, val saveBeforeClose: () -> Boolean)

    internal class ProjectClose(
        private val project: Project,
        private val saveBeforeClose: () -> Boolean,
    ) : VetoableProjectManagerListener {
        override fun canClose(projectToClose: Project): Boolean = projectToClose != project || saveBeforeClose()
    }
}
