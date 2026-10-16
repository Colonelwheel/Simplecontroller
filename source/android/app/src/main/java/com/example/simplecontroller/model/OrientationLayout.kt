package com.example.simplecontroller.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.min

enum class LayoutOrientation {
    PORTRAIT,
    LANDSCAPE;

    fun opposite(): LayoutOrientation = when (this) {
        PORTRAIT -> LANDSCAPE
        LANDSCAPE -> PORTRAIT
    }
}

@Serializable
data class ControlGeometry(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float
)

@Serializable
data class PageOrientationGeometry(
    val canvasWidth: Float = 0f,
    val canvasHeight: Float = 0f,
    val controls: Map<String, ControlGeometry> = emptyMap()
)

fun capturePageGeometry(
    controls: List<Control>,
    canvasWidth: Float,
    canvasHeight: Float
): PageOrientationGeometry = PageOrientationGeometry(
    canvasWidth = canvasWidth.coerceAtLeast(0f),
    canvasHeight = canvasHeight.coerceAtLeast(0f),
    controls = controls.associate { control ->
        control.id to ControlGeometry(control.x, control.y, control.w, control.h)
    }
)

fun ControllerPage.geometryFor(orientation: LayoutOrientation): PageOrientationGeometry? =
    when (orientation) {
        LayoutOrientation.PORTRAIT -> portraitGeometry
        LayoutOrientation.LANDSCAPE -> landscapeGeometry
    }

fun ControllerPage.withGeometry(
    orientation: LayoutOrientation,
    geometry: PageOrientationGeometry?
): ControllerPage = when (orientation) {
    LayoutOrientation.PORTRAIT -> copy(portraitGeometry = geometry)
    LayoutOrientation.LANDSCAPE -> copy(landscapeGeometry = geometry)
}

/**
 * Resolves controls for one canvas without mutating either saved orientation. Missing per-control
 * geometry is copied from the other orientation, then fitted into the requested canvas.
 */
fun ControllerPage.controlsForOrientation(
    orientation: LayoutOrientation,
    destinationWidth: Float,
    destinationHeight: Float
): List<Control> {
    val width = destinationWidth.takeIf { it > 0f }
    val height = destinationHeight.takeIf { it > 0f }
    val target = geometryFor(orientation)
    val alternateOrientation = orientation.opposite()
    val alternate = geometryFor(alternateOrientation)

    return controls.map { control ->
        val targetGeometry = target?.controls?.get(control.id)
        val alternateGeometry = alternate?.controls?.get(control.id)
        val sourceGeometry: ControlGeometry
        val sourceLayout: PageOrientationGeometry?
        val sourceOrientation: LayoutOrientation

        when {
            targetGeometry != null -> {
                sourceGeometry = targetGeometry
                sourceLayout = target
                sourceOrientation = orientation
            }
            alternateGeometry != null -> {
                sourceGeometry = alternateGeometry
                sourceLayout = alternate
                sourceOrientation = alternateOrientation
            }
            else -> {
                sourceGeometry = ControlGeometry(control.x, control.y, control.w, control.h)
                sourceLayout = null
                sourceOrientation = orientation
            }
        }

        if (width == null || height == null) {
            control.copy(
                x = sourceGeometry.x,
                y = sourceGeometry.y,
                w = sourceGeometry.w,
                h = sourceGeometry.h
            )
        } else {
            val fitted = fitControlGeometry(
                geometry = sourceGeometry,
                sourceCanvasWidth = sourceLayout?.canvasWidth ?: 0f,
                sourceCanvasHeight = sourceLayout?.canvasHeight ?: 0f,
                sourceOrientation = sourceOrientation,
                destinationCanvasWidth = width,
                destinationCanvasHeight = height,
                destinationOrientation = orientation
            )
            control.copy(x = fitted.x, y = fitted.y, w = fitted.w, h = fitted.h)
        }
    }
}

fun fitControlGeometry(
    geometry: ControlGeometry,
    sourceCanvasWidth: Float,
    sourceCanvasHeight: Float,
    sourceOrientation: LayoutOrientation,
    destinationCanvasWidth: Float,
    destinationCanvasHeight: Float,
    destinationOrientation: LayoutOrientation
): ControlGeometry {
    val destinationWidth = destinationCanvasWidth.coerceAtLeast(1f)
    val destinationHeight = destinationCanvasHeight.coerceAtLeast(1f)
    val inferredSourceWidth: Float
    val inferredSourceHeight: Float
    if (sourceCanvasWidth > 0f && sourceCanvasHeight > 0f) {
        inferredSourceWidth = sourceCanvasWidth
        inferredSourceHeight = sourceCanvasHeight
    } else if (sourceOrientation != destinationOrientation) {
        inferredSourceWidth = destinationHeight
        inferredSourceHeight = destinationWidth
    } else {
        inferredSourceWidth = destinationWidth
        inferredSourceHeight = destinationHeight
    }

    val scaleX = destinationWidth / inferredSourceWidth.coerceAtLeast(1f)
    val scaleY = destinationHeight / inferredSourceHeight.coerceAtLeast(1f)
    val sizeScale = min(scaleX, scaleY)
    val fittedWidth = (geometry.w.coerceAtLeast(1f) * sizeScale)
        .coerceIn(1f, destinationWidth)
    val fittedHeight = (geometry.h.coerceAtLeast(1f) * sizeScale)
        .coerceIn(1f, destinationHeight)
    val sourceCenterX = (geometry.x + geometry.w / 2f) / inferredSourceWidth.coerceAtLeast(1f)
    val sourceCenterY = (geometry.y + geometry.h / 2f) / inferredSourceHeight.coerceAtLeast(1f)
    val fittedX = (sourceCenterX * destinationWidth - fittedWidth / 2f)
        .coerceIn(0f, (destinationWidth - fittedWidth).coerceAtLeast(0f))
    val fittedY = (sourceCenterY * destinationHeight - fittedHeight / 2f)
        .coerceIn(0f, (destinationHeight - fittedHeight).coerceAtLeast(0f))

    return ControlGeometry(fittedX, fittedY, fittedWidth, fittedHeight)
}

fun PageOrientationGeometry.remapControlIds(
    idMap: Map<String, String>
): PageOrientationGeometry = copy(
    controls = controls.mapNotNull { (oldId, geometry) ->
        idMap[oldId]?.let { newId -> newId to geometry }
    }.toMap()
)

fun PageOrientationGeometry.withOnlyControlIds(ids: Set<String>): PageOrientationGeometry =
    copy(controls = controls.filterKeys(ids::contains))

fun PageOrientationGeometry.matchesCanvas(width: Float, height: Float): Boolean =
    canvasWidth > 0f && canvasHeight > 0f &&
        abs(canvasWidth - width) < 0.5f && abs(canvasHeight - height) < 0.5f
