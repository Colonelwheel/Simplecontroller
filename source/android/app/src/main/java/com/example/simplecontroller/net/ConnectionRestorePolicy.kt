package com.example.simplecontroller.net

enum class SavedConnectionMode { MANUAL, USB_TETHER }

/** Restore the selected route without replacing the separately saved manual endpoint. */
object ConnectionRestorePolicy {
    const val PREFERENCE_KEY = "connectionMode"

    fun mode(storedValue: String?, usbNetworkPresent: Boolean): SavedConnectionMode =
        when (storedValue) {
            SavedConnectionMode.MANUAL.name -> SavedConnectionMode.MANUAL
            SavedConnectionMode.USB_TETHER.name -> SavedConnectionMode.USB_TETHER
            null -> if (usbNetworkPresent) SavedConnectionMode.USB_TETHER else SavedConnectionMode.MANUAL
            else -> SavedConnectionMode.MANUAL
        }

    fun shouldLoadManual(mode: SavedConnectionMode, usbSettingsReturnPending: Boolean): Boolean =
        mode == SavedConnectionMode.MANUAL && !usbSettingsReturnPending

    fun shouldDiscoverUsb(
        mode: SavedConnectionMode,
        usbSettingsReturnPending: Boolean,
        autoReconnect: Boolean,
        connectedOrConnecting: Boolean
    ): Boolean = usbSettingsReturnPending ||
        (mode == SavedConnectionMode.USB_TETHER && autoReconnect && !connectedOrConnecting)

    fun shouldStartManual(
        mode: SavedConnectionMode,
        usbSettingsReturnPending: Boolean,
        autoReconnect: Boolean,
        connectedOrConnecting: Boolean
    ): Boolean = shouldLoadManual(mode, usbSettingsReturnPending) &&
        autoReconnect && !connectedOrConnecting
}

/** Do not start the previous runtime endpoint while selected USB discovery is unresolved. */
class UsbTetherStartGate {
    @Volatile private var discoveryRequired = false

    fun requireDiscovery() {
        discoveryRequired = true
    }

    fun endpointConfigured() {
        discoveryRequired = false
    }

    fun canStart(): Boolean = !discoveryRequired
}

/** A discovery completion must still own its attempt when it applies a route or status. */
class UsbTetherDiscoveryAttempts {
    private var sequence = 0L
    private var currentAttempt: Long? = null

    @Synchronized fun next(): Long = (++sequence).also { currentAttempt = it }

    @Synchronized fun invalidate() {
        currentAttempt = null
    }

    @Synchronized fun isCurrent(attempt: Long): Boolean = currentAttempt == attempt

    // Serialize the callback with close/replacement, allowing reentrant configuration
    // to invalidate this attempt without rejecting the callback that already owns it.
    @Synchronized fun applyIfCurrent(attempt: Long, action: () -> Unit): Boolean {
        if (currentAttempt != attempt) return false
        action()
        return true
    }
}
