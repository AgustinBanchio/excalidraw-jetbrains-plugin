package com.agustinbanchio.excalidraw.scratch

import com.agustinbanchio.excalidraw.actions.ExcalidrawNewFileAction
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.scratch.ScratchFileCreationHelper
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project

class ExcalidrawScratchFileCreationHelper : ScratchFileCreationHelper() {
    override fun prepareText(project: Project, context: Context, dataContext: DataContext): Boolean {
        val template = FileTemplateManager.getInstance(project)
            .getInternalTemplate(ExcalidrawNewFileAction.TEMPLATE_NAME)

        context.text = template.getText(emptyMap<String, Any>())
        return true
    }
}
