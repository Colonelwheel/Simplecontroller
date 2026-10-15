package com.example.simplecontroller.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.children
import com.example.simplecontroller.io.deepCopyControls
import com.example.simplecontroller.model.*
import com.example.simplecontroller.net.UdpClient

/** Page-owned detached instances. Canonical Control geometry never changes during Play. */
class FloatingControlsController(
    private val context: Context,
    private val canvas: FrameLayout,
    private val controls: () -> List<Control>,
    private val page: () -> ControllerPage,
    private val orientation: () -> LayoutOrientation,
    private val createControl: (Control) -> ControlView,
    private val createRing: (FloatingZone) -> FloatingRingView,
    private val editZone: (FloatingZone) -> Unit,
    private val moveZone: (String, ControlGeometry) -> Boolean
) {
    private data class Instance(
        val zone: FloatingZone, val views: List<View>, val finishView: View?,
        val members: List<ControlView>, val ring: FloatingRingView?
    )
    private data class Capture(
        val instance: Instance, val pointer: Int, var target: View?,
        var initial: Boolean, val offsetX: Float = 0f, val offsetY: Float = 0f,
        val finishEntry: FinishEntryGuard = FinishEntryGuard(),
        val resumedAt: Long? = null
    )
    private data class WaitingZone(val zone:FloatingZone, val pageId:String,
                                   val orientation:LayoutOrientation, val canvasMetrics:List<Int>,
                                   val ticket:UdpClient.FloatingPreparationTicket)
    private val instances = linkedMapOf<String, Instance>()
    private val editorViews = mutableListOf<View>()
    private var capture: Capture? = null
    private var consumed = false
    private val density get() = context.resources.displayMetrics.density
    private val pendingHandler=Handler(Looper.getMainLooper())
    private var pendingEvent:MotionEvent?=null
    private var pendingPointer=-1
    private var pendingZoneId:String?=null
    private val pendingGesture=PendingFloatingGesture<WaitingZone>(
        now=SystemClock::uptimeMillis,
        schedule={ delay,task->
            val runnable=Runnable { task() }
            pendingHandler.postDelayed(runnable,delay)
            ButtonAimScheduledTask { pendingHandler.removeCallbacks(runnable) }
        },
        onReady=::resumeZone,
        onAbandoned={ timedOut->
            clearPendingEvent();consumed=true
            if(timedOut) Toast.makeText(context,"Floating controls are not ready yet.",Toast.LENGTH_SHORT).show()
        }
    )

    init {
        canvas.addOnLayoutChangeListener { _,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom ->
            if (GlobalSettings.editMode && editorViews.isNotEmpty() &&
                (right-left != oldRight-oldLeft || bottom-top != oldBottom-oldTop)) canvas.post {
                if (GlobalSettings.editMode && editorViews.isNotEmpty() &&
                    canvas.width == right-left && canvas.height == bottom-top) bindPage()
            }
        }
    }

    fun bindPage() {
        editorViews.filterIsInstance<FloatingZoneOutline>().forEach { it.cancelEditingGesture() }
        editorViews.forEach(canvas::removeView)
        editorViews.clear()
        if (!GlobalSettings.editMode) return
        val boundPage = page().id
        val boundOrientation = orientation()
        page().floatingZones.asReversed().forEach { zone ->
            val rectangle = zoneRect(zone)
            val outline = FloatingZoneOutline(context,zone.name.ifBlank { "Floating zone" },zone.enabled,rectangle,
                onEdit = { page().floatingZones.firstOrNull { it.id == zone.id }?.let(editZone) },
                onMoved = { rectangle ->
                    GlobalSettings.editMode && page().id == boundPage && orientation() == boundOrientation &&
                        moveZone(zone.id,rectangle)
                })
            addAt(outline,rectangle)
            canvas.removeView(outline)
            canvas.addView(outline,0)
            editorViews += outline
        }
    }
    fun hiddenMemberIds(): Set<String> = if (GlobalSettings.editMode) emptySet()
        else page().floatingZones.flatMap { it.memberIds }.toSet()
    fun clear(removeEditorViews:Boolean=true) {
        val interrupted=consumed || pendingGesture.isPending || capture!=null
        cancelPendingGesture()
        capture=null
        instances.keys.toList().forEach(::finish)
        editorViews.filterIsInstance<FloatingZoneOutline>().forEach { it.cancelEditingGesture() }
        if(removeEditorViews) { editorViews.forEach(canvas::removeView);editorViews.clear() }
        consumed=interrupted
    }
    private fun zoneRect(zone: FloatingZone): ControlGeometry {
        val source=zone.geometryFor(orientation()) ?: zone.geometryFor(orientation().opposite())!!
        return source.zoneOnCanvas(canvas.width.toFloat(),canvas.height.toFloat())
    }
    private fun usable()=ControlGeometry(canvas.paddingLeft.toFloat(),canvas.paddingTop.toFloat(),
        (canvas.width-canvas.paddingLeft-canvas.paddingRight).toFloat(),
        (canvas.height-canvas.paddingTop-canvas.paddingBottom).toFloat())

    private fun canvasMetrics()=listOf(canvas.width,canvas.height,canvas.paddingLeft,canvas.paddingTop,
        canvas.paddingRight,canvas.paddingBottom)
    private fun waitingIsCurrent(waiting:WaitingZone):Boolean =
        !GlobalSettings.editMode && canvas.isAttachedToWindow &&
            (context as? android.app.Activity)?.hasWindowFocus()!=false &&
            page().id==waiting.pageId && orientation()==waiting.orientation && canvasMetrics()==waiting.canvasMetrics &&
            waiting.zone.enabled && page().floatingZones.any { it==waiting.zone } &&
            waiting.zone.id !in instances && UdpClient.isFloatingPreparationCurrent(waiting.ticket)

    private fun clearPendingEvent() {
        pendingEvent?.recycle();pendingEvent=null;pendingPointer=-1;pendingZoneId=null
    }
    private fun cancelPendingGesture() { pendingGesture.cancel();clearPendingEvent() }
    private fun rememberPendingEvent(event:MotionEvent) {
        pendingEvent?.recycle();pendingEvent=MotionEvent.obtainNoHistory(event)
    }
    private fun waitForZone(zone:FloatingZone,event:MotionEvent) {
        val waiting=WaitingZone(zone,page().id,orientation(),canvasMetrics(),UdpClient.captureFloatingPreparationTicket())
        pendingPointer=event.getPointerId(0);pendingZoneId=zone.id
        rememberPendingEvent(event)
        pendingGesture.begin(waiting,pendingPointer,event.x,event.y,
            isCurrent={ waitingIsCurrent(waiting) },
            isReady={ UdpClient.prepareFloatingControls(waiting.ticket) })
    }
    private fun visibleHitAt(x:Float,y:Float):View? {
        val hits=canvas.children.toList().asReversed().filter {
            it.visibility==View.VISIBLE && it !in editorViews &&
                ControlGeometry(it.x,it.y,it.width.toFloat(),it.height.toFloat()).containsPoint(x,y) &&
                (it !is FloatingRingView || it.acceptsDown(x-it.x,y-it.y))
        }
        val surfaced=instances.values.flatMap { it.views }.toSet()
        return hits.firstOrNull { it !in surfaced } ?: hits.firstOrNull()
    }
    private fun resumeZone(waiting:WaitingZone,pointer:Int,x:Float,y:Float,time:Long) {
        val source=pendingEvent
        val occupied=visibleHitAt(x,y)!=null || instances.values.any { instance->
            instance.ring?.let { it.geometry.roleAt(x-it.x,y-it.y)!=null }==true
        }
        if(source==null || !waitingIsCurrent(waiting) || !UdpClient.floatingSupported ||
            !usable().containsPoint(x,y) || occupied) {
            clearPendingEvent();consumed=true;return
        }
        val down=floatingActivationDown(source,pointer,x,y,time)
        clearPendingEvent()
        if(down==null) { consumed=true;return }
        try { startZone(waiting.zone,x,y,down,resumedAt=time) } finally { down.recycle() }
    }
    private fun startZone(zone:FloatingZone,x:Float,y:Float,event:MotionEvent,resumedAt:Long?=null) {
        val spawned=summon(zone,x,y) ?: run { consumed=true;return }
        val target=spawned.ring ?: spawned.members.firstOrNull { it.model.id==zone.startMemberId }
        capture=Capture(spawned,event.getPointerId(0),target,true,
            lastPlacement?.grabOffsetX ?: 0f,lastPlacement?.grabOffsetY ?: 0f,
            FinishEntryGuard(spawned.finishView?.let {
                ControlGeometry(it.x,it.y,it.width.toFloat(),it.height.toFloat()).containsPoint(x,y)
            }==true),resumedAt)
        consumed=false
        if(target!=null) deliver(capture!!,event)
    }

    /** Translate a clone once; all pointers, contact properties and history are retained. */
    fun dispatch(original:MotionEvent):Boolean {
        if(GlobalSettings.editMode) return false
        var event=MotionEvent.obtain(original)
        val location=IntArray(2);canvas.getLocationOnScreen(location)
        event.offsetLocation(original.rawX-original.x-location[0],original.rawY-original.y-location[1])
        try {
            if(event.actionMasked==MotionEvent.ACTION_DOWN) {
                cancelPendingGesture()
                consumed=false;capture=null
                val x=event.x;val y=event.y
                val hit=visibleHitAt(x,y)
                if(hit!=null) {
                    val instance=instances.values.firstOrNull { hit in it.views } ?: return false
                    val ringFinish=instance.ring?.let {
                        hit===it && it.geometry.finish.contains(x-it.x,y-it.y)
                    }==true
                    if(hit==instance.finishView || ringFinish) { finish(instance.zone.id);consumed=true;return true }
                    if(!UdpClient.floatingSupported) { consumed=true;return true }
                    capture=Capture(instance,event.getPointerId(0),hit,false);deliver(capture!!,event)
                    return true
                }
                val direct=instances.values.toList().asReversed().firstOrNull { instance ->
                    instance.ring?.let { it.geometry.roleAt(x-it.x,y-it.y)!=null } == true
                }
                if(direct!=null) {
                    if(!UdpClient.floatingSupported) { consumed=true;return true }
                    capture=Capture(direct,event.getPointerId(0),direct.ring,false);deliver(capture!!,event);return true
                }
                val zone=page().floatingZones.asReversed().firstOrNull {
                    it.enabled && it.id !in instances && it.ring?.isActive!=false && zoneRect(it).containsPoint(x,y)
                } ?: return false
                if(!UdpClient.prepareFloatingControls()) {
                    waitForZone(zone,event)
                    consumed=true;return true
                }
                startZone(zone,x,y,event)
                return true
            }
            if(pendingGesture.isPending) {
                val index=event.findPointerIndex(pendingPointer)
                if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL ||
                    index<0 || (event.actionMasked==MotionEvent.ACTION_POINTER_UP &&
                        event.getPointerId(event.actionIndex)==pendingPointer)) {
                    cancelPendingGesture();consumed=true;return true
                }
                if(event.actionMasked==MotionEvent.ACTION_MOVE) {
                    rememberPendingEvent(event)
                    pendingGesture.update(pendingPointer,event.getX(index),event.getY(index))
                }
                return true
            }
            if(consumed) return true
            val active=capture ?: return false
            if(!UdpClient.floatingSupported) {
                capture=null;consumed=true;return true
            }
            val originalIndex=event.findPointerIndex(active.pointer)
            if(originalIndex<0 || (event.actionMasked==MotionEvent.ACTION_POINTER_UP &&
                    event.getPointerId(event.actionIndex)==active.pointer)) {
                val cancel=MotionEvent.obtain(event);cancel.action=MotionEvent.ACTION_CANCEL
                deliver(active,cancel);cancel.recycle();capture=null;consumed=true;return true
            }
            active.resumedAt?.let { startedAt->
                val filtered=resumedFloatingEvent(event,active.pointer,startedAt) ?: return true
                event.recycle();event=filtered
            }
            val index=event.findPointerIndex(active.pointer)
            val end=active.instance.finishView
            if(end!=null && event.actionMasked!=MotionEvent.ACTION_CANCEL) {
                val rect=ControlGeometry(end.x,end.y,end.width.toFloat(),end.height.toFloat())
                val crossed=(0 until event.historySize).any {
                    active.finishEntry.observe(rect.containsPoint(event.getHistoricalX(index,it),event.getHistoricalY(index,it)))
                } || active.finishEntry.observe(rect.containsPoint(event.getX(index),event.getY(index)))
                if(crossed) { finish(active.instance.zone.id);consumed=true;capture=null;return true }
            }
            if(active.instance.zone.groupSwipe && active.target is ControlView &&
                (active.target as ControlView).model.let { !it.buttonAimEnabled && !it.buttonDirectional.enabled } &&
                event.actionMasked==MotionEvent.ACTION_MOVE) {
                val next=active.instance.members.asReversed().firstOrNull {
                    !it.model.buttonAimEnabled && !it.model.buttonDirectional.enabled &&
                        ControlGeometry(it.x,it.y,it.width.toFloat(),it.height.toFloat())
                            .containsPoint(event.getX(index),event.getY(index))
                }
                if(next!=null && next!=active.target) {
                    val up=MotionEvent.obtain(event);up.action=MotionEvent.ACTION_UP;deliver(active,up);up.recycle()
                    active.target=next
                    active.initial=false
                    val down=MotionEvent.obtain(event);down.action=MotionEvent.ACTION_DOWN;deliver(active,down);down.recycle()
                }
            }
            deliver(active,event)
            if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) capture=null
            return true
        } finally { event.recycle() }
    }
    private fun deliver(captured:Capture,event:MotionEvent) {
        if(instances[captured.instance.zone.id]!==captured.instance) return
        val target=captured.target ?: return
        val copy=MotionEvent.obtain(event);copy.offsetLocation(-target.x,-target.y)
        if(target is ControlView && captured.initial)
            copy.offsetLocation(-captured.offsetX,-captured.offsetY)
        try {
            if(target is FloatingRingView) {
                if(captured.initial && event.actionMasked==MotionEvent.ACTION_DOWN)
                    target.beginInitial(copy,captured.instance.zone.ring!!.startRole)
                else target.playTouch(copy)
            } else if(target is ControlView) target.playTouch(copy)
        } finally { copy.recycle() }
    }
    private var lastPlacement:FloatingPlacement?=null
    private fun summon(zone:FloatingZone,x:Float,y:Float):Instance? {
        val endHeight=48f*density
        if(zone.kind==FloatingZoneKind.RING) {
            val ring=createRing(zone)
            val anchor=ring.geometry.startAnchor(zone.ring!!.startRole)
            val p=placeFloatingBounds(ring.geometry.width,ring.geometry.height,anchor.x,anchor.y,x,y,usable())
                ?: run { ring.releaseAll();return cannotFit() }
            lastPlacement=p
            addAt(ring,ControlGeometry(p.x,p.y,ring.geometry.width,ring.geometry.height))
            return Instance(zone,listOf(ring),null,emptyList(),ring).also { instances[zone.id]=it }
        }
        val members=controls().filter { it.id in zone.memberIds }
        if(members.size!=zone.memberIds.size) return cannotFit()
        val saved=zone.memberGeometryFor(orientation()).ifEmpty { zone.memberGeometryFor(orientation().opposite()) }
        val geometries=members.associate { it.id to (saved[it.id] ?: ControlGeometry(it.x,it.y,it.w,it.h)) }
        val bounds=floatingBounds(geometries.values) ?: return null
        val start=geometries[zone.startMemberId]
        val anchorX=start?.let { it.x-bounds.x+it.w/2f } ?: bounds.w/2f
        val anchorY=(start?.let { it.y-bounds.y+it.h/2f } ?: bounds.h/2f)+endHeight
        val p=placeFloatingBounds(bounds.w,bounds.h+endHeight,anchorX,anchorY,x,y,usable()) ?: return cannotFit()
        lastPlacement=p
        val views=deepCopyControls(members).map { model ->
            val g=geometries.getValue(model.id)
            model.x=p.x+g.x-bounds.x;model.y=p.y+endHeight+g.y-bounds.y;model.w=g.w;model.h=g.h
            createControl(model).also { addAt(it,ControlGeometry(model.x,model.y,model.w,model.h)) }
        }
        val end=Button(context).apply {
            text="Finish "+zone.name.ifBlank { "group" };contentDescription=text
            setOnClickListener { finish(zone.id) }
        }
        addAt(end,ControlGeometry(p.x,p.y,bounds.w,endHeight))
        return Instance(zone,views+end,end,views,null).also { instances[zone.id]=it }
    }
    private fun cannotFit():Instance? {
        Toast.makeText(context,"This floating control does not fit. Edit its size or arrangement; targets were not shrunk.",Toast.LENGTH_LONG).show()
        return null
    }
    private fun addAt(view:View,g:ControlGeometry) {
        view.tag="floating-runtime"
        canvas.addView(view,FrameLayout.LayoutParams(g.w.toInt(),g.h.toInt()).apply {
            leftMargin=(g.x-canvas.paddingLeft).toInt();topMargin=(g.y-canvas.paddingTop).toInt()
        })
        view.measure(View.MeasureSpec.makeMeasureSpec(g.w.toInt(),View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(g.h.toInt(),View.MeasureSpec.EXACTLY))
        view.layout(g.x.toInt(),g.y.toInt(),(g.x+g.w).toInt(),(g.y+g.h).toInt())
    }
    fun finish(id:String) {
        if(pendingZoneId==id) { cancelPendingGesture();consumed=true }
        val instance=instances.remove(id) ?: return
        instance.ring?.finish()
        instance.members.forEach { it.releaseFloatingRuntime();it.markSafeForSilentDetach() }
        instance.views.forEach(canvas::removeView)
        if(capture?.instance==instance) { capture=null;consumed=true }
    }
    fun action(action:FloatingLocalAction,targetId:String) {
        val target=instances[targetId]
        when(action) {
            FloatingLocalAction.FINISH -> finish(targetId)
            FloatingLocalAction.STOP_FIRE -> target?.ring?.stopFiring()
            FloatingLocalAction.RELEASE_ADS -> target?.ring?.releaseAds()
            FloatingLocalAction.END_SUPPRESSION -> {
                target?.ring?.endSuppression()
                canvas.children.filterIsInstance<ControlView>().filter { it.model.id==targetId }.forEach { it.endMouseSuppression() }
            }
            FloatingLocalAction.NONE -> Unit
        }
    }
    fun showFinishList() {
        val entries=instances.values.toList()
        if(entries.isEmpty()) { Toast.makeText(context,"No floating controls are active.",Toast.LENGTH_SHORT).show();return }
        AlertDialog.Builder(context).setTitle("Finish floating control")
            .setItems(entries.map { it.zone.name.ifBlank { it.zone.id } }.toTypedArray()) { _,i -> finish(entries[i].zone.id) }
            .setNegativeButton("Cancel",null).show()
    }
}
