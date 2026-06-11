package com.agustinbanchio.excalidraw.editor

import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

class ExcalidrawResourceSchemeHandlerFactory : CefSchemeHandlerFactory {
    override fun create(browser: CefBrowser, frame: CefFrame, schemeName: String, request: CefRequest): CefResourceHandler {
        val uri = URI(request.url)
        val stream = if (isTrustedFrontendUrl(request.url)) openResource(uri.path) else null

        return object : CefResourceHandler {
            override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
                callback.Continue()
                return true
            }

            override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef?) {
                response.mimeType = mimeType(uri.path)
                response.status = if (stream == null) 404 else 200
            }

            override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback): Boolean {
                if (stream == null) {
                    bytesRead.set(0)
                    return false
                }

                return try {
                    val read = stream.read(dataOut, 0, bytesToRead)
                    if (read >= 0) {
                        bytesRead.set(read)
                        true
                    } else {
                        bytesRead.set(0)
                        closeQuietly(stream)
                        false
                    }
                } catch (_: IOException) {
                    bytesRead.set(0)
                    closeQuietly(stream)
                    false
                }
            }

            override fun cancel() {
                closeQuietly(stream)
            }
        }
    }

    companion object {
        private const val DOMAIN = "excalidraw-jetbrains-plugin"
        private const val ORIGIN = "https://$DOMAIN"
        const val INDEX_URL = "$ORIGIN/index.html"

        private val registered = AtomicBoolean(false)

        fun register(): Boolean {
            val hasIndex = ExcalidrawResourceSchemeHandlerFactory::class.java
                .getResource("/excalidraw-web/index.html") != null

            if (!hasIndex) return false

            if (registered.compareAndSet(false, true)) {
                CefApp.getInstance().registerSchemeHandlerFactory(
                    "https",
                    DOMAIN,
                    ExcalidrawResourceSchemeHandlerFactory(),
                )
            }

            return true
        }

        fun isTrustedFrontendUrl(url: String?): Boolean {
            if (url == null) return false

            return try {
                val uri = URI(url)
                uri.scheme == "https" && uri.host == DOMAIN && uri.port == -1
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        private fun openResource(path: String): InputStream? {
            val normalizedPath = path.ifBlank { "/index.html" }
            if (normalizedPath.contains('\\') || normalizedPath.split('/').any { it == ".." }) {
                return null
            }

            return ExcalidrawResourceSchemeHandlerFactory::class.java
                .getResourceAsStream("/excalidraw-web$normalizedPath")
                ?.let(::BufferedInputStream)
        }

        private fun mimeType(path: String): String = when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }

        private fun closeQuietly(stream: InputStream?) {
            try {
                stream?.close()
            } catch (_: IOException) {
            }
        }
    }
}
