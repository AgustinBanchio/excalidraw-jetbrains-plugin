package com.agustinbanchio.excalidraw.file

import com.agustinbanchio.excalidraw.ExcalidrawIcons
import com.intellij.lang.Language
import com.intellij.json.JsonLanguage
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object ExcalidrawLanguage : Language(JsonLanguage.INSTANCE, "Excalidraw", "application/vnd.excalidraw+json")

object ExcalidrawFileType : LanguageFileType(ExcalidrawLanguage) {
    override fun getName(): String = "Excalidraw"

    override fun getDescription(): String = "Excalidraw drawing"

    override fun getDefaultExtension(): String = "excalidraw"

    override fun getIcon(): Icon = ExcalidrawIcons.File

    override fun getCharset(file: com.intellij.openapi.vfs.VirtualFile, content: ByteArray): String = Charsets.UTF_8.name()
}
