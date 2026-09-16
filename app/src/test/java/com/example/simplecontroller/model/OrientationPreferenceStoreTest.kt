package com.example.simplecontroller.model

import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationPreferenceStoreTest {
    @Test
    fun missingOrInvalidValue_defaultsToPortrait() {
        val values = mutableMapOf<String, String>()
        val store = OrientationPreferenceStore(mapBackend(values))

        assertEquals(LayoutOrientation.PORTRAIT, store.readPlayOrientation())
        values["playOrientation"] = "SIDEWAYS"
        assertEquals(LayoutOrientation.PORTRAIT, store.readPlayOrientation())
    }

    @Test
    fun savedPlayOrientation_persistsAcrossStoreInstances() {
        val values = mutableMapOf<String, String>()
        OrientationPreferenceStore(mapBackend(values))
            .writePlayOrientation(LayoutOrientation.LANDSCAPE)

        assertEquals(
            LayoutOrientation.LANDSCAPE,
            OrientationPreferenceStore(mapBackend(values)).readPlayOrientation()
        )
    }

    private fun mapBackend(values: MutableMap<String, String>) =
        object : OrientationPreferenceBackend {
            override fun read(key: String): String? = values[key]
            override fun write(key: String, value: String) {
                values[key] = value
            }
        }
}
