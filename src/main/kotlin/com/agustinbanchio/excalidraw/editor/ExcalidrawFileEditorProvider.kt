package com.agustinbanchio.excalidraw.editor

import com.agustinbanchio.excalidraw.file.ExcalidrawFileType
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp

class ExcalidrawFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.fileType == ExcalidrawFileType && JBCefApp.isSupported()

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        ExcalidrawFileEditor(project, file)

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        const val EDITOR_TYPE_ID = "excalidraw-editor"
    }
}
