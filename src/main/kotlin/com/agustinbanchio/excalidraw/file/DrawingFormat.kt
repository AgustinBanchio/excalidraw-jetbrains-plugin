package com.agustinbanchio.excalidraw.file

enum class DrawingFormat(val bridgeName: String) {
    JSON("json"), SVG("svg"), PNG("png");

    companion object {
        fun fromName(name: String): DrawingFormat? = when {
            name.endsWith(".excalidraw.svg", ignoreCase = true) -> SVG
            name.endsWith(".excalidraw.png", ignoreCase = true) -> PNG
            name.endsWith(".excalidraw", ignoreCase = true) -> JSON
            else -> null
        }
    }
}
