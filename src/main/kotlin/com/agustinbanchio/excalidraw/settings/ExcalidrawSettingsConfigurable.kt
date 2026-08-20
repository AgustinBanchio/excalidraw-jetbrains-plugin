package com.agustinbanchio.excalidraw.settings

import com.intellij.openapi.options.Configurable
import com.intellij.util.ui.FormBuilder
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class ExcalidrawSettingsConfigurable : Configurable {
    private var settingsPanel: JPanel? = null
    private var adaptiveFrameRateCheckBox: JCheckBox? = null
    private var nativeTrackpadZoomCheckBox: JCheckBox? = null
    private var coalescedTrackpadScrollingCheckBox: JCheckBox? = null

    override fun getDisplayName(): String = "Excalidraw Editor"

    override fun createComponent(): JComponent {
        adaptiveFrameRateCheckBox = JCheckBox("Match OSR rendering to the active display refresh rate")
        nativeTrackpadZoomCheckBox = JCheckBox("Use IntelliJ's native macOS pinch-to-zoom adapter")
        coalescedTrackpadScrollingCheckBox = JCheckBox("Use frame-coalesced trackpad scrolling in OSR")

        return FormBuilder.createFormBuilder()
            .addComponent(JLabel("JCEF off-screen rendering compatibility"))
            .addComponent(adaptiveFrameRateCheckBox!!)
            .addComponent(nativeTrackpadZoomCheckBox!!)
            .addComponent(coalescedTrackpadScrollingCheckBox!!)
            .addComponent(JLabel("Changes apply to newly opened Excalidraw editor tabs."))
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .also { settingsPanel = it }
    }

    override fun isModified(): Boolean {
        val settings = ExcalidrawThemeSettings.getInstance()
        return adaptiveFrameRateCheckBox?.isSelected?.let { it != settings.adaptiveOsrFrameRateEnabled } == true ||
            nativeTrackpadZoomCheckBox?.isSelected?.let { it != settings.nativeTrackpadZoomEnabled } == true ||
            coalescedTrackpadScrollingCheckBox?.isSelected
                ?.let { it != settings.coalescedTrackpadScrollingEnabled } == true
    }

    override fun apply() {
        val settings = ExcalidrawThemeSettings.getInstance()
        settings.adaptiveOsrFrameRateEnabled = adaptiveFrameRateCheckBox?.isSelected ?: true
        settings.nativeTrackpadZoomEnabled = nativeTrackpadZoomCheckBox?.isSelected ?: true
        settings.coalescedTrackpadScrollingEnabled = coalescedTrackpadScrollingCheckBox?.isSelected ?: true
    }

    override fun reset() {
        val settings = ExcalidrawThemeSettings.getInstance()
        adaptiveFrameRateCheckBox?.isSelected = settings.adaptiveOsrFrameRateEnabled
        nativeTrackpadZoomCheckBox?.isSelected = settings.nativeTrackpadZoomEnabled
        coalescedTrackpadScrollingCheckBox?.isSelected = settings.coalescedTrackpadScrollingEnabled
    }

    override fun disposeUIResources() {
        settingsPanel = null
        adaptiveFrameRateCheckBox = null
        nativeTrackpadZoomCheckBox = null
        coalescedTrackpadScrollingCheckBox = null
    }
}
