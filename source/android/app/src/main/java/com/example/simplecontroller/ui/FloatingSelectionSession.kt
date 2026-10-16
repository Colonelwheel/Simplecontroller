package com.example.simplecontroller.ui

/** Defers view removal until dispatch unwinds; cancellation can also veto a queued preview. */
internal class FloatingSelectionSession<T : Any>(
    private val post: (() -> Unit) -> Unit,
    private val removeOverlay: () -> Unit,
    private val isCurrent: () -> Boolean,
    private val preview: (T) -> Unit
) {
    var completed = false
        private set
    private var canceled = false

    fun complete(result: T?) {
        if (completed) return
        completed = true
        post {
            removeOverlay()
            if (!canceled && result != null && isCurrent()) preview(result)
        }
    }

    /** Unlike another terminal touch event, Back/pause may cancel an already queued result. */
    fun cancel() {
        canceled = true
        complete(null)
    }
}
