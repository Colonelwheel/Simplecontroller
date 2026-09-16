package com.example.simplecontroller.model

interface OrientationPreferenceBackend {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

class OrientationPreferenceStore(
    private val backend: OrientationPreferenceBackend
) {
    fun readPlayOrientation(): LayoutOrientation =
        backend.read(PLAY_ORIENTATION_KEY)
            ?.let { value -> runCatching { LayoutOrientation.valueOf(value) }.getOrNull() }
            ?: LayoutOrientation.PORTRAIT

    fun writePlayOrientation(orientation: LayoutOrientation) {
        backend.write(PLAY_ORIENTATION_KEY, orientation.name)
    }

    private companion object {
        const val PLAY_ORIENTATION_KEY = "playOrientation"
    }
}
