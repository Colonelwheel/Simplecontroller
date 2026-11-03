package com.example.simplecontroller.ui

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.simplecontroller.model.ButtonDirectionStages
import com.example.simplecontroller.model.ButtonDirectionalSettings
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControlType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ButtonDirectionalGestureInstrumentedTest {
    @Test
    fun touchMoveRetreatAndLift_releaseEachAdditionThenBase() {
        val transport = RecordingTransport()
        val executor = executor(transport)
        val control = control("X360A")
        var pressed = false
        val handler = ButtonDirectionalGestureHandler(
            control, executor, { error(it) }, { pressed = it }
        )

        send(handler, MotionEvent.ACTION_DOWN, 100f, 100f)
        assertTrue(pressed)
        send(handler, MotionEvent.ACTION_MOVE, 100f, 40f)
        send(handler, MotionEvent.ACTION_MOVE, 100f, 0f)
        send(handler, MotionEvent.ACTION_MOVE, 100f, 50f)
        send(handler, MotionEvent.ACTION_MOVE, 100f, 100f)
        send(handler, MotionEvent.ACTION_UP, 100f, 100f)

        assertFalse(pressed)
        assertEquals(
            listOf(
                "X360A_HOLD", "X360B_HOLD", "X360Y_HOLD",
                "X360Y_RELEASE", "X360B_RELEASE", "X360A_RELEASE"
            ),
            transport.commands
        )
    }

    @Test
    fun sharedButtonAimBase_survivesDirectionalLift() {
        val transport = RecordingTransport()
        val executor = executor(transport)
        val base = (executor.activate(ButtonAimPayloadOwner.BASE, "X360B")
            as ButtonAimPayloadActivationResult.Activated).lease
        val control = control("X360B").apply { buttonAimEnabled = true }
        val handler = ButtonDirectionalGestureHandler(control, executor, { error(it) }, {})

        send(handler, MotionEvent.ACTION_DOWN, 100f, 100f, baseOwnedByAim = true)
        send(handler, MotionEvent.ACTION_MOVE, 100f, 40f, baseOwnedByAim = true)
        send(handler, MotionEvent.ACTION_UP, 100f, 40f, baseOwnedByAim = true)
        assertEquals(listOf("X360B_HOLD"), transport.commands)

        executor.release(base)
        assertEquals(listOf("X360B_HOLD", "X360B_RELEASE"), transport.commands)
    }

    private fun control(payload: String) = Control(
        id = "button", type = ControlType.BUTTON,
        x = 0f, y = 0f, w = 200f, h = 200f, payload = payload,
        buttonDirectional = ButtonDirectionalSettings(
            enabled = true,
            up = ButtonDirectionStages(40f, "X360B", 80f, "X360Y")
        )
    )

    private fun executor(transport: RecordingTransport) = ButtonAimPayloadExecutor(
        transport,
        ButtonAimPayloadScheduler { _, _ -> ButtonAimScheduledTask {} }
    )

    private fun send(
        handler: ButtonDirectionalGestureHandler,
        action: Int,
        x: Float,
        y: Float,
        baseOwnedByAim: Boolean = false
    ) {
        val event = MotionEvent.obtain(0L, 100L, action, x, y, 0)
        try {
            handler.onTouch(event, baseOwnedByAim)
        } finally {
            event.recycle()
        }
    }

    private class RecordingTransport : ButtonAimPayloadTransport {
        val commands = mutableListOf<String>()
        override fun sendCommand(command: String) { commands += command }
        override fun sendKey(key: String, pressed: Boolean) = Unit
        override fun sendStickMacro(stickName: String, x: Float, y: Float) = Unit
        override fun onCameraFollow(command: String) = Unit
        override fun onScrollToggle() = Unit
        override fun onReleaseAll() = Unit
    }
}
