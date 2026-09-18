package com.agustinbanchio.excalidraw.editor

import java.io.IOException
import java.util.Base64

/** A PNG is binary and must never pass through the IDE's text Document encoding. */
internal class BinaryImageDocument(initial: ByteArray) {
    private var saved = initial.copyOf()
    private var current = initial.copyOf()
    val isModified: Boolean get() = !current.contentEquals(saved)
    fun encoded(): String = Base64.getEncoder().encodeToString(current)

    fun update(encoded: String) {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size >= PNG_SIGNATURE.size && bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            "The editor did not produce a valid PNG. The original file has not been overwritten."
        }
        current = bytes
    }

    fun reload(bytes: ByteArray) {
        saved = bytes.copyOf()
        current = bytes.copyOf()
    }

    fun save(diskContents: () -> ByteArray, write: (ByteArray) -> Unit) {
        if (!isModified) return
        if (!diskContents().contentEquals(saved)) {
            throw IOException("The PNG changed on disk. Reopen it to load the external changes before saving.")
        }
        write(current)
        saved = current.copyOf()
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}
