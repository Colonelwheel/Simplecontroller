package com.example.simplecontroller.model

import org.junit.Assert.*
import org.junit.Test

class FloatingOrientationTest {
    @Test fun activationFieldsMatchDisplayedCanvasAndNoOpSaveDoesNotRescaleAgain() {
        val saved=FloatingZoneGeometry(1000f,2000f,ControlGeometry(100f,200f,300.5f,400.5f))
        val displayed=saved.zoneOnCanvas(500f,1000f)
        assertEquals(ControlGeometry(50f,100f,150.25f,200.25f),displayed)
        val resaved=FloatingZoneGeometry(500f,1000f,displayed)
        assertEquals(displayed,resaved.zoneOnCanvas(500f,1000f))
        assertEquals(saved.zone,FloatingZoneGeometry(zone=saved.zone).zoneOnCanvas(500f,1000f))
    }

    @Test fun missingOrientationTranslatesGroupWithoutScalingOrChangingSpacing() {
        val a=Control("a",ControlType.BUTTON,20f,300f,100f,80f,"X360A")
        val b=Control("b",ControlType.BUTTON,150f,300f,100f,80f,"X360B")
        val saved=mapOf("a" to ControlGeometry(20f,300f,100f,80f),"b" to ControlGeometry(150f,300f,100f,80f))
        val zone=FloatingZone("group",kind=FloatingZoneKind.BUTTON_GROUP,memberIds=listOf("a","b"),startMemberId="a",
            portraitGeometry=FloatingZoneGeometry(300f,500f),portraitMemberGeometry=saved,portraitRestorationGeometry=saved)
        val page=ControllerPage("page","Page",listOf(a,b),floatingZones=listOf(zone))
        val result=page.controlsForFloatingOrientation(LayoutOrientation.LANDSCAPE,500f,200f)
        assertEquals(100f,result[0].w,0f);assertEquals(80f,result[0].h,0f)
        assertEquals(130f,result[1].x-result[0].x,0f);assertEquals(120f,result[0].y,0f)
        assertEquals(300f,a.y,0f);assertEquals(300f,zone.portraitRestorationGeometry.getValue("a").y,0f)
    }
    @Test fun impossibleGroupDoesNotSilentlyShrinkTargets() {
        val a=Control("a",ControlType.BUTTON,0f,0f,600f,80f,"X360A")
        val saved=mapOf("a" to ControlGeometry(0f,0f,600f,80f))
        val z=FloatingZone("z",kind=FloatingZoneKind.SINGLE_BUTTON,memberIds=listOf("a"),startMemberId="a",
            portraitGeometry=FloatingZoneGeometry(),portraitMemberGeometry=saved,portraitRestorationGeometry=saved)
        val result=ControllerPage("page","Page",listOf(a),floatingZones=listOf(z))
            .controlsForFloatingOrientation(LayoutOrientation.LANDSCAPE,300f,200f)
        assertEquals(600f,result.single().w,0f)
    }
}
