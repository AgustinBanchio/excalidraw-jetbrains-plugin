package com.agustinbanchio.excalidraw.editor

import com.agustinbanchio.excalidraw.settings.ExcalidrawThemeSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.FileEditorStateLevel.FULL
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.cef.handler.CefDownloadHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.Dimension
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class ExcalidrawFileEditor(
    private val project: Project,
    private val virtualFile: VirtualFile,
) : UserDataHolderBase(), FileEditor {
    private val document: Document = FileDocumentManager.getInstance().getDocument(virtualFile)
        ?: error("Unable to open document for ${virtualFile.path}")
    private val browser = JBCefBrowser()
    private val propertyChangeSupport = PropertyChangeSupport(this)
    private val readyQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val sceneChangedQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val saveQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val themeChangedQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val browseLibraryQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private var disposed = false
    private var lastPushedText: String? = null

    init {
        setupJsBridge()
        setupDocumentListener()
        loadFrontend()
    }

    override fun getComponent(): JComponent = browser.component

    override fun getPreferredFocusedComponent(): JComponent = browser.component

    override fun getName(): String = "Excalidraw"

    override fun getFile(): VirtualFile = virtualFile

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = FileDocumentManager.getInstance().isDocumentUnsaved(document)

    override fun isValid(): Boolean = virtualFile.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.removePropertyChangeListener(listener)
    }

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun dispose() {
        disposed = true
        Disposer.dispose(readyQuery)
        Disposer.dispose(sceneChangedQuery)
        Disposer.dispose(saveQuery)
        Disposer.dispose(themeChangedQuery)
        Disposer.dispose(browseLibraryQuery)
        Disposer.dispose(browser)
    }

    private fun setupJsBridge() {
        readyQuery.addHandler {
            if (!isTrustedFrontend()) return@addHandler null

            ApplicationManager.getApplication().invokeLater {
                pushDocumentToFrontend()
            }
            null
        }

        sceneChangedQuery.addHandler { payload ->
            if (!isTrustedFrontend()) return@addHandler null

            applyFrontendScene(payload, saveAfterUpdate = false)
            null
        }

        saveQuery.addHandler { payload ->
            if (!isTrustedFrontend()) return@addHandler null

            applyFrontendScene(payload, saveAfterUpdate = true)
            null
        }

        themeChangedQuery.addHandler { theme ->
            if (!isTrustedFrontend()) return@addHandler null

            ExcalidrawThemeSettings.getInstance().rememberTheme(theme)
            null
        }

        browseLibraryQuery.addHandler { url ->
            if (!isTrustedFrontend()) return@addHandler null

            openLibraryBrowser(url)
            null
        }

        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (!frame.isMain || !ExcalidrawResourceSchemeHandlerFactory.isTrustedFrontendUrl(frame.url)) return
                    injectBridge()
                }
            },
            browser.cefBrowser,
        )

        browser.jbCefClient.addRequestHandler(
            object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(
                    browser: CefBrowser,
                    frame: CefFrame,
                    request: CefRequest,
                    userGesture: Boolean,
                    isRedirect: Boolean,
                ): Boolean =
                    browser == this@ExcalidrawFileEditor.browser.cefBrowser &&
                        frame.isMain &&
                        !ExcalidrawResourceSchemeHandlerFactory.isTrustedFrontendUrl(request.url)
            },
            browser.cefBrowser,
        )
    }

    private fun setupDocumentListener() {
        document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    propertyChangeSupport.firePropertyChange(FileEditor.getPropModified(), null, isModified)
                    val text = document.text
                    if (text != lastPushedText) {
                        pushDocumentToFrontend()
                    }
                }
            },
            this,
        )
    }

    private fun loadFrontend() {
        if (!ExcalidrawResourceSchemeHandlerFactory.register()) {
            browser.loadHTML(
                "<html><body><h3>Excalidraw frontend was not bundled.</h3><p>Run ./gradlew buildFrontend and restart runIde.</p></body></html>",
            )
            return
        }

        browser.loadURL(ExcalidrawResourceSchemeHandlerFactory.INDEX_URL)
    }

    private fun injectBridge() {
        val script = """
            window.intellijExcalidraw = {
              ready: function(payload) { ${readyQuery.inject("payload")} },
              sceneChanged: function(payload) { ${sceneChangedQuery.inject("payload")} },
              save: function(payload) { ${saveQuery.inject("payload")} },
              themeChanged: function(payload) { ${themeChangedQuery.inject("payload")} },
              browseLibrary: function(payload) { ${browseLibraryQuery.inject("payload")} }
            };
            window.dispatchEvent(new CustomEvent("intellij-excalidraw-bridge-ready"));
        """.trimIndent()
        executeJavaScript(script)
    }

    private fun pushDocumentToFrontend() {
        if (disposed || !isTrustedFrontend()) return
        val text = document.text
        val preferredTheme = ExcalidrawThemeSettings.getInstance().preferredTheme
        lastPushedText = text
        executeJavaScript(
            "window.excalidrawPlugin?.loadFile(${text.toJavaScriptStringLiteral()}, ${preferredTheme.toJavaScriptStringLiteral()});",
        )
    }

    private fun applyFrontendScene(payload: String, saveAfterUpdate: Boolean) {
        if (disposed || !isTrustedFrontend() || payload == document.text) {
            if (saveAfterUpdate) saveDocument()
            return
        }

        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project, Runnable {
                lastPushedText = payload
                document.setText(payload)
            })

            propertyChangeSupport.firePropertyChange(FileEditor.getPropModified(), null, isModified)

            if (saveAfterUpdate) {
                saveDocument()
            }
        }
    }

    private fun saveDocument() {
        ApplicationManager.getApplication().invokeLater {
            FileDocumentManager.getInstance().saveDocument(document)
            propertyChangeSupport.firePropertyChange(FileEditor.getPropModified(), null, isModified)
        }
    }

    private fun openLibraryBrowser(url: String) {
        if (!ExcalidrawResourceSchemeHandlerFactory.isAllowedLibraryBrowserUrl(url)) {
            thisLogger().warn("Blocked untrusted Excalidraw library browser URL: $url")
            return
        }

        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater

            LibraryBrowserDialog(project, url) { returnUrl ->
                importLibraryFromReturnUrl(returnUrl)
            }.show()
        }
    }

    private fun importLibraryFromReturnUrl(url: String) {
        if (!ExcalidrawResourceSchemeHandlerFactory.isTrustedLibraryReturnUrl(url)) {
            thisLogger().warn("Blocked untrusted Excalidraw library return URL: $url")
            return
        }

        val script = """
            (function() {
              const nextUrl = ${url.toJavaScriptStringLiteral()};
              const oldUrl = window.location.href;
              window.history.pushState({}, "", nextUrl);
              const event = typeof HashChangeEvent === "function"
                ? new HashChangeEvent("hashchange", { oldURL: oldUrl, newURL: nextUrl })
                : new Event("hashchange");
              window.dispatchEvent(event);
            })();
        """.trimIndent()
        executeJavaScript(script)
    }

    private fun executeJavaScript(script: String) {
        if (!isTrustedFrontend()) return

        try {
            browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        } catch (error: Throwable) {
            thisLogger().warn("Failed to execute Excalidraw JCEF JavaScript", error)
        }
    }

    private fun isTrustedFrontend(): Boolean =
        ExcalidrawResourceSchemeHandlerFactory.isTrustedFrontendUrl(browser.cefBrowser.url)

    private fun String.toJavaScriptStringLiteral(): String = buildString(length + 16) {
        append('"')
        for (char in this@toJavaScriptStringLiteral) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20 || char.code == 0x2028 || char.code == 0x2029) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }

    private object NoState : FileEditorState {
        override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean = level == FULL
    }

    private class LibraryBrowserDialog(
        private val project: Project,
        initialUrl: String,
        private val onLibrarySelected: (String) -> Unit,
    ) : DialogWrapper(project) {
        private val libraryBrowser = JBCefBrowser()
        private val downloadLibraryQuery = JBCefJSQuery.create(libraryBrowser as JBCefBrowserBase)
        private val statusLabel = JLabel(" ")
        private val contentPanel = JPanel(BorderLayout())
        private val blockedDownloadIds = mutableSetOf<Int>()
        private val importedLibraryReturnUrls = mutableSetOf<String>()

        init {
            title = "Browse Excalidraw Libraries"
            setCancelButtonText("Close")

            downloadLibraryQuery.addHandler { url ->
                requestLibraryDownload(url)
                null
            }

            libraryBrowser.jbCefClient.addRequestHandler(
                object : CefRequestHandlerAdapter() {
                    override fun onBeforeBrowse(
                        browser: CefBrowser,
                        frame: CefFrame,
                        request: CefRequest,
                        userGesture: Boolean,
                        isRedirect: Boolean,
                    ): Boolean {
                        val url = request.url
                        if (!frame.isMain) {
                            return false
                        }

                        if (ExcalidrawResourceSchemeHandlerFactory.isAllowedLibraryBrowserUrl(url)) {
                            return false
                        }

                        if (!ExcalidrawResourceSchemeHandlerFactory.isTrustedLibraryReturnUrl(url)) {
                            invokeInDialog {
                                showBlockedMessage("Blocked navigation outside the Excalidraw library browser.")
                            }
                            return true
                        }

                        if (!importedLibraryReturnUrls.add(url)) {
                            invokeInDialog {
                                showAlreadyAddedMessage()
                            }
                            return true
                        }

                        invokeInDialog {
                            onLibrarySelected(url)
                            showLibraryAddedMessage(url)
                        }
                        return true
                    }
                },
                libraryBrowser.cefBrowser,
            )

            libraryBrowser.jbCefClient.addLoadHandler(
                object : CefLoadHandlerAdapter() {
                    override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                        if (frame.isMain && ExcalidrawResourceSchemeHandlerFactory.isAllowedLibraryBrowserUrl(frame.url)) {
                            injectLibraryBrowserPatch(cefBrowser, frame.url)
                        }
                    }
                },
                libraryBrowser.cefBrowser,
            )

            libraryBrowser.jbCefClient.addDownloadHandler(
                object : CefDownloadHandlerAdapter() {
                    override fun onBeforeDownload(
                        browser: CefBrowser,
                        downloadItem: CefDownloadItem,
                        suggestedName: String,
                        callback: CefBeforeDownloadCallback,
                    ): Boolean {
                        if (!ExcalidrawResourceSchemeHandlerFactory.isAllowedLibraryDownloadUrl(downloadItem.url)) {
                            blockedDownloadIds.add(downloadItem.id)
                            invokeInDialog {
                                showBlockedMessage("Blocked a download outside the Excalidraw library catalog.")
                            }
                            return false
                        }

                        val fileName = sanitizeDownloadFileName(
                            suggestedName.ifBlank { downloadItem.suggestedFileName },
                        )

                        if (!fileName.endsWith(".excalidrawlib")) {
                            blockedDownloadIds.add(downloadItem.id)
                            invokeInDialog {
                                showBlockedMessage("Blocked a non-library download.")
                            }
                            return false
                        }

                        callback.Continue(defaultDownloadPath(fileName), true)
                        return true
                    }

                    override fun onDownloadUpdated(
                        browser: CefBrowser,
                        downloadItem: CefDownloadItem,
                        callback: CefDownloadItemCallback,
                    ) {
                        if (blockedDownloadIds.remove(downloadItem.id)) {
                            callback.cancel()
                            return
                        }

                        if (downloadItem.isComplete) {
                            invokeInDialog {
                                statusLabel.text = "Downloaded ${downloadItem.suggestedFileName}."
                            }
                        }
                    }
                },
                libraryBrowser.cefBrowser,
            )

            init()
            libraryBrowser.loadURL(initialUrl)
        }

        override fun createCenterPanel(): JComponent {
            libraryBrowser.component.preferredSize = Dimension(1100, 760)
            statusLabel.border = javax.swing.BorderFactory.createEmptyBorder(8, 12, 8, 12)
            statusLabel.foreground = JBColor.GRAY
            contentPanel.add(libraryBrowser.component, BorderLayout.CENTER)
            contentPanel.add(statusLabel, BorderLayout.SOUTH)
            return contentPanel
        }

        override fun getPreferredFocusedComponent(): JComponent = libraryBrowser.component

        override fun createActions() = arrayOf(cancelAction)

        override fun dispose() {
            Disposer.dispose(downloadLibraryQuery)
            Disposer.dispose(libraryBrowser)
            super.dispose()
        }

        private fun injectLibraryBrowserPatch(browser: CefBrowser, sourceUrl: String) {
            val script = """
                (function() {
                  if (window.__excalidrawJetBrainsLibraryPatch) {
                    return;
                  }

                  window.__excalidrawJetBrainsLibraryPatch = true;
                  window.__excalidrawJetBrainsPendingLibraries = window.__excalidrawJetBrainsPendingLibraries || new Set();
                  window.__excalidrawJetBrainsAddedLibraries = window.__excalidrawJetBrainsAddedLibraries || new Set();

                  const isPluginReturnUrl = function(url) {
                    return typeof url === "string" &&
                      url.indexOf("https://excalidraw-jetbrains-plugin/") === 0 &&
                      url.indexOf("addLibrary=") !== -1;
                  };

                  const isLibraryDownloadUrl = function(url) {
                    try {
                      const parsed = new URL(url, window.location.href);
                      return parsed.protocol === "https:" &&
                        parsed.host === "libraries.excalidraw.com" &&
                        parsed.pathname.toLowerCase().endsWith(".excalidrawlib");
                    } catch (_) {
                      return false;
                    }
                  };

                  const showMessage = function(message, kind) {
                    let toast = document.getElementById("excalidraw-jetbrains-library-toast");
                    if (!toast) {
                      toast = document.createElement("div");
                      toast.id = "excalidraw-jetbrains-library-toast";
                      toast.setAttribute("role", "status");
                      toast.style.position = "fixed";
                      toast.style.left = "50%";
                      toast.style.bottom = "24px";
                      toast.style.transform = "translateX(-50%)";
                      toast.style.maxWidth = "calc(100% - 48px)";
                      toast.style.padding = "12px 16px";
                      toast.style.borderRadius = "8px";
                      toast.style.boxShadow = "0 8px 28px rgba(0, 0, 0, 0.25)";
                      toast.style.zIndex = "2147483647";
                      toast.style.font = "14px system-ui, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif";
                      toast.style.fontWeight = "600";
                      document.body.appendChild(toast);
                    }

                    const isError = kind === "error";
                    toast.textContent = message;
                    toast.style.background = isError ? "#b00020" : "#2e7d32";
                    toast.style.color = "#fff";
                    toast.style.display = "block";
                    window.clearTimeout(window.__excalidrawJetBrainsToastTimer);
                    window.__excalidrawJetBrainsToastTimer = window.setTimeout(function() {
                      toast.style.display = "none";
                    }, 6000);
                  };

                  const normalizeUrl = function(url) {
                    return new URL(url, window.location.href).href;
                  };

                  const markLibraryAdded = function(url) {
                    const normalizedUrl = normalizeUrl(url);
                    window.__excalidrawJetBrainsPendingLibraries.delete(normalizedUrl);
                    window.__excalidrawJetBrainsAddedLibraries.add(normalizedUrl);
                    document.querySelectorAll("a.install-library").forEach(function(link) {
                      if (normalizeUrl(link.href) === normalizedUrl) {
                        link.textContent = "Added to Excalidraw";
                        link.style.pointerEvents = "none";
                        link.style.opacity = "0.7";
                        link.setAttribute("aria-disabled", "true");
                      }
                    });
                  };

                  window.__excalidrawJetBrainsShowMessage = showMessage;
                  window.__excalidrawJetBrainsMarkLibraryAdded = markLibraryAdded;

                  const originalOpen = window.open;
                  window.open = function(url, target, features) {
                    const nextUrl = String(url || "");
                    if (isPluginReturnUrl(nextUrl)) {
                      const normalizedUrl = normalizeUrl(nextUrl);
                      if (window.__excalidrawJetBrainsAddedLibraries.has(normalizedUrl) ||
                          window.__excalidrawJetBrainsPendingLibraries.has(normalizedUrl)) {
                        showMessage("That library is already being added.", "info");
                        return null;
                      }
                      window.__excalidrawJetBrainsPendingLibraries.add(normalizedUrl);
                      showMessage("Adding library to Excalidraw...", "info");
                      window.location.href = nextUrl;
                      return null;
                    }
                    return originalOpen.call(window, url, target, features);
                  };

                  document.addEventListener("click", function(event) {
                    const link = event.target && event.target.closest
                      ? event.target.closest("a[href]")
                      : null;
                    if (!link) {
                      return;
                    }

                    const href = normalizeUrl(link.href);
                    if (isLibraryDownloadUrl(href)) {
                      event.preventDefault();
                      showMessage("Choose where to save the library file...", "info");
                      ${downloadLibraryQuery.inject("href")}
                      return;
                    }

                    if (isPluginReturnUrl(href)) {
                      event.preventDefault();
                      if (window.__excalidrawJetBrainsAddedLibraries.has(href) ||
                          window.__excalidrawJetBrainsPendingLibraries.has(href)) {
                        showMessage("That library is already being added.", "info");
                        return;
                      }
                      window.__excalidrawJetBrainsPendingLibraries.add(href);
                      link.textContent = "Adding...";
                      link.style.pointerEvents = "none";
                      link.style.opacity = "0.7";
                      showMessage("Adding library to Excalidraw...", "info");
                      window.location.href = href;
                    }
                  }, true);
                })();
            """.trimIndent()
            browser.executeJavaScript(script, sourceUrl, 0)
        }

        private fun showLibraryAddedMessage(returnUrl: String) {
            showSuccessMessage("Library added to Excalidraw.")
            executeLibraryJavaScript(
                """
                    window.__excalidrawJetBrainsMarkLibraryAdded?.(${returnUrl.toJavaScriptStringLiteral()});
                    window.__excalidrawJetBrainsShowMessage?.("Library added to Excalidraw.", "success");
                """.trimIndent(),
            )
        }

        private fun showSuccessMessage(message: String) {
            statusLabel.foreground = JBColor(0x2E7D32, 0x8BC34A)
            statusLabel.text = message
            executeLibraryJavaScript(
                "window.__excalidrawJetBrainsShowMessage?.(${message.toJavaScriptStringLiteral()}, \"success\");",
            )
        }

        private fun showAlreadyAddedMessage() {
            statusLabel.foreground = JBColor.GRAY
            statusLabel.text = "That library has already been added."
            executeLibraryJavaScript(
                """window.__excalidrawJetBrainsShowMessage?.("That library has already been added.", "info");""",
            )
        }

        private fun showBlockedMessage(message: String) {
            statusLabel.foreground = JBColor(0xB00020, 0xFF8A80)
            statusLabel.text = message
            executeLibraryJavaScript(
                "window.__excalidrawJetBrainsShowMessage?.(${message.toJavaScriptStringLiteral()}, \"error\");",
            )
        }

        private fun showInfoMessage(message: String) {
            statusLabel.foreground = JBColor.GRAY
            statusLabel.text = message
            executeLibraryJavaScript(
                "window.__excalidrawJetBrainsShowMessage?.(${message.toJavaScriptStringLiteral()}, \"info\");",
            )
        }

        private fun requestLibraryDownload(url: String) {
            if (!ExcalidrawResourceSchemeHandlerFactory.isAllowedLibraryDownloadUrl(url)) {
                showBlockedMessage("Blocked a non-library download.")
                return
            }

            invokeInDialog downloadDialog@{
                val fileName = sanitizeDownloadFileName(fileNameFromUrl(url))
                val descriptor = FileSaverDescriptor(
                    "Save Excalidraw Library",
                    "Choose where to save the Excalidraw library file.",
                    "excalidrawlib",
                )
                val target = FileChooserFactory.getInstance()
                    .createSaveFileDialog(descriptor, project)
                    .save(defaultDownloadDirectory(), fileName)

                if (target == null) {
                    showInfoMessage("Download canceled.")
                    return@downloadDialog
                }

                showInfoMessage("Downloading $fileName...")
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        downloadLibraryFile(url, target.file.toPath())
                        invokeInDialog {
                            showSuccessMessage("Downloaded ${target.file.name}.")
                        }
                    } catch (error: Throwable) {
                        try {
                            Files.deleteIfExists(target.file.toPath())
                        } catch (_: IOException) {
                        }

                        thisLogger().warn("Failed to download Excalidraw library", error)
                        invokeInDialog {
                            showBlockedMessage("Could not download the library file.")
                        }
                    }
                }
            }
        }

        private fun invokeInDialog(action: () -> Unit) {
            ApplicationManager.getApplication().invokeLater(
                action,
                ModalityState.stateForComponent(contentPanel),
            )
        }

        private fun downloadLibraryFile(url: String, target: Path) {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() !in 200..299 ||
                !ExcalidrawResourceSchemeHandlerFactory.isAllowedLibraryDownloadUrl(response.uri().toString())
            ) {
                response.body().close()
                throw IOException("Unexpected library download response: ${response.statusCode()}")
            }

            response.body().use { input ->
                Files.newOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L

                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break

                        total += read
                        if (total > MAX_LIBRARY_DOWNLOAD_BYTES) {
                            throw IOException("Excalidraw library download is too large")
                        }

                        output.write(buffer, 0, read)
                    }
                }
            }
        }

        private fun executeLibraryJavaScript(script: String) {
            try {
                libraryBrowser.cefBrowser.executeJavaScript(script, libraryBrowser.cefBrowser.url, 0)
            } catch (error: Throwable) {
                thisLogger().warn("Failed to execute Excalidraw library browser JavaScript", error)
            }
        }

        private fun sanitizeDownloadFileName(fileName: String): String {
            val sanitized = fileName
                .ifBlank { "excalidraw-library.excalidrawlib" }
                .replace(Regex("""[\\/:*?"<>|]"""), "_")

            return if (sanitized.endsWith(".excalidrawlib")) sanitized else "$sanitized.excalidrawlib"
        }

        private fun defaultDownloadDirectory(): Path {
            val home = Path.of(System.getProperty("user.home"))
            val downloads = home.resolve("Downloads")
            return if (downloads.toFile().isDirectory) downloads else home
        }

        private fun defaultDownloadPath(fileName: String): String {
            return defaultDownloadDirectory().resolve(fileName).toString()
        }

        private fun fileNameFromUrl(url: String): String {
            val path = URI(url).path.substringAfterLast('/')
            return URLDecoder.decode(path, StandardCharsets.UTF_8)
        }

        private fun String.toJavaScriptStringLiteral(): String = buildString(length + 16) {
            append('"')
            for (char in this@toJavaScriptStringLiteral) {
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char.code < 0x20 || char.code == 0x2028 || char.code == 0x2029) {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
            append('"')
        }

        private companion object {
            private const val MAX_LIBRARY_DOWNLOAD_BYTES = 25L * 1024L * 1024L
        }
    }
}
