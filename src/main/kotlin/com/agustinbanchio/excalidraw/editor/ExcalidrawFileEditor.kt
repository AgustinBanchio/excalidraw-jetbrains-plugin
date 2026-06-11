package com.agustinbanchio.excalidraw.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.FileEditorStateLevel.FULL
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent

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
        Disposer.dispose(browser)
    }

    private fun setupJsBridge() {
        readyQuery.addHandler {
            ApplicationManager.getApplication().invokeLater {
                pushDocumentToFrontend()
            }
            null
        }

        sceneChangedQuery.addHandler { payload ->
            applyFrontendScene(payload, saveAfterUpdate = false)
            null
        }

        saveQuery.addHandler { payload ->
            applyFrontendScene(payload, saveAfterUpdate = true)
            null
        }

        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (!frame.isMain) return
                    injectBridge()
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
              save: function(payload) { ${saveQuery.inject("payload")} }
            };
            window.dispatchEvent(new CustomEvent("intellij-excalidraw-bridge-ready"));
        """.trimIndent()
        executeJavaScript(script)
    }

    private fun pushDocumentToFrontend() {
        if (disposed) return
        val text = document.text
        lastPushedText = text
        executeJavaScript(
            "window.excalidrawPlugin?.loadFile(${text.toJavaScriptStringLiteral()});",
        )
    }

    private fun applyFrontendScene(payload: String, saveAfterUpdate: Boolean) {
        if (disposed || payload == document.text) {
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

    private fun executeJavaScript(script: String) {
        try {
            browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        } catch (error: Throwable) {
            thisLogger().warn("Failed to execute Excalidraw JCEF JavaScript", error)
        }
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

    private object NoState : FileEditorState {
        override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean = level == FULL
    }
}
