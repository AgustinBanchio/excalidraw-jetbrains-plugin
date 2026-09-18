package com.agustinbanchio.excalidraw.actions

import com.agustinbanchio.excalidraw.ExcalidrawIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile

abstract class ExcalidrawNewImageAction(private val extension: String) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && event.getData(LangDataKeys.IDE_VIEW) != null
        event.presentation.icon = ExcalidrawIcons.File
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val directory = event.getData(LangDataKeys.IDE_VIEW)?.orChooseDirectory?.virtualFile ?: return
        val name = Messages.showInputDialog(
            project, "File name:", "New Excalidraw ${extension.uppercase()} Drawing", ExcalidrawIcons.File, "drawing",
            object : InputValidator {
                override fun checkInput(inputString: String): Boolean = inputString.trim().let {
                    it.isNotEmpty() && it != "." && it != ".." && !it.contains(Regex("""[\\/:*?"<>|]"""))
                }
                override fun canClose(inputString: String): Boolean = checkInput(inputString)
            },
        )?.trim() ?: return
        val suffix = ".excalidraw.$extension"
        val fileName = if (name.endsWith(suffix, ignoreCase = true)) name else name + suffix
        if (directory.findChild(fileName) != null) {
            Messages.showErrorDialog(project, "A file named $fileName already exists.", "Cannot Create Drawing")
            return
        }
        try {
            lateinit var file: VirtualFile
            WriteCommandAction.runWriteCommandAction(project, Runnable {
                file = directory.createChildData(this, fileName)
            })
            // Opening an editor can initialize JCEF and must happen outside the write action.
            // Empty image files open as a blank scene, as in the VS Code extension.
            FileEditorManager.getInstance(project).openFile(file, true)
        } catch (error: Exception) {
            Messages.showErrorDialog(project, error.message ?: "Unable to create the drawing.", "Cannot Create Drawing")
        }
    }
}

class ExcalidrawNewSvgAction : ExcalidrawNewImageAction("svg")
class ExcalidrawNewPngAction : ExcalidrawNewImageAction("png")
