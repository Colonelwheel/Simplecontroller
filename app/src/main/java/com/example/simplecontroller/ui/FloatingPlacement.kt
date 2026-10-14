package com.example.simplecontroller.ui

import com.example.simplecontroller.model.ControlGeometry

/** Pixel-space translation only: runtime placement never scales saved touch targets. */
data class FloatingPlacement(val x: Float, val y: Float, val grabOffsetX: Float, val grabOffsetY: Float)

fun placeFloatingBounds(
    width: Float, height: Float, anchorX: Float, anchorY: Float,
    touchX: Float, touchY: Float, usable: ControlGeometry
): FloatingPlacement? {
    if (listOf(width, height, anchorX, anchorY, touchX, touchY, usable.x, usable.y, usable.w, usable.h)
            .any { !it.isFinite() } || width <= 0f || height <= 0f || width > usable.w || height > usable.h
    ) return null
    val desiredX = touchX - anchorX
    val desiredY = touchY - anchorY
    val x = desiredX.coerceIn(usable.x, usable.x + usable.w - width)
    val y = desiredY.coerceIn(usable.y, usable.y + usable.h - height)
    return FloatingPlacement(x, y, desiredX - x, desiredY - y)
}

fun ControlGeometry.containsPoint(x: Float, y: Float): Boolean =
    x >= this.x && y >= this.y && x <= this.x + w && y <= this.y + h

fun ControlGeometry.fullyContains(other: ControlGeometry): Boolean =
    other.x >= x && other.y >= y && other.x + other.w <= x + w && other.y + other.h <= y + h

fun floatingBounds(members: Collection<ControlGeometry>): ControlGeometry? {
    if (members.isEmpty()) return null
    val x = members.minOf { it.x }; val y = members.minOf { it.y }
    return ControlGeometry(x, y, members.maxOf { it.x + it.w } - x, members.maxOf { it.y + it.h } - y)
}

/** An edge-clamped birth inside Finish must first leave before deliberately entering it. */
class FinishEntryGuard(initiallyInside:Boolean=false) {
    private var eligible=!initiallyInside
    fun observe(inside:Boolean):Boolean {
        if(!inside) { eligible=true;return false }
        return eligible
    }
}
