package com.agustinbanchio.excalidraw.settings

import kotlin.test.Test
import kotlin.test.assertTrue

class ExcalidrawThemeSettingsTest {
    @Test
    fun `osr compatibility options default to enabled`() {
        val state = ExcalidrawThemeSettings.SettingsState()

        assertTrue(state.adaptiveOsrFrameRateEnabled)
        assertTrue(state.nativeTrackpadZoomEnabled)
        assertTrue(state.coalescedTrackpadScrollingEnabled)
    }
}
