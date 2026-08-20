package com.agustinbanchio.excalidraw.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.ui.JBColor

@Service(Service.Level.APP)
@State(name = "ExcalidrawThemeSettings", storages = [Storage("excalidraw.xml")])
class ExcalidrawThemeSettings : PersistentStateComponent<ExcalidrawThemeSettings.SettingsState> {
    private var settingsState = SettingsState()

    val preferredTheme: String
        get() = settingsState.preferredTheme ?: if (JBColor.isBright()) LIGHT else DARK

    var adaptiveOsrFrameRateEnabled: Boolean
        get() = settingsState.adaptiveOsrFrameRateEnabled
        set(value) {
            settingsState.adaptiveOsrFrameRateEnabled = value
        }

    var nativeTrackpadZoomEnabled: Boolean
        get() = settingsState.nativeTrackpadZoomEnabled
        set(value) {
            settingsState.nativeTrackpadZoomEnabled = value
        }

    var coalescedTrackpadScrollingEnabled: Boolean
        get() = settingsState.coalescedTrackpadScrollingEnabled
        set(value) {
            settingsState.coalescedTrackpadScrollingEnabled = value
        }

    fun rememberTheme(theme: String) {
        if (theme == LIGHT || theme == DARK) {
            settingsState.preferredTheme = theme
        }
    }

    override fun getState(): SettingsState = settingsState

    override fun loadState(state: SettingsState) {
        settingsState = state
    }

    class SettingsState {
        var preferredTheme: String? = null
        var adaptiveOsrFrameRateEnabled: Boolean = true
        var nativeTrackpadZoomEnabled: Boolean = true
        var coalescedTrackpadScrollingEnabled: Boolean = true
    }

    companion object {
        const val LIGHT = "light"
        const val DARK = "dark"

        fun getInstance(): ExcalidrawThemeSettings = service()
    }
}
