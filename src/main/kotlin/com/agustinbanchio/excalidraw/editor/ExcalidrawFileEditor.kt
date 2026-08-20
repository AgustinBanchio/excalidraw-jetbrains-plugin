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
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.Magnificator
import com.intellij.ui.components.ZoomableViewport
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefDownloadHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.DisplayMode
import java.awt.Point
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
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
import javax.swing.Timer
import kotlin.math.ceil

class ExcalidrawFileEditor(
    private val project: Project,
    private val virtualFile: VirtualFile,
) : UserDataHolderBase(), FileEditor {
    private val pluginSettings = ExcalidrawThemeSettings.getInstance()
    private val adaptiveOsrFrameRateEnabled = pluginSettings.adaptiveOsrFrameRateEnabled
    private val nativeTrackpadZoomEnabled = pluginSettings.nativeTrackpadZoomEnabled
    private val coalescedTrackpadScrollingEnabled = pluginSettings.coalescedTrackpadScrollingEnabled
    private val document: Document = FileDocumentManager.getInstance().getDocument(virtualFile)
        ?: error("Unable to open document for ${virtualFile.path}")
    private val browser = JBCefBrowser.createBuilder()
        .setOffScreenRendering(false)
        .build()
    private val nativeMagnificationViewport = if (
        browser.isOffScreenRendering && SystemInfo.isMac && nativeTrackpadZoomEnabled
    ) {
        ExcalidrawZoomableViewport(
            browser.component,
            onMagnificationStarted = ::beginNativeMagnification,
            onMagnified = ::applyNativeMagnification,
            onMagnificationFinished = ::endNativeMagnification,
        )
    } else {
        null
    }
    private val editorComponent: JComponent = nativeMagnificationViewport ?: browser.component
    private val propertyChangeSupport = PropertyChangeSupport(this)
    private val readyQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val beginSceneTransferQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val appendSceneTransferChunkQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val completeSceneTransferQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val saveCurrentDocumentQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val themeChangedQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val browseLibraryQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val openExternalLinkQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val scrollAppliedQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val sceneUpdateAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var disposed = false
    private var documentRevision = 1L
    private var applyingFrontendScene = false
    @Volatile
    private var pendingSceneUpdate: PendingSceneUpdate? = null
    private var incomingSceneTransfer: IncomingSceneTransfer? = null
    private var trackpadWheelListener: MouseWheelListener? = null
    private var trackpadScrollTimer: Timer? = null
    private val trackpadScrollAccumulator = TrackpadScrollAccumulator()
    private val highResolutionWheelDetector = HighResolutionWheelStreamDetector(TRACKPAD_STREAM_CONTINUATION_MS)
    private var pendingTrackpadWheelEvent: MouseWheelEvent? = null
    private var lastTrackpadScrollEventAt: Long? = null
    private var touchScrollDiagnosticCount = 0L
    private var standardWheelDiagnosticCount = 0L
    private var adaptedStandardWheelDiagnosticCount = 0L
    private var scrollDispatchDiagnosticCount = 0L
    private var scrollAcknowledgementDiagnosticCount = 0L
    private var lastWheelDiagnosticsAt = System.nanoTime()
    private var osrGraphicsConfigurationListener: PropertyChangeListener? = null
    private var osrHierarchyListener: HierarchyListener? = null
    private var activeDisplayRefreshRate: Int? = null

    init {
        setupOffScreenRendering()
        setupTrackpadGestures()
        setupJsBridge()
        setupDocumentListener()
        loadFrontend()
    }

    override fun getComponent(): JComponent = editorComponent

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
        flushPendingFrontendScene(saveAfterUpdate = true)
        disposed = true
        Disposer.dispose(readyQuery)
        Disposer.dispose(beginSceneTransferQuery)
        Disposer.dispose(appendSceneTransferChunkQuery)
        Disposer.dispose(completeSceneTransferQuery)
        Disposer.dispose(saveCurrentDocumentQuery)
        Disposer.dispose(themeChangedQuery)
        Disposer.dispose(browseLibraryQuery)
        Disposer.dispose(openExternalLinkQuery)
        Disposer.dispose(scrollAppliedQuery)
        trackpadWheelListener?.let { browser.browserComponent?.removeMouseWheelListener(it) }
        cancelPendingTrackpadScroll()
        val browserComponent = browser.browserComponent
        osrGraphicsConfigurationListener?.let { browserComponent?.removePropertyChangeListener("graphicsConfiguration", it) }
        osrHierarchyListener?.let { browserComponent?.removeHierarchyListener(it) }
        Disposer.dispose(browser)
    }

    private fun setupOffScreenRendering() {
        if (!browser.isOffScreenRendering || (!adaptiveOsrFrameRateEnabled && !coalescedTrackpadScrollingEnabled)) return

        val browserComponent = browser.browserComponent ?: return
        osrGraphicsConfigurationListener = PropertyChangeListener {
            updateOsrDisplayRefreshRate(browserComponent)
        }.also {
            browserComponent.addPropertyChangeListener("graphicsConfiguration", it)
        }
        osrHierarchyListener = HierarchyListener { event ->
            val displayChanged = event.changeFlags and
                (HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() or HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L
            if (displayChanged) {
                updateOsrDisplayRefreshRate(browserComponent)
            }
        }.also(browserComponent::addHierarchyListener)

        ApplicationManager.getApplication().invokeLater {
            if (!disposed) updateOsrDisplayRefreshRate(browserComponent)
        }
    }

    private fun updateOsrDisplayRefreshRate(browserComponent: Component) {
        val refreshRate = browserComponent.graphicsConfiguration
            ?.device
            ?.displayMode
            ?.refreshRate
            ?.takeIf { it != DisplayMode.REFRESH_RATE_UNKNOWN && it > 0 }
            ?: return
        if (refreshRate == activeDisplayRefreshRate) return

        activeDisplayRefreshRate = refreshRate
        updateTrackpadScrollTimerDelay()
        if (!adaptiveOsrFrameRateEnabled) return

        try {
            browser.cefBrowser.setWindowlessFrameRate(refreshRate)
            thisLogger().debug("Set Excalidraw JCEF OSR frame rate to the active display rate: $refreshRate Hz")
        } catch (error: Throwable) {
            thisLogger().warn("Failed to update Excalidraw JCEF OSR frame rate", error)
        }
    }

    private fun setupTrackpadGestures() {
        if (!browser.isOffScreenRendering || (!nativeTrackpadZoomEnabled && !coalescedTrackpadScrollingEnabled)) return

        val browserComponent = browser.browserComponent ?: return
        trackpadWheelListener = MouseWheelListener { event ->
            recordWheelEventForDiagnostics(event)

            if (
                lastTrackpadScrollEventAt?.let { event.`when` - it > TRACKPAD_STREAM_RESET_GAP_MS } == true
            ) {
                cancelPendingTrackpadScroll()
            }

            // JBR uses the otherwise undefined scroll types 2..4 for some touch-scroll
            // streams. macOS can instead report trackpads as fractional standard wheel
            // events, so identify those without intercepting ordinary integer mouse wheels.
            val isTouchScroll = event.scrollType in TOUCH_SCROLL_BEGIN..TOUCH_SCROLL_END
            val isHighResolutionStandardScroll = event.scrollType <= MouseWheelEvent.WHEEL_BLOCK_SCROLL &&
                SystemInfo.isMac &&
                highResolutionWheelDetector.isHighResolutionStream(
                    event.`when`,
                    event.preciseWheelRotation,
                    event.wheelRotation,
                )
            if (!isTouchScroll && !isHighResolutionStandardScroll) return@MouseWheelListener

            if (nativeMagnificationViewport?.isMagnificationActive == true) {
                event.consume()
                cancelPendingTrackpadScroll()
                return@MouseWheelListener
            }

            if (!coalescedTrackpadScrollingEnabled) return@MouseWheelListener

            event.consume()
            if (event.scrollType == TOUCH_SCROLL_BEGIN) {
                cancelPendingTrackpadScroll()
            }
            lastTrackpadScrollEventAt = event.`when`
            if (isHighResolutionStandardScroll) adaptedStandardWheelDiagnosticCount++

            val scrollAmount = if (isTouchScroll) event.scrollAmount.coerceAtLeast(1) else 1
            var delta = event.preciseWheelRotation * scrollAmount * OSR_TRACKPAD_WHEEL_FACTOR
            // Standard macOS wheel events already reflect the system's natural-scrolling
            // preference. Only JBR's synthetic touch-scroll events use the opposite sign.
            if (isTouchScroll && (SystemInfo.isLinux || SystemInfo.isMac)) {
                delta *= -1
            }
            if (!delta.isFinite() || delta == 0.0) return@MouseWheelListener

            pendingTrackpadWheelEvent = event
            if (event.isShiftDown) {
                trackpadScrollAccumulator.add(delta, 0.0)
            } else {
                trackpadScrollAccumulator.add(0.0, delta)
            }
            scheduleTrackpadScrollFlush()
        }
        browserComponent.addMouseWheelListener(trackpadWheelListener)
    }

    private fun scheduleTrackpadScrollFlush() {
        if (trackpadScrollAccumulator.inFlightSequence != null) return

        if (activeDisplayRefreshRate == null) {
            browser.browserComponent?.let(::updateOsrDisplayRefreshRate)
        }

        val timer = trackpadScrollTimer ?: Timer(trackpadScrollFrameDelayMs()) {
            flushPendingTrackpadScroll()
        }.also {
            it.isRepeats = false
            it.isCoalesce = true
            trackpadScrollTimer = it
        }
        if (!timer.isRunning) {
            val delay = trackpadScrollFrameDelayMs()
            timer.delay = delay
            timer.initialDelay = delay
            timer.start()
        }
    }

    private fun flushPendingTrackpadScroll() {
        val dispatch = trackpadScrollAccumulator.dispatchIfIdle() ?: return
        scrollDispatchDiagnosticCount++

        sendTrackpadScrollToFrontend(dispatch)
    }

    private fun acknowledgeTrackpadScroll(sequence: Long) {
        if (!trackpadScrollAccumulator.acknowledge(sequence)) return

        scrollAcknowledgementDiagnosticCount++
        if (trackpadScrollAccumulator.hasPending) {
            scheduleTrackpadScrollFlush()
        }
    }

    private fun recordWheelEventForDiagnostics(event: MouseWheelEvent) {
        if (!isDebugLoggingEnabled()) return

        if (event.scrollType in TOUCH_SCROLL_BEGIN..TOUCH_SCROLL_END) {
            touchScrollDiagnosticCount++
        } else {
            standardWheelDiagnosticCount++
        }

        val now = System.nanoTime()
        if (now - lastWheelDiagnosticsAt < WHEEL_DIAGNOSTICS_INTERVAL_NS) return

        thisLogger().info(
            "Excalidraw OSR wheel diagnostics: touch=$touchScrollDiagnosticCount, " +
                "standard=$standardWheelDiagnosticCount, adaptedStandard=$adaptedStandardWheelDiagnosticCount, " +
                "dispatched=$scrollDispatchDiagnosticCount, " +
                "acknowledged=$scrollAcknowledgementDiagnosticCount, " +
                "inFlight=${trackpadScrollAccumulator.inFlightSequence != null}",
        )
        touchScrollDiagnosticCount = 0
        standardWheelDiagnosticCount = 0
        adaptedStandardWheelDiagnosticCount = 0
        scrollDispatchDiagnosticCount = 0
        scrollAcknowledgementDiagnosticCount = 0
        lastWheelDiagnosticsAt = now
    }

    private fun cancelPendingTrackpadScroll() {
        trackpadScrollTimer?.stop()
        trackpadScrollAccumulator.reset()
        highResolutionWheelDetector.reset()
        pendingTrackpadWheelEvent = null
        lastTrackpadScrollEventAt = null
    }

    private fun updateTrackpadScrollTimerDelay() {
        trackpadScrollTimer?.takeUnless(Timer::isRunning)?.let { timer ->
            val delay = trackpadScrollFrameDelayMs()
            timer.delay = delay
            timer.initialDelay = delay
        }
    }

    private fun trackpadScrollFrameDelayMs(): Int = activeDisplayRefreshRate
        ?.let { ceil(1_000.0 / it).toInt().coerceAtLeast(1) }
        ?: 0

    private fun sendTrackpadScrollToFrontend(dispatch: TrackpadScrollDispatch) {
        val sourceEvent = pendingTrackpadWheelEvent ?: run {
            trackpadScrollAccumulator.reset()
            return
        }
        val hasHorizontalDelta = dispatch.deltaX.isFinite() && dispatch.deltaX != 0.0
        val hasVerticalDelta = dispatch.deltaY.isFinite() && dispatch.deltaY != 0.0
        if (!hasHorizontalDelta && !hasVerticalDelta) {
            acknowledgeTrackpadScroll(dispatch.sequence)
            return
        }

        executeJavaScript(
            "window.excalidrawPlugin?.scroll(" +
                "${dispatch.deltaX}, ${dispatch.deltaY}, ${sourceEvent.x}, ${sourceEvent.y}, " +
                "${sourceEvent.isControlDown || sourceEvent.isMetaDown}, ${dispatch.sequence});",
        )
    }

    private fun beginNativeMagnification(at: Point) {
        executeJavaScript("window.excalidrawPlugin?.beginMagnification(${at.x}, ${at.y});")
    }

    private fun applyNativeMagnification(scale: Double) {
        if (!scale.isFinite() || scale <= 0.0) return
        executeJavaScript("window.excalidrawPlugin?.magnify(${scale});")
    }

    private fun endNativeMagnification() {
        executeJavaScript("window.excalidrawPlugin?.endMagnification();")
    }

    private fun setupJsBridge() {
        setupDebugConsoleLogging()

        readyQuery.addHandler {
            if (!isTrustedFrontend()) return@addHandler null

            ApplicationManager.getApplication().invokeLater {
                cancelPendingTrackpadScroll()
                pushDocumentToFrontend()
            }
            null
        }

        scrollAppliedQuery.addHandler { payload ->
            if (!isTrustedFrontend()) return@addHandler null

            val sequence = payload.toLongOrNull() ?: return@addHandler null
            runOnEdt { acknowledgeTrackpadScroll(sequence) }
            null
        }

        beginSceneTransferQuery.addHandler { payload ->
            if (!isTrustedFrontend()) return@addHandler null

            runOnEdt { beginSceneTransfer(payload) }
            null
        }

        appendSceneTransferChunkQuery.addHandler { payload ->
            if (!isTrustedFrontend()) return@addHandler null

            runOnEdt { appendSceneTransferChunk(payload) }
            null
        }

        completeSceneTransferQuery.addHandler { payload ->
            if (!isTrustedFrontend()) return@addHandler null

            runOnEdt { completeSceneTransfer(payload) }
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

    private fun setupDebugConsoleLogging() {
        if (!isDebugLoggingEnabled()) return

        browser.jbCefClient.addDisplayHandler(
            object : CefDisplayHandlerAdapter() {
                override fun onConsoleMessage(
                    browser: CefBrowser,
                    level: org.cef.CefSettings.LogSeverity,
                    message: String,
                    source: String,
                    line: Int,
                ): Boolean {
                    thisLogger().info("Excalidraw JCEF console [$level] $source:$line: $message")
                    return false
                }
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
              beginSceneTransfer: function(payload) { ${beginSceneTransferQuery.inject("payload")} },
              appendSceneTransferChunk: function(payload) { ${appendSceneTransferChunkQuery.inject("payload")} },
              completeSceneTransfer: function(payload) { ${completeSceneTransferQuery.inject("payload")} },
              saveCurrentDocument: function() { ${saveCurrentDocumentQuery.inject("''")} },
              themeChanged: function(payload) { ${themeChangedQuery.inject("payload")} },
              browseLibrary: function(payload) { ${browseLibraryQuery.inject("payload")} },
              openExternalLink: function(payload) { ${openExternalLinkQuery.inject("payload")} },
              scrollApplied: function(payload) { ${scrollAppliedQuery.inject("payload")} }
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
        val update = PendingSceneUpdate.decode(encodedUpdate) ?: return
        queueFrontendScene(update, saveImmediately)
    }

    private fun queueFrontendScene(update: PendingSceneUpdate, saveImmediately: Boolean) {
        if (disposed || !isTrustedFrontend()) return
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

    private fun beginSceneTransfer(payload: String) {
        val transfer = IncomingSceneTransfer.decode(payload) ?: return
        incomingSceneTransfer = transfer
    }

    private fun appendSceneTransferChunk(payload: String) {
        val separator = payload.indexOf('\n')
        if (separator <= 0) {
            incomingSceneTransfer = null
            return
        }

        val transferId = payload.substring(0, separator)
        val chunk = payload.substring(separator + 1)
        val transfer = incomingSceneTransfer

        if (transfer == null || transfer.id != transferId) {
            incomingSceneTransfer = null
            return
        }

        transfer.appendChunk(chunk)
    }

    private fun completeSceneTransfer(payload: String) {
        val transfer = incomingSceneTransfer
        incomingSceneTransfer = null

        if (transfer == null || transfer.id != payload || !transfer.isComplete()) {
            return
        }

        queueFrontendScene(
            PendingSceneUpdate(transfer.revision, transfer.scene()),
            transfer.saveImmediately,
        )
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
        val save = {
            FileDocumentManager.getInstance().saveDocument(document)
            propertyChangeSupport.firePropertyChange(FileEditor.getPropModified(), null, isModified)
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) save() else application.invokeAndWait(save)
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

    private data class IncomingSceneTransfer(
        val id: String,
        val revision: Long,
        val saveImmediately: Boolean,
        val expectedChunkCount: Int,
        private val builder: StringBuilder = StringBuilder(),
        private var receivedChunkCount: Int = 0,
    ) {
        fun appendChunk(chunk: String) {
            builder.append(chunk)
            receivedChunkCount += 1
        }

        fun isComplete(): Boolean = receivedChunkCount == expectedChunkCount

        fun scene(): String = builder.toString()

        companion object {
            fun decode(value: String): IncomingSceneTransfer? {
                val lines = value.split('\n')
                if (lines.size != 4) return null

                val revision = lines[1].toLongOrNull() ?: return null
                val saveImmediately = when (lines[2]) {
                    "1" -> true
                    "0" -> false
                    else -> return null
                }
                val chunkCount = lines[3].toIntOrNull()?.takeIf { it > 0 } ?: return null

                return IncomingSceneTransfer(lines[0], revision, saveImmediately, chunkCount)
            }
        }
    }

    private object NoState : FileEditorState {
        override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean = level == FULL
    }

    private class ExcalidrawZoomableViewport(
        content: JComponent,
        private val onMagnificationStarted: (Point) -> Unit,
        private val onMagnified: (Double) -> Unit,
        private val onMagnificationFinished: () -> Unit,
    ) : JPanel(BorderLayout()), ZoomableViewport {
        private val magnifier = Magnificator { _, at -> Point(at) }

        var isMagnificationActive: Boolean = false
            private set

        init {
            add(content, BorderLayout.CENTER)
        }

        override fun getMagnificator(): Magnificator = magnifier

        override fun magnificationStarted(at: Point) {
            isMagnificationActive = true
            onMagnificationStarted(Point(at))
        }

        override fun magnify(magnification: Double) {
            if (!isMagnificationActive) return

            val scale = if (magnification < 0.0) {
                1.0 / (1.0 - magnification)
            } else {
                1.0 + magnification
            }
            if (scale.isFinite() && scale > 0.0) {
                onMagnified(scale)
            }
        }

        override fun magnificationFinished(magnification: Double) {
            if (!isMagnificationActive) return

            magnify(magnification)
            isMagnificationActive = false
            onMagnificationFinished()
        }
    }

    private companion object {
        private const val SCENE_UPDATE_DELAY_MS = 250
        private const val TOUCH_SCROLL_BEGIN = 2
        private const val TOUCH_SCROLL_END = 4
        private const val OSR_TRACKPAD_WHEEL_FACTOR = 40.0
        private const val TRACKPAD_STREAM_CONTINUATION_MS = 250L
        private const val TRACKPAD_STREAM_RESET_GAP_MS = 500L
        private const val WHEEL_DIAGNOSTICS_INTERVAL_NS = 1_000_000_000L

        private fun isDebugLoggingEnabled(): Boolean =
            System.getProperty("excalidraw.plugin.debug")?.toBooleanStrictOrNull() == true ||
                System.getenv("EXCALIDRAW_PLUGIN_DEBUG")?.toBooleanStrictOrNull() == true
    }

    private class LibraryBrowserDialog(
        private val project: Project,
        initialUrl: String,
        private val onLibrarySelected: (String) -> Unit,
    ) : DialogWrapper(project) {
        private val libraryBrowser = JBCefBrowser.createBuilder()
            .setOffScreenRendering(false)
            .build()
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
