package com.agustinbanchio.excalidraw.editor

import kotlin.test.Test
import kotlin.test.assertTrue

class CefResourceRequestContractTest {
    @Test
    fun `legacy request handling continues synchronously`() {
        var continued = false

        val handled = CefResourceRequestContract.processRequest { continued = true }

        assertTrue(handled)
        assertTrue(continued)
    }

    @Test
    fun `new request handling opens synchronously`() {
        var handleRequest = false

        val opened = CefResourceRequestContract.open { handleRequest = it }

        assertTrue(opened)
        assertTrue(handleRequest)
    }
}
