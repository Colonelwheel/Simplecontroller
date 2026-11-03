package com.example.simplecontroller.ui

import org.junit.Assert.*
import org.junit.Test

class FloatingSelectionSessionTest {
    private class Fixture {
        val queued = mutableListOf<() -> Unit>()
        val previews = mutableListOf<String>()
        var current = true
        var removals = 0
        var duringRemoval: () -> Unit = {}
        val session = FloatingSelectionSession<String>(queued::add,
            { removals++; duringRemoval() }, { current }, previews::add)
        fun drain() {
            while (queued.isNotEmpty()) queued.removeAt(0).invoke()
        }
    }

    @Test fun releaseThenRemovalCancellation_completesExactlyOnceAfterDispatch() {
        val f = Fixture()
        f.duringRemoval = { f.session.complete(null) }
        f.session.complete("rectangle")
        assertTrue(f.session.completed)
        assertEquals(0, f.removals)
        assertTrue(f.previews.isEmpty())
        f.drain()
        assertEquals(1, f.removals)
        assertEquals(listOf("rectangle"), f.previews)
        f.session.complete("duplicate")
        f.drain()
        assertEquals(1, f.removals)
    }

    @Test fun backBeforeDrawing_thenStrayReleaseNeverOpensPreview() {
        val f = Fixture()
        f.session.cancel()
        f.session.complete("late release")
        f.drain()
        assertEquals(1, f.removals)
        assertTrue(f.previews.isEmpty())
    }

    @Test fun backOrPauseAfterRelease_vetoesQueuedPreview() {
        val f = Fixture()
        f.session.complete("rectangle")
        f.session.cancel()
        f.drain()
        assertEquals(1, f.removals)
        assertTrue(f.previews.isEmpty())
    }

    @Test fun pageOrOrientationChange_removesOverlayWithoutStalePreview() {
        val f = Fixture()
        f.session.complete("rectangle")
        f.current = false
        f.drain()
        assertEquals(1, f.removals)
        assertTrue(f.previews.isEmpty())
    }

    @Test fun replacingSelection_oldCleanupCannotRemoveNewOverlay() {
        val queue = mutableListOf<() -> Unit>()
        val overlays = mutableSetOf("first", "second")
        val previews = mutableListOf<String>()
        val first = FloatingSelectionSession<String>(queue::add,
            { overlays.remove("first") }, { false }, previews::add)
        val second = FloatingSelectionSession<String>(queue::add,
            { overlays.remove("second") }, { true }, previews::add)
        first.complete("old rectangle")
        second.complete("new rectangle")
        queue.removeAt(0).invoke()
        assertEquals(setOf("second"), overlays)
        assertTrue(previews.isEmpty())
        queue.removeAt(0).invoke()
        assertTrue(overlays.isEmpty())
        assertEquals(listOf("new rectangle"), previews)
    }
}
