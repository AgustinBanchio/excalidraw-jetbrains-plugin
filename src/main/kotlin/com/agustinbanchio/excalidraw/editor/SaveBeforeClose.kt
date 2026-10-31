package com.agustinbanchio.excalidraw.editor

/** Shared by tab closing and project shutdown; the caller keeps JCEF alive while awaiting the save. */
internal class SaveBeforeClose(
    private val awaitSave: () -> Throwable?,
    private val confirmDiscard: (Throwable) -> Boolean,
    private val discardPending: () -> Unit,
) {
    var closeAllowed = false
        private set

    fun markChanged() {
        closeAllowed = false
    }

    fun canClose(): Boolean {
        val failure = awaitSave()
        closeAllowed = failure == null || confirmDiscard(failure)
        if (failure != null && closeAllowed) discardPending()
        return closeAllowed
    }
}
