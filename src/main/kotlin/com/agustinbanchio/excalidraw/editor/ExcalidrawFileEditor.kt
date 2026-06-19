package com.agustinbanchio.excalidraw.editor

import com.agustinbanchio.excalidraw.settings.ExcalidrawThemeSettings
import com.intellij.ide.BrowserUtil
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
import com.intellij.util.Alarm
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.cef.handler.CefDownloadHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
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
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
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
    private val saveCurrentDocumentQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val themeChangedQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val browseLibraryQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val openExternalLinkQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val sceneUpdateAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var disposed = false
    private var documentRevision = 1L
    private var applyingFrontendScene = false
    @Volatile
    private var pendingSceneUpdate: PendingSceneUpdate? = null

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
        sceneUpdateAlarm.cancelAllRequests()
        flushPendingFrontendScene(saveAfterUpdate = false)
        disposed = true
        Disposer.dispose(readyQuery)
        Disposer.dispose(sceneChangedQuery)
        Disposer.dispose(saveQuery)
        Disposer.dispose(saveCurrentDocumentQuery)
        Disposer.dispose(themeChangedQuery)
        Disposer.dispose(browseLibraryQuery)
        Disposer.dispose(openExternalLinkQuery)
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

            runOnEdt { queueFrontendScene(payload, saveImmediately = false) }
            null
        }

        saveQuery.addHandler { payload ->
            if (!isTrustedFrontend()) return@addHandler null

            runOnEdt { queueFrontendScene(payload, saveImmediately = true) }
            null
        }

        saveCurrentDocumentQuery.addHandler {
            if (!isTrustedFrontend()) return@addHandler null

            runOnEdt {
                cancelPendingFrontendScene()
                saveDocument()
            }
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

        openExternalLinkQuery.addHandler { url ->
            if (!isTrustedFrontend()) return@addHandler null

            val uri = allowedExternalUri(url) ?: run {
                thisLogger().warn("Blocked unsupported Excalidraw link: $url")
                return@addHandler null
            }
            ApplicationManager.getApplication().invokeLater {
                BrowserUtil.browse(uri)
            }
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
                        !ExcalidrawResourceSchemeHandlerFactory.isTrustedFrontendUrl(request.url)
            },
            browser.cefBrowser,
        )

        browser.jbCefClient.addLifeSpanHandler(
            object : CefLifeSpanHandlerAdapter() {
                override fun onBeforePopup(
                    browser: CefBrowser,
                    frame: CefFrame,
                    targetUrl: String,
                    targetFrameName: String,
                ): Boolean = true
            },
            browser.cefBrowser,
        )

        browser.jbCefClient.addDownloadHandler(
            object : CefDownloadHandlerAdapter() {
                override fun onBeforeDownload(
                    browser: CefBrowser,
                    downloadItem: CefDownloadItem,
                    suggestedName: String,
                    callback: CefBeforeDownloadCallback,
                ): Boolean = false
            },
            browser.cefBrowser,
        )
    }

    private fun setupDocumentListener() {
        document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    propertyChangeSupport.firePropertyChange(FileEditor.getPropModified(), null, isModified)
                    if (applyingFrontendScene) return

                    documentRevision++
                    cancelPendingFrontendScene()
                    pushDocumentToFrontend()
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
              saveCurrentDocument: function() { ${saveCurrentDocumentQuery.inject("''")} },
              themeChanged: function(payload) { ${themeChangedQuery.inject("payload")} },
              browseLibrary: function(payload) { ${browseLibraryQuery.inject("payload")} },
              openExternalLink: function(payload) { ${openExternalLinkQuery.inject("payload")} }
            };
            window.dispatchEvent(new CustomEvent("intellij-excalidraw-bridge-ready"));
        """.trimIndent()
        executeJavaScript(script)
    }

    private fun pushDocumentToFrontend() {
        if (disposed || !isTrustedFrontend()) return
        val text = document.text
        val preferredTheme = ExcalidrawThemeSettings.getInstance().preferredTheme
        executeJavaScript(
            "window.excalidrawPlugin?.loadFile(${text.toJavaScriptStringLiteral()}, ${preferredTheme.toJavaScriptStringLiteral()}, $documentRevision);",
        )
    }

    private fun queueFrontendScene(encodedUpdate: String, saveImmediately: Boolean) {
        if (disposed || !isTrustedFrontend()) return

        val update = PendingSceneUpdate.decode(encodedUpdate) ?: return
        if (update.revision != documentRevision) {
            if (saveImmediately) saveDocument()
            return
        }

        pendingSceneUpdate = update
        sceneUpdateAlarm.cancelAllRequests()
        if (saveImmediately) {
            flushPendingFrontendScene(saveAfterUpdate = true)
            return
        }

        sceneUpdateAlarm.addRequest(
            { flushPendingFrontendScene(saveAfterUpdate = false) },
            SCENE_UPDATE_DELAY_MS,
        )
    }

    private fun flushPendingFrontendScene(saveAfterUpdate: Boolean) {
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            application.invokeAndWait { flushPendingFrontendScene(saveAfterUpdate) }
            return
        }

        val update = pendingSceneUpdate ?: return
        pendingSceneUpdate = null
        if (update.revision != documentRevision) return

        applyFrontendScene(update.scene, saveAfterUpdate)
    }

    private fun cancelPendingFrontendScene() {
        sceneUpdateAlarm.cancelAllRequests()
        pendingSceneUpdate = null
    }

    private fun applyFrontendScene(payload: String, saveAfterUpdate: Boolean) {
        if (payload == document.text) {
            if (saveAfterUpdate) saveDocument()
            return
        }

        WriteCommandAction.runWriteCommandAction(project, Runnable {
            applyingFrontendScene = true
            try {
                document.setText(payload)
            } finally {
                applyingFrontendScene = false
            }
        })

        propertyChangeSupport.firePropertyChange(FileEditor.getPropModified(), null, isModified)

        if (saveAfterUpdate) {
            saveDocument()
        }
    }

    private fun runOnEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeLater(action)
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

    private fun allowedExternalUri(url: String): URI? = try {
        val uri = URI(url)
        uri.takeIf {
            (it.scheme.equals("https", ignoreCase = true) || it.scheme.equals("http", ignoreCase = true)) &&
                !it.host.isNullOrBlank() &&
                it.userInfo == null
        }
    } catch (_: IllegalArgumentException) {
        null
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

    private data class PendingSceneUpdate(
        val revision: Long,
        val scene: String,
    ) {
        companion object {
            fun decode(value: String): PendingSceneUpdate? {
                val separator = value.indexOf('\n')
                if (separator <= 0) return null

                val revision = value.substring(0, separator).toLongOrNull() ?: return null
                return PendingSceneUpdate(revision, value.substring(separator + 1))
            }
        }
    }

    private object NoState : FileEditorState {
        override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean = level == FULL
    }

    private companion object {
        private const val SCENE_UPDATE_DELAY_MS = 250
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

                        if (!fileName.endsWith(".excalidrawlib", ignoreCase = true)) {
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
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
            val absoluteTarget = target.toAbsolutePath()
            val temporaryFile = Files.createTempFile(
                absoluteTarget.parent,
                ".${absoluteTarget.fileName}.",
                ".download",
            )

            try {
                val response = sendLibraryRequest(client, URI(url))
                response.body().use { input ->
                    Files.newOutputStream(
                        temporaryFile,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                    ).use { output ->
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

                try {
                    Files.move(
                        temporaryFile,
                        absoluteTarget,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporaryFile, absoluteTarget, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporaryFile)
            }
        }

        private fun sendLibraryRequest(
            client: HttpClient,
            initialUri: URI,
        ): HttpResponse<java.io.InputStream> {
            var uri = initialUri

            repeat(MAX_LIBRARY_REDIRECTS + 1) { redirectCount ->
                if (!ExcalidrawResourceSchemeHandlerFactory.isAllowedLibraryDownloadUrl(uri.toString())) {
                    throw IOException("Blocked untrusted library download URL")
                }

                val request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

                if (response.statusCode() in REDIRECT_STATUS_CODES) {
                    response.body().close()
                    if (redirectCount == MAX_LIBRARY_REDIRECTS) {
                        throw IOException("Too many library download redirects")
                    }

                    val location = response.headers().firstValue("Location").orElseThrow {
                        IOException("Library download redirect has no destination")
                    }
                    uri = uri.resolve(location)
                    return@repeat
                }

                if (response.statusCode() !in 200..299) {
                    response.body().close()
                    throw IOException("Unexpected library download response: ${response.statusCode()}")
                }

                return response
            }

            throw IOException("Too many library download redirects")
        }

        private fun executeLibraryJavaScript(script: String) {
            try {
                libraryBrowser.cefBrowser.executeJavaScript(script, libraryBrowser.cefBrowser.url, 0)
            } catch (error: Throwable) {
                thisLogger().warn("Failed to execute Excalidraw library browser JavaScript", error)
            }
        }

        private fun sanitizeDownloadFileName(fileName: String): String {
            var sanitized = fileName
                .ifBlank { "excalidraw-library.excalidrawlib" }
                .replace(Regex("""[\\/:*?"<>|]"""), "_")
                .trimEnd(' ', '.')

            if (!sanitized.endsWith(".excalidrawlib", ignoreCase = true)) {
                sanitized += ".excalidrawlib"
            }

            val baseName = sanitized.substringBefore('.').uppercase()
            return if (baseName in WINDOWS_RESERVED_FILE_NAMES) "_$sanitized" else sanitized
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
            private const val MAX_LIBRARY_REDIRECTS = 5
            private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
            private val WINDOWS_RESERVED_FILE_NAMES = buildSet {
                addAll(listOf("CON", "PRN", "AUX", "NUL"))
                (1..9).forEach { number ->
                    add("COM$number")
                    add("LPT$number")
                }
            }
        }
    }
}
