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
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

internal object CefResourceRequestContract {
    fun processRequest(continueRequest: () -> Unit): Boolean {
        continueRequest()
        return true
    }

    fun open(setHandleRequest: (Boolean) -> Unit): Boolean {
        setHandleRequest(true)
        return true
    }
}

class ExcalidrawResourceSchemeHandlerFactory : CefSchemeHandlerFactory {
    override fun create(browser: CefBrowser, frame: CefFrame, schemeName: String, request: CefRequest): CefResourceHandler {
        val uri = URI(request.url)
        val stream = if (isTrustedFrontendUrl(request.url)) openResource(uri.path) else null

        val handler = ResourceHandlerInvocationHandler(uri.path, stream)
        return Proxy.newProxyInstance(
            CefResourceHandler::class.java.classLoader,
            arrayOf(CefResourceHandler::class.java),
            handler,
        ) as CefResourceHandler
    }

    private class ResourceHandlerInvocationHandler(
        private val path: String,
        private val stream: InputStream?,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args.orEmpty()
            return when (method.name) {
                "processRequest" -> processRequest(arguments)
                "open" -> open(arguments)
                "getResponseHeaders" -> getResponseHeaders(arguments)
                "readResponse", "read" -> read(arguments)
                "skip" -> skip(arguments)
                "cancel" -> closeQuietly(stream)
                "toString" -> "ExcalidrawResourceHandler($path)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments.firstOrNull()
                else -> defaultReturnValue(method)
            }
        }

        private fun processRequest(args: Array<out Any?>): Boolean =
            CefResourceRequestContract.processRequest { (args[1] as CefCallback).Continue() }

        private fun open(args: Array<out Any?>): Boolean =
            CefResourceRequestContract.open { setRef(args[1], it) }

        private fun getResponseHeaders(args: Array<out Any?>) {
            val response = args[0] as CefResponse
            response.mimeType = mimeType(path)
            response.status = if (stream == null) 404 else 200
            setRef(args.getOrNull(1), -1)
        }

        private fun read(args: Array<out Any?>): Boolean {
            val dataOut = args[0] as ByteArray
            val bytesToRead = args[1] as Int
            val bytesRead = args[2] as IntRef

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

        private fun skip(args: Array<out Any?>): Boolean {
            if (stream == null) {
                setRef(args[1], -2L)
                return false
            }

            return try {
                setRef(args[1], stream.skip(args[0] as Long))
                true
            } catch (_: IOException) {
                setRef(args[1], -2L)
                false
            }
        }

        private fun setRef(reference: Any?, value: Any) {
            reference?.javaClass?.methods
                ?.firstOrNull { it.name == "set" && it.parameterCount == 1 }
                ?.invoke(reference, value)
        }

        private fun defaultReturnValue(method: Method): Any? = when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0F
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
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
                uri.scheme.equals("https", ignoreCase = true) && uri.host.equals(DOMAIN, ignoreCase = true) && uri.port == -1
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        fun isTrustedLibraryReturnUrl(url: String?): Boolean {
            if (url == null) return false

            return try {
                val uri = URI(url)
                isTrustedFrontendUrl(url) &&
                    (uri.path.isNullOrBlank() || uri.path == "/" || uri.path == "/index.html") &&
                    ((uri.fragment ?: "").contains("addLibrary=") || (uri.query ?: "").contains("addLibrary="))
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        fun isAllowedLibraryBrowserUrl(url: String?): Boolean {
            if (url == null) return false

            return try {
                val uri = URI(url)
                uri.scheme.equals("https", ignoreCase = true) &&
                    uri.host.equals("libraries.excalidraw.com", ignoreCase = true) &&
                    uri.port == -1
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        fun isAllowedLibraryDownloadUrl(url: String?): Boolean {
            if (url == null) return false

            return try {
                val uri = URI(url)
                isAllowedLibraryBrowserUrl(url) && uri.path.endsWith(".excalidrawlib", ignoreCase = true)
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
