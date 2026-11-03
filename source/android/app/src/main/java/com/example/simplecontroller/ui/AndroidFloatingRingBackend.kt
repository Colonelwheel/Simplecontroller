package com.example.simplecontroller.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.example.simplecontroller.model.*
import com.example.simplecontroller.net.PhoneOutputCoordinator
import com.example.simplecontroller.net.UdpClient
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Shared ownership/transport adapter; the pure ring never sends raw releases. */
class AndroidFloatingRingBackend(
    private val context:Context, private val config:FloatingRingConfig, zoneId:String
):FloatingRingBackend {
    private val root=UdpClient.beginOutputScope("ring:"+zoneId)
    private val moveOwner=Any()
    private val aimOwner=Any()
    private val suppressionOwner=Any()
    private val geometry=FloatingRingGeometry(config,context.resources.displayMetrics.density)
    private var retainedAim=false
    private val aim=AimOutputSession(aimOwner,{ geometry.innerRadius*2f to geometry.innerRadius*2f },
        onRetainedStickChanged={ retainedAim=it!=null;onFeedbackChanged() },
        scopeProvider={ root.child("aim") })
    private var moveOwned=false
    private val payloads=mutableMapOf<String,String>()
    var onOutputReplaced:(String)->Unit={}
    var onMovementReplaced:()->Unit={}
    var onFeedbackChanged:()->Unit={}
    var pendingPayloads:(String)->Set<String> = { emptySet() }
    private val feedbackJob=(context as? LifecycleOwner)?.lifecycleScope?.launch {
        UdpClient.outputConflicts.collectLatest { onFeedbackChanged() }
    }
    private fun point(x:Float,y:Float)=
        (x-geometry.center.x+geometry.innerRadius) to (y-geometry.center.y+geometry.innerRadius)
    private val fireKeys=config.knobs.flatMap {
        PhoneOutputCoordinator.payloadLogicalKeys(it.fire.firePayload,root.player).orEmpty()
    }.toSet()
    private val payloadController=FloatingRingPayloadController(root,fireKeys,
        HandlerButtonAimPayloadScheduler(),{ onOutputReplaced(it) },{ onFeedbackChanged() })
    override fun acquirePayload(slot:String,payload:String):Boolean {
        payloads[slot]=payload
        return payloadController.acquire(slot,payload)
    }
    override fun reservePayload(slot:String,payload:String,retained:Boolean,exclusive:Boolean):Boolean {
        payloads[slot]=payload
        return payloadController.reserve(slot,payload,retained,exclusive)
    }
    override fun releasePayload(slot:String)=payloadController.release(slot)
    override fun endPayload(slot:String) { payloadController.end(slot);payloads.remove(slot) }
    override fun endPayloadNormally(slot:String)=payloadController.endNormally(slot)
    override fun payloadMasked(slot:String):Boolean =
        (pendingPayloads(slot)+payloads[slot].orEmpty()).any { payload ->
            PhoneOutputCoordinator.payloadLogicalKeys(payload,root.player).orEmpty().any(UdpClient::outputIsMasked)
        }
    override fun aimIsRetained():Boolean=retainedAim
    override fun retainPayload(slot:String,retained:Boolean)=root.retainPayload(slot,retained)
    override fun payloadIsOwned(slot:String)=root.payloadIsOwned(slot)
    override fun payloadsOverlap(first:String,second:String):Boolean {
        fun keys(value:String)=PhoneOutputCoordinator.payloadLogicalKeys(value,root.player).orEmpty()
        return keys(first).intersect(keys(second)).isNotEmpty()
    }
    override fun beginAim(pointerId:Int,x:Float,y:Float,eventTime:Long,tuning:ButtonAimTuning):Boolean {
        if(tuning.haptics) (context as? android.app.Activity)?.window?.decorView
            ?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        val p=point(x,y)
        val output=if(tuning.output==ButtonAimOutput.RIGHT_STICK) TouchAimOutput.RIGHT_STICK else TouchAimOutput.MOUSE
        return aim.begin(pointerId,p.first,p.second,eventTime,AimOutputConfig(
            output,tuning.sensitivity,tuning.invertY,tuning.stickProfile,tuning.mouseProfile,
            tuning.stickFullDisplacementPx,tuning.stickDeadzonePx,
            if(tuning.stickUsesTouchPosition) AimOriginMode.CONTROL_CENTER else AimOriginMode.INITIAL_TOUCH,
            gameMouse=tuning.output==ButtonAimOutput.GAME_MOUSE
        ),allowRetainedTakeover=true).started
    }
    override fun updateAim(pointerId:Int,x:Float,y:Float,eventTime:Long) {
        val p=point(x,y);aim.update(pointerId,p.first,p.second,eventTime)
    }
    override fun suspendAim(pointerId:Int,x:Float,y:Float,eventTime:Long) {
        val p=point(x,y);aim.suspendAtBoundary(pointerId,p.first,p.second,eventTime)
    }
    override fun resumeAim(pointerId:Int,x:Float,y:Float,eventTime:Long) {
        val p=point(x,y);aim.resumeFromBoundary(pointerId,p.first,p.second,eventTime)
    }
    override fun completeAim(autoCenter:Boolean) {
        aim.flushPendingMouse()
        aim.completeGesture(autoCenter)
    }
    override fun finishAim()=aim.finish(flushMouse=false)
    override fun updateAimSensitivity(value:Float)=aim.updateSensitivity(value,discardPendingMouse=true)
    override fun movement(x:Float,y:Float,retained:Boolean) {
        val name=if(config.movement.output==TouchAimOutput.RIGHT_STICK) "STICK_R" else "STICK_L"
        val scope=root.child("movement")
        ManualStickArbiter.registerOutputScope(moveOwner,scope)
        if(!moveOwned) {
            moveOwned=ManualStickArbiter.acquire(name,moveOwner,allowRetainedTakeover=true) {
                moveOwned=false;scope.releaseAllClaims();onMovementReplaced()
            }.acquired
        }
        if(moveOwned) {
            ManualStickArbiter.setState(name,moveOwner,if(retained) ManualStickOwnerState.RETAINED else ManualStickOwnerState.ACTIVE)
            scope.setRetained(retained)
            ManualStickArbiter.send(name,moveOwner,x,y,config.movement.excludeFromCameraFollow)
            scope.setRetained(retained)
        }
    }
    override fun releaseMovement() {
        val name=if(config.movement.output==TouchAimOutput.RIGHT_STICK) "STICK_R" else "STICK_L"
        if(moveOwned) ManualStickArbiter.release(name,moveOwner,center=true)
        moveOwned=false
        root.child("movement").releaseAllClaims()
        ManualStickArbiter.registerOutputScope(moveOwner,null)
    }
    override fun mouseSuppression(active:Boolean)=UdpClient.setMouseCameraSuppression(suppressionOwner,active)
    override fun closeScope() {
        payloadController.close()
        feedbackJob?.cancel()
        root.finish { confirmed->
            if(!confirmed) Handler(Looper.getMainLooper()).post {
                Toast.makeText(context,"Floating output cleanup is unconfirmed by the receiver.",Toast.LENGTH_LONG).show()
            }
        }
    }
}
