package com.agustinbanchio.excalidraw.editor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFilePreCloseCheck
import java.util.concurrent.CopyOnWriteArraySet

/** Keep the browser alive until its final async image export has reached disk. */
class ExcalidrawSaveGuard : VirtualFilePreCloseCheck {
    override fun canCloseFile(file: VirtualFile): Boolean =
        editors.filter { it.file == file }.all { it.saveBeforeClose() }

    // Implement explicitly: this default interface method is absent on 2025.3/2026.1.
    override fun canCloseFiles(files: Collection<VirtualFile>): Boolean = files.all(::canCloseFile)

    companion object {
        internal val editors = CopyOnWriteArraySet<ExcalidrawFileEditor>()
    }
}
