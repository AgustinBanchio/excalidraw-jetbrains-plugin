package com.agustinbanchio.excalidraw.file

import com.intellij.json.JsonParser
import com.intellij.json.JsonParserDefinition
import com.intellij.json.psi.impl.JsonFileImpl
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType

class ExcalidrawJsonParserDefinition : JsonParserDefinition() {
    override fun createParser(project: Project?): PsiParser = JsonParser()

    override fun createFile(fileViewProvider: FileViewProvider): PsiFile =
        JsonFileImpl(fileViewProvider, ExcalidrawLanguage)

    override fun getFileNodeType(): IFileElementType = FILE

    companion object {
        private val FILE = IFileElementType(ExcalidrawLanguage)
    }
}
