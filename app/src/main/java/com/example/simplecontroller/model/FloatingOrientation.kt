package com.example.simplecontroller.model

import com.example.simplecontroller.ui.floatingBounds
import com.example.simplecontroller.ui.placeFloatingBounds

/** Keep Button group sizes and relative spacing when deriving an orientation. */
fun ControllerPage.controlsForFloatingOrientation(
    orientation:LayoutOrientation,width:Float,height:Float
):List<Control> {
    val groupedGeometry=mutableMapOf<String,ControlGeometry>()
    floatingZones.filter { it.kind!=FloatingZoneKind.RING }.forEach { zone ->
        val current=zone.memberGeometryFor(orientation)
        val alternate=zone.memberGeometryFor(orientation.opposite())
        val saved=when {
            current.keys.containsAll(zone.memberIds) -> current
            alternate.keys.containsAll(zone.memberIds) -> alternate
            else -> zone.memberIds.associateWith { id->current[id] ?: alternate[id] ?:
                controls.first { it.id==id }.let { ControlGeometry(it.x,it.y,it.w,it.h) } }
        }
        val bounds=floatingBounds(saved.values) ?: return@forEach
        val p=if(width>0f && height>0f) placeFloatingBounds(bounds.w,bounds.h,0f,0f,bounds.x,bounds.y,
            ControlGeometry(0f,0f,width,height)) else null
        saved.forEach { (id,g)->groupedGeometry[id]=g.copy(x=g.x+(p?.x ?: bounds.x)-bounds.x,y=g.y+(p?.y ?: bounds.y)-bounds.y) }
    }
    return controlsForOrientation(orientation,width,height).map { c->
        groupedGeometry[c.id]?.let { c.copy(x=it.x,y=it.y,w=it.w,h=it.h) } ?: c
    }
}
