package com.example.simplecontroller.net

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ConnectionRestorePolicyTest {
    @Test fun upgradeInfersUsbOnlyWhenNoModeWasSavedAndUsbNetworkIsPresent() {
        assertEquals(SavedConnectionMode.MANUAL, ConnectionRestorePolicy.mode(null, false))
        assertEquals(SavedConnectionMode.USB_TETHER, ConnectionRestorePolicy.mode(null, true))
    }

    @Test fun explicitManualChoiceWinsEvenWhileUsbTetheringRemainsEnabled() {
        for (usbPresent in listOf(false, true)) {
            assertEquals(SavedConnectionMode.MANUAL,
                ConnectionRestorePolicy.mode(SavedConnectionMode.MANUAL.name, usbPresent))
        }
    }

    @Test fun savedUsbChoiceSurvivesReopenBeforeTheUsbNetworkIsReady() {
        for (usbPresent in listOf(false, true)) {
            assertEquals(SavedConnectionMode.USB_TETHER,
                ConnectionRestorePolicy.mode(SavedConnectionMode.USB_TETHER.name, usbPresent))
        }
    }

    @Test fun unknownSavedModeUsesManualInsteadOfInferringAnUnrequestedUsbConnection() {
        for (value in listOf("", "usb_tether", "FUTURE_MODE")) {
            assertEquals(SavedConnectionMode.MANUAL, ConnectionRestorePolicy.mode(value, true))
        }
    }

    @Test fun usbRestoreNeverLoadsTheSavedManualEndpointOrConsoleBridgeChoice() {
        for (pending in listOf(false, true)) {
            assertFalse(ConnectionRestorePolicy.shouldLoadManual(SavedConnectionMode.USB_TETHER, pending))
        }
        assertTrue(ConnectionRestorePolicy.shouldLoadManual(SavedConnectionMode.MANUAL, false))
        assertFalse(ConnectionRestorePolicy.shouldLoadManual(SavedConnectionMode.MANUAL, true))
    }

    @Test fun reopenedUsbRediscoversWhenAutoReconnectIsEnabledInsteadOfStartingWifi() {
        val mode = ConnectionRestorePolicy.mode(SavedConnectionMode.USB_TETHER.name, true)
        assertTrue(ConnectionRestorePolicy.shouldDiscoverUsb(mode, false, true, false))
        assertFalse(ConnectionRestorePolicy.shouldStartManual(mode, false, true, false))
    }

    @Test fun disablingAutoReconnectSuppressesNormalUsbResumeWithoutChangingTheSavedMode() {
        val mode = ConnectionRestorePolicy.mode(SavedConnectionMode.USB_TETHER.name, true)
        assertFalse(ConnectionRestorePolicy.shouldDiscoverUsb(mode, false, false, false))
        assertFalse(ConnectionRestorePolicy.shouldStartManual(mode, false, false, false))
        assertEquals(SavedConnectionMode.USB_TETHER, mode)
    }

    @Test fun upgradeWithActiveUsbAlsoDiscoversWithoutLoadingTheSavedWifiEndpoint() {
        val mode = ConnectionRestorePolicy.mode(null, true)
        assertTrue(ConnectionRestorePolicy.shouldDiscoverUsb(mode, false, true, false))
        assertFalse(ConnectionRestorePolicy.shouldLoadManual(mode, false))
        assertFalse(ConnectionRestorePolicy.shouldStartManual(mode, false, true, false))
    }

    @Test fun selectedUsbDoesNotStartDuplicateDiscoveryForAnAlreadyActiveAttemptOrConnection() {
        assertFalse(ConnectionRestorePolicy.shouldDiscoverUsb(SavedConnectionMode.USB_TETHER, false, true, true))
        assertFalse(ConnectionRestorePolicy.shouldStartManual(SavedConnectionMode.USB_TETHER, false, true, true))
    }

    @Test fun explicitTetherSettingsReturnOverridesThePreviousModeAndConnectionStatus() {
        for (mode in SavedConnectionMode.values()) {
            for (connectedOrConnecting in listOf(false, true)) {
                assertFalse(ConnectionRestorePolicy.shouldLoadManual(mode, true))
                for (autoReconnect in listOf(false, true)) {
                    assertTrue(ConnectionRestorePolicy.shouldDiscoverUsb(
                        mode, true, autoReconnect, connectedOrConnecting))
                    assertFalse(ConnectionRestorePolicy.shouldStartManual(
                        mode, true, autoReconnect, connectedOrConnecting))
                }
            }
        }
    }

    @Test fun manualReopenStillHonorsAutoReconnectAndDoesNotDiscoverUsb() {
        assertFalse(ConnectionRestorePolicy.shouldDiscoverUsb(SavedConnectionMode.MANUAL, false, true, false))
        assertTrue(ConnectionRestorePolicy.shouldStartManual(SavedConnectionMode.MANUAL, false, true, false))
        assertFalse(ConnectionRestorePolicy.shouldStartManual(SavedConnectionMode.MANUAL, false, false, false))
        assertFalse(ConnectionRestorePolicy.shouldStartManual(SavedConnectionMode.MANUAL, false, true, true))
        assertFalse(ConnectionRestorePolicy.shouldStartManual(SavedConnectionMode.MANUAL, false, false, true))
    }

    @Test fun canceledOrFailedUsbDiscoveryCannotRestartThePreviousWifiEndpoint() {
        val startGate = UsbTetherStartGate()
        val attempts = UsbTetherDiscoveryAttempts()
        assertTrue(startGate.canStart())

        startGate.requireDiscovery()
        val canceled = attempts.next()
        attempts.invalidate()
        assertFalse(attempts.applyIfCurrent(canceled) { startGate.endpointConfigured() })
        assertFalse("A late canceled result must not unblock the previous Wi-Fi endpoint", startGate.canStart())

        val failed = attempts.next()
        assertTrue(attempts.applyIfCurrent(failed) {
            // A current discovery failure reports an error without configuring an endpoint.
        })
        assertFalse("Floating preparation must still wait for a selected-route endpoint", startGate.canStart())
    }

    @Test fun verifiedUsbEndpointOrExplicitManualConfigurationAllowsStartupAgain() {
        val startGate = UsbTetherStartGate()
        val attempts = UsbTetherDiscoveryAttempts()
        startGate.requireDiscovery()
        val discovery = attempts.next()
        assertTrue(attempts.applyIfCurrent(discovery) { startGate.endpointConfigured() })
        assertTrue(startGate.canStart())

        startGate.requireDiscovery()
        attempts.invalidate()
        assertTrue(ConnectionRestorePolicy.shouldLoadManual(SavedConnectionMode.MANUAL, false))
        startGate.endpointConfigured()
        assertTrue("An explicit manual connection must remain available after a USB failure", startGate.canStart())
    }

    @Test fun anUnissuedAttemptCannotApplyAReceiverEndpoint() {
        val attempts = UsbTetherDiscoveryAttempts()
        assertFalse(attempts.isCurrent(0L))
        assertFalse(attempts.applyIfCurrent(0L) { fail("No discovery has started") })
    }

    @Test fun closingDiscoveryPreventsItsCompletionFromReplacingTheManualRoute() {
        val attempts = UsbTetherDiscoveryAttempts()
        val usb = attempts.next()
        attempts.invalidate()
        var route = "manual"
        assertFalse(attempts.applyIfCurrent(usb) { route = "obsolete USB" })
        assertEquals("manual", route)
        assertFalse(attempts.isCurrent(usb))
    }

    @Test fun newerDiscoveryPreventsAnOlderFailureOrEndpointFromOverwritingIt() {
        val attempts = UsbTetherDiscoveryAttempts()
        val first = attempts.next()
        val second = attempts.next()
        var route = "searching for replacement"
        assertFalse(attempts.applyIfCurrent(first) { route = "old failure" })
        assertTrue(attempts.applyIfCurrent(second) { route = "replacement USB" })
        assertFalse(attempts.applyIfCurrent(first) { route = "old endpoint" })
        assertEquals("replacement USB", route)
    }

    @Test fun applyingTheCurrentEndpointMayReentrantlyInvalidateAndConfigureItsReplacement() {
        val attempts = UsbTetherDiscoveryAttempts()
        val discovery = attempts.next()
        var replacement = 0L
        assertTrue(attempts.applyIfCurrent(discovery) {
            attempts.invalidate()
            replacement = attempts.next()
            assertTrue(attempts.isCurrent(replacement))
        })
        assertFalse(attempts.isCurrent(discovery))
        assertTrue(attempts.isCurrent(replacement))
    }

    @Test fun manualReplacementCannotBeOverwrittenByACallbackAlreadyApplyingOnAnotherThread() {
        val attempts = UsbTetherDiscoveryAttempts()
        val discovery = attempts.next()
        val applying = CountDownLatch(1)
        val finishApplying = CountDownLatch(1)
        val replacing = CountDownLatch(1)
        val replaced = CountDownLatch(1)
        val route = AtomicReference("searching")
        val failure = AtomicReference<Throwable?>()
        val callback = Thread {
            try {
                assertTrue(attempts.applyIfCurrent(discovery) {
                    applying.countDown()
                    assertTrue(finishApplying.await(3, TimeUnit.SECONDS))
                    route.set("USB")
                })
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        val replacement = Thread {
            try {
                replacing.countDown()
                attempts.invalidate()
                route.set("manual")
                replaced.countDown()
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        callback.start()
        try {
            assertTrue(applying.await(3, TimeUnit.SECONDS))
            replacement.start()
            assertTrue(replacing.await(3, TimeUnit.SECONDS))
            assertFalse("Replacement must wait until the current callback completes",
                replaced.await(100, TimeUnit.MILLISECONDS))
        } finally {
            finishApplying.countDown()
            callback.join(3000)
            if (replacement.state != Thread.State.NEW) replacement.join(3000)
        }
        assertFalse(callback.isAlive)
        assertFalse(replacement.isAlive)
        failure.get()?.let { throw AssertionError("Concurrent route update failed", it) }
        assertEquals("manual", route.get())
        assertFalse(attempts.isCurrent(discovery))
    }

    @Test fun callbackFailurePropagatesAndDoesNotKeepTheAttemptLockHeld() {
        val attempts = UsbTetherDiscoveryAttempts()
        val discovery = attempts.next()
        val failure = IllegalStateException("original connection failure")
        try {
            attempts.applyIfCurrent(discovery) { throw failure }
            fail("The original failure must propagate")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        attempts.invalidate()
        assertFalse(attempts.isCurrent(discovery))
        assertTrue(attempts.isCurrent(attempts.next()))
    }
}
