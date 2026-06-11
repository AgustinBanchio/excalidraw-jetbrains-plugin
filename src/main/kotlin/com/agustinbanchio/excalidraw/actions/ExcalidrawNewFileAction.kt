package com.agustinbanchio.excalidraw.actions

import com.agustinbanchio.excalidraw.ExcalidrawIcons
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.NonEmptyInputValidator
import com.intellij.psi.PsiDirectory

class ExcalidrawNewFileAction : CreateFileFromTemplateAction(
    "Excalidraw Drawing",
    "Create a new Excalidraw drawing",
    ExcalidrawIcons.File,
), DumbAware {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder
            .setTitle("New Excalidraw Drawing")
            .addKind("Excalidraw Drawing", ExcalidrawIcons.File, TEMPLATE_NAME)
            .setValidator(NonEmptyInputValidator())
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String =
        "Create Excalidraw Drawing"

    companion object {
        const val TEMPLATE_NAME = "Excalidraw Drawing"
    }
}
