package com.example.simplecontroller.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationLayoutTest {
    @Test
    fun portraitAndLandscapeGeometry_areIndependent() {
        val control = button("fire", 10f, 20f, 100f, 80f)
        val portrait = capturePageGeometry(listOf(control), 1000f, 2000f)
        val landscapeControl = control.copy(x = 700f, y = 300f, w = 160f, h = 120f)
        val landscape = capturePageGeometry(listOf(landscapeControl), 2000f, 1000f)
        val page = ControllerPage(
            id = "base",
            name = "Base",
            controls = listOf(control),
            portraitGeometry = portrait,
            landscapeGeometry = landscape
        )

        val portraitResult = page.controlsForOrientation(LayoutOrientation.PORTRAIT, 1000f, 2000f)
        val landscapeResult = page.controlsForOrientation(LayoutOrientation.LANDSCAPE, 2000f, 1000f)

        assertEquals(10f, portraitResult.single().x, 0.001f)
        assertEquals(700f, landscapeResult.single().x, 0.001f)
        val changedPortrait = page.withGeometry(
            LayoutOrientation.PORTRAIT,
            capturePageGeometry(listOf(control.copy(x = 40f)), 1000f, 2000f)
        )
        assertEquals(700f, changedPortrait.landscapeGeometry!!.controls.getValue("fire").x, 0.001f)
        assertNotEquals(
            changedPortrait.portraitGeometry!!.controls.getValue("fire").x,
            changedPortrait.landscapeGeometry!!.controls.getValue("fire").x
        )
    }

    @Test
    fun firstLandscapeCopy_scalesAndClampsEveryControlInsideCanvas() {
        val page = ControllerPage(
            id = "base",
            name = "Base",
            controls = listOf(
                button("negative", -100f, -200f, 400f, 500f),
                button("oversized", 900f, 1900f, 4000f, 3000f)
            ),
            portraitGeometry = PageOrientationGeometry(
                canvasWidth = 1000f,
                canvasHeight = 2000f,
                controls = mapOf(
                    "negative" to ControlGeometry(-100f, -200f, 400f, 500f),
                    "oversized" to ControlGeometry(900f, 1900f, 4000f, 3000f)
                )
            )
        )

        val fitted = page.controlsForOrientation(LayoutOrientation.LANDSCAPE, 2000f, 1000f)

        fitted.forEach { control ->
            assertTrue(control.x >= 0f)
            assertTrue(control.y >= 0f)
            assertTrue(control.w in 1f..2000f)
            assertTrue(control.h in 1f..1000f)
            assertTrue(control.x + control.w <= 2000.001f)
            assertTrue(control.y + control.h <= 1000.001f)
        }
    }

    @Test
    fun missingControlGeometry_isDerivedFromOtherOrientationWithUnknownCanvasSize() {
        val existing = button("existing", 10f, 20f, 100f, 100f)
        val added = button("added", 800f, 1600f, 200f, 200f)
        val page = ControllerPage(
            id = "base",
            name = "Base",
            controls = listOf(existing, added),
            portraitGeometry = capturePageGeometry(listOf(existing, added), 0f, 0f),
            landscapeGeometry = PageOrientationGeometry(
                canvasWidth = 2000f,
                canvasHeight = 1000f,
                controls = mapOf("existing" to ControlGeometry(50f, 60f, 100f, 100f))
            )
        )

        val result = page.controlsForOrientation(LayoutOrientation.LANDSCAPE, 2000f, 1000f)
        val addedResult = result.first { it.id == "added" }

        assertTrue(addedResult.x >= 0f && addedResult.x + addedResult.w <= 2000.001f)
        assertTrue(addedResult.y >= 0f && addedResult.y + addedResult.h <= 1000.001f)
    }

    @Test
    fun remapControlIds_preservesDetachedGeometry() {
        val source = PageOrientationGeometry(
            100f,
            200f,
            mapOf("old" to ControlGeometry(1f, 2f, 3f, 4f))
        )

        val remapped = source.remapControlIds(mapOf("old" to "new"))

        assertTrue("old" !in remapped.controls)
        assertEquals(ControlGeometry(1f, 2f, 3f, 4f), remapped.controls["new"])
        assertTrue("old" in source.controls)
    }

    private fun button(id: String, x: Float, y: Float, w: Float, h: Float) = Control(
        id = id,
        type = ControlType.BUTTON,
        x = x,
        y = y,
        w = w,
        h = h,
        payload = "X360A"
    )
}
