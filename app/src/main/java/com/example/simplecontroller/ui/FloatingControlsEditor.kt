package com.example.simplecontroller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.children
import androidx.core.widget.doAfterTextChanged
import com.example.simplecontroller.MainActivity
import com.example.simplecontroller.model.*
import com.example.simplecontroller.io.FloatingBoostProfileStore
import com.example.simplecontroller.io.FloatingRingPresetStore
import com.example.simplecontroller.io.detachedControllerPageCopy
import com.example.simplecontroller.io.floatingLocalActionError
import java.util.UUID
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One-finger editor; all nested changes are drafts until the zone's Save action. */
class FloatingControlsEditor(
    private val context:Context, private val canvas:FrameLayout,
    private val controls:()->MutableList<Control>, private val page:()->ControllerPage,
    private val orientation:()->LayoutOrientation, private val replaceZones:(List<FloatingZone>)->Unit,
    private val syncPage:()->Unit,
    private val saveProfileCopy:()->Unit
) {
    private var selection: FloatingSelectionSession<ControlGeometry>? = null
    private var selectionBack: OnBackPressedCallback? = null
    private var selectionGeneration = 0L
    private fun id()=UUID.randomUUID().toString()
    private fun message(text:String)=Toast.makeText(context,text,Toast.LENGTH_LONG).show()
    private fun geometry(c:Control)=ControlGeometry(c.x,c.y,c.w,c.h)
    fun showRingMenu() {
        AlertDialog.Builder(context).setTitle("Floating ring")
            .setItems(arrayOf("Saved Presets","New")) { _,index->
                if(index==0) ringPresets() else addRing()
            }.setNegativeButton("Cancel",null).show()
    }
    fun showManager() {
        val zones=page().floatingZones
        val labels=listOf("Add floating ring","Create floating Button / group from rectangle","Saved full ring presets")+
            zones.map { "Edit "+it.name.ifBlank { it.id } }
        AlertDialog.Builder(context).setTitle("Floating controls")
            .setItems(labels.toTypedArray()) { _,i -> when(i) {
                0->addRing();1->selectButtons();2->ringPresets();else->edit(zones[i-3])
            } }
            .setNegativeButton("Cancel",null).show()
    }
    fun addRing() {
        val d=context.resources.displayMetrics.density
        val rect=ControlGeometry(canvas.paddingLeft+24f*d,canvas.paddingTop+150f*d,
            (canvas.width-48f*d).coerceAtLeast(100f*d),(canvas.height-230f*d).coerceAtLeast(100f*d))
        edit(FloatingZone(id(),name="Floating ring",kind=FloatingZoneKind.RING,ring=FloatingRingConfig())
            .withGeometry(orientation(),FloatingZoneGeometry(canvas.width.toFloat(),canvas.height.toFloat(),rect)))
    }
    fun selectButtons() {
        if(!GlobalSettings.editMode) return
        cancelSelection()
        val generation=selectionGeneration
        val sourcePage=page().id
        val sourceOrientation=orientation()
        val sourceCanvas=listOf(canvas.width,canvas.height,canvas.paddingLeft,canvas.paddingTop,
            canvas.paddingRight,canvas.paddingBottom)
        val activity=context as? MainActivity
        lateinit var overlay:RectangleSelection
        lateinit var completion:FloatingSelectionSession<ControlGeometry>
        val back=object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                completion.cancel()
                remove()
            }
        }
        completion=FloatingSelectionSession(
            post={ task->canvas.post { task() } },
            removeOverlay={
                if(overlay.parent===canvas) canvas.removeView(overlay)
                back.remove()
                if(selection===completion) { selection=null;selectionBack=null }
            },
            isCurrent={
                generation==selectionGeneration && GlobalSettings.editMode && canvas.isAttachedToWindow &&
                    activity?.isFinishing!=true && activity?.isDestroyed!=true &&
                    page().id==sourcePage && orientation()==sourceOrientation &&
                    sourceCanvas==listOf(canvas.width,canvas.height,canvas.paddingLeft,canvas.paddingTop,
                        canvas.paddingRight,canvas.paddingBottom)
            },
            preview={ rect->previewMembers(rect.copy(x=rect.x+overlay.x,y=rect.y+overlay.y)) }
        )
        overlay=RectangleSelection(context,completion)
        selection=completion
        selectionBack=back
        activity?.onBackPressedDispatcher?.addCallback(activity,back)
        canvas.addView(overlay,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT))
    }
    fun cancelSelection() {
        selectionGeneration++
        selectionBack?.remove()
        selectionBack=null
        selection?.cancel()
        selection=null
    }
    private fun previewMembers(rect:ControlGeometry) {
        val used=page().floatingZones.flatMap { it.memberIds }.toSet()
        val eligible=controls().filter { it.type==ControlType.BUTTON && it.id !in used }
        if(eligible.isEmpty()) { message("No ungrouped Buttons are available.");return }
        val flags=eligible.map { c ->
            val view=canvas.children.filterIsInstance<ControlView>().firstOrNull { it.model.id==c.id }
            rect.fullyContains(view?.let { ControlGeometry(it.x,it.y,it.width.toFloat(),it.height.toFloat()) } ?: geometry(c))
        }.toBooleanArray()
        val dialog=AlertDialog.Builder(context).setTitle("Preview floating group members")
            .setMultiChoiceItems(eligible.map { it.name.ifBlank { it.payload.ifBlank { it.id } } }.toTypedArray(),flags) { _,i,on->flags[i]=on }
            .setPositiveButton("Configure",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected=eligible.filterIndexed { i,_->flags[i] }
                if(selected.isEmpty()) { message("Select at least one Button.");return@setOnClickListener }
                val current=selected.associate { it.id to geometry(it) }
                val storedOther=page().geometryFor(orientation().opposite())?.controls.orEmpty()
                val other=selected.associate { c->c.id to (storedOther[c.id] ?: geometry(c)) }
                val zone=FloatingZone(id(),name=if(selected.size==1) "Floating Button" else "Floating group",
                    kind=if(selected.size==1) FloatingZoneKind.SINGLE_BUTTON else FloatingZoneKind.BUTTON_GROUP,
                    memberIds=selected.map { it.id },startMemberId=selected.first().id,
                    portraitMemberGeometry=if(orientation()==LayoutOrientation.PORTRAIT) current else other,
                    landscapeMemberGeometry=if(orientation()==LayoutOrientation.LANDSCAPE) current else other,
                    portraitRestorationGeometry=if(orientation()==LayoutOrientation.PORTRAIT) current else other,
                    landscapeRestorationGeometry=if(orientation()==LayoutOrientation.LANDSCAPE) current else other)
                    .withGeometry(orientation(),FloatingZoneGeometry(canvas.width.toFloat(),canvas.height.toFloat(),rect))
                dialog.dismiss();edit(zone,newGroup=true)
            }
        };dialog.show()
    }
    fun edit(zone:FloatingZone,newGroup:Boolean=false) {
        syncPage()
        val source=page().floatingZones.firstOrNull { it.id==zone.id } ?: zone
        var draft=source
        val f=Form(context)
        val name=f.text("Name",source.name)
        val enabled=f.flag("Activation zone enabled",source.enabled)
        val current=source.geometryFor(orientation()) ?: source.geometryFor(orientation().opposite())!!
        val rectangle=current.zoneOnCanvas(canvas.width.toFloat(),canvas.height.toFloat())
        f.note("Drag the outline in Edit to move the zone. Tap or hold it for these settings. Covered zones can be selected from the Floating controls menu.")
        val x=f.dimension("Zone left (px)",rectangle.x,canvas.width,position=true)
        val y=f.dimension("Zone top (px)",rectangle.y,canvas.height,position=true)
        val w=f.dimension("Zone width (px)",rectangle.w,canvas.width)
        val h=f.dimension("Zone height (px)",rectangle.h,canvas.height)
        var start:()->String={ source.startMemberId }
        var swipe:()->Boolean={source.groupSwipe}
        fun currentDraft()=draft.copy(name=name(),enabled=enabled(),startMemberId=start(),groupSwipe=swipe())
            .withGeometry(orientation(),FloatingZoneGeometry(canvas.width.toFloat(),canvas.height.toFloat(),ControlGeometry(x(),y(),w(),h())))
        if(source.kind!=FloatingZoneKind.RING) {
            val ids=source.memberIds
            start=f.choice("Initial Button (Reveal only produces no input)",
                if(source.kind==FloatingZoneKind.BUTTON_GROUP) listOf("")+ids else ids,source.startMemberId) { key->
                if(key.isBlank()) "Reveal only" else controls().first { it.id==key }.name.ifBlank { key }
            }
            if(source.kind==FloatingZoneKind.BUTTON_GROUP)
                swipe=f.flag("Swipe between compatible ordinary members",source.groupSwipe)
            f.note("Member properties remain in their existing Button options. Zone resizing does not resize members.")
        } else {
            f.button("Ring geometry, layers and starting role") { editRing(draft.ring!!) { draft=draft.copy(ring=it) } }
            f.button("Movement settings") { editMovement(draft.ring!!) { draft=draft.copy(ring=it) } }
            f.button("Aiming and fine sensitivity") { editAim(draft.ring!!) { draft=draft.copy(ring=it) } }
            f.button("Aim knobs and firing") { editKnobs(draft.ring!!) { draft=draft.copy(ring=it) } }
            f.button("Boost 1") { editBoost(draft.ring!!,RingBoostStage.ONE) { draft=draft.copy(ring=it) } }
            f.button("Boost 2") { editBoost(draft.ring!!,RingBoostStage.TWO) { draft=draft.copy(ring=it) } }
            f.button("Preview boost reachability") {
                val preview=FloatingRingEditorPreview(context,draft.ring!!,
                    (canvas.width-canvas.paddingLeft-canvas.paddingRight).toFloat(),
                    (canvas.height-canvas.paddingTop-canvas.paddingBottom).toFloat())
                AlertDialog.Builder(context).setTitle("Ring reachability preview").setView(preview)
                    .setPositiveButton("Done",null).show()
            }
            f.button("Save / apply named boost profiles") { boostProfiles(draft.ring!!) { draft=draft.copy(ring=it) } }
            f.button("Save full ring / activation zone preset") {
                try { saveRingPreset(currentDraft()) }
                catch(error:IllegalArgumentException) { message(error.message ?: "Invalid zone setting.") }
            }
            f.note("A full ring preset includes both saved activation rectangles and all ring settings. Add it to another profile from Floating controls -> Saved full ring presets.")
            f.note("Boost boundaries are invisible in Play. Edit preview marks sectors beyond the canvas as unreachable. Disabled layers keep their settings.")
        }
        val existing=page().floatingZones.any { it.id==source.id }
        if(existing) {
            f.button("Duplicate floating zone and its members") { duplicate(source) }
            f.button("Move zone above overlapping zones") { replaceZones(page().floatingZones.filter { it.id!=source.id }+source) }
            f.button(if(source.kind==FloatingZoneKind.RING) "Remove zone" else "Ungroup and restore original geometry") {
                AlertDialog.Builder(context).setTitle("Remove floating zone?")
                    .setMessage("Restore original positions in both orientations and keep the Buttons' latest behavior settings.")
                    .setPositiveButton("Remove") { _,_->ungroup(source) }.setNegativeButton("Cancel",null).show()
            }
        }
        dialog("Floating zone",f) {
            val updated=currentDraft()
            val zones=if(existing) page().floatingZones.map { if(it.id==source.id) updated else it }
                else page().floatingZones+updated
            page().copy(controls=controls(),floatingZones=zones).floatingValidationError()?.let { return@dialog it }
            if(newGroup) (context as? MainActivity)?.preserveSavedProfileDuringFloatingConversion()
            replaceZones(zones)
            if(newGroup) AlertDialog.Builder(context).setTitle("Keep the existing saved profile?")
                .setMessage("The group is in the working layout. Save a new profile copy to retain the existing saved profile.")
                .setPositiveButton("Save a copy") { _,_->saveProfileCopy() }.setNegativeButton("Keep editing",null).show()
            null
        }
    }
    private fun saveRingPreset(zone:FloatingZone) {
        val f=Form(context)
        val name=f.text("Preset name",zone.name)
        f.note("Saves this ring's movement, aiming, firing, boosts, starting settings, enabled switches and both saved activation rectangles.")
        dialog("Save full ring preset",f) {
            val preset=zone.captureRingPreset(id(),name(),System.currentTimeMillis())
                ?: return@dialog "The ring or zone settings are invalid."
            val result=FloatingRingPresetStore.save(context,preset)
            if(result.succeeded) null else result.error ?: "Could not save the ring preset."
        }
    }
    private fun ringPresets() {
        val presets=FloatingRingPresetStore.list(context)
        if(presets.isEmpty()) {
            message("No full ring presets are saved. Open a floating ring's settings to save one.")
            return
        }
        AlertDialog.Builder(context).setTitle("Saved full ring presets")
            .setItems(presets.map { it.name }.toTypedArray()) { _,index->
                val preset=presets[index]
                AlertDialog.Builder(context).setTitle(preset.name)
                    .setItems(arrayOf("Add a new ring to this profile","Rename preset","Delete preset")) { _,action->
                        when(action) {
                            0->preset.instantiate()?.let { edit(it) }
                                ?: message("This ring preset is invalid; the profile was left unchanged.")
                            1->{ val f=Form(context);val name=f.text("Preset name",preset.name)
                                dialog("Rename full ring preset",f) {
                                    val result=FloatingRingPresetStore.rename(context,preset,name(),System.currentTimeMillis())
                                    if(result.succeeded) null else result.error ?: "Could not rename the preset."
                                }
                            }
                            2->AlertDialog.Builder(context).setTitle("Delete this full ring preset?")
                                .setMessage("Rings already added to controller profiles keep their own settings.")
                                .setPositiveButton("Delete") { _,_->
                                    if(!FloatingRingPresetStore.delete(context,preset.id)) message("Could not delete the preset.")
                                }.setNegativeButton("Cancel",null).show()
                        }
                    }.setNegativeButton("Cancel",null).show()
            }.setNegativeButton("Cancel",null).show()
    }
    private fun ungroup(zone:FloatingZone) {
        val saved=zone.restorationGeometryFor(orientation())
        controls().forEach { c->saved[c.id]?.let { c.x=it.x;c.y=it.y;c.w=it.w;c.h=it.h } }
        (context as? MainActivity)?.restoreFloatingGeometry(zone)
        replaceZones(page().floatingZones.filter { it.id!=zone.id })
    }
    private fun duplicate(zone:FloatingZone) {
        val selected=controls().filter { it.id in zone.memberIds }
        val template=page().copy(controls=selected,floatingZones=listOf(zone))
        val copied=detachedControllerPageCopy(template,page().id,page().name,redirectSelfTargets=false)
        copied.controls.forEach { it.x+=40f;it.y+=40f }
        controls().addAll(copied.controls)
        fun shifted(values:Map<String,ControlGeometry>)=values.mapValues { (_,g)->g.copy(x=g.x+40f,y=g.y+40f) }
        val z=copied.floatingZones.single().let { value->value.copy(
            name=value.name+" copy",
            portraitGeometry=value.portraitGeometry?.let { it.copy(zone=it.zone.copy(x=it.zone.x+40f,y=it.zone.y+40f)) },
            landscapeGeometry=value.landscapeGeometry?.let { it.copy(zone=it.zone.copy(x=it.zone.x+40f,y=it.zone.y+40f)) },
            portraitMemberGeometry=shifted(value.portraitMemberGeometry),landscapeMemberGeometry=shifted(value.landscapeMemberGeometry),
            portraitRestorationGeometry=shifted(value.portraitRestorationGeometry),landscapeRestorationGeometry=shifted(value.landscapeRestorationGeometry))}
        replaceZones(page().floatingZones+z)
    }
    private fun editRing(c:FloatingRingConfig,save:(FloatingRingConfig)->Unit) {
        val f=Form(context)
        val inner=f.number("Inner aim radius (dp)",c.innerRadiusDp)
        val outer=f.number("Movement outer radius (dp)",c.movementRadiusDp)
        val role=f.choice("Summon starting role",FloatingStartRole.entries.toList(),c.startRole)
        val start=f.choice("Starting aim knob",c.knobs.map { it.id },c.startKnobId)
        val direct=f.flag("Start aiming when a direct boost enters inner area",c.directBoostToAim)
        val dest=f.choice("Direct boost aim destination",c.knobs.map { it.id },c.directBoostAimKnobId)
        val lower=f.flag("Activate Boost 1 when returning from direct Boost 2",c.directBoostTwoToOne)
        f.note("On inward return past the Boost 2 direct area's inner edge, enabled Boost 1 starts using its normal action and threshold settings. Boost 1 direct tap need not be enabled.")
        dialog("Ring geometry",f) {
            val v=c.copy(innerRadiusDp=inner(),movementRadiusDp=outer(),startRole=role(),startKnobId=start(),
                directBoostToAim=direct(),directBoostAimKnobId=dest(),directBoostTwoToOne=lower())
            // Disabled starting selections are validated on the final zone Save.
            save(v);null
        }
    }
    private fun editMovement(c:FloatingRingConfig,save:(FloatingRingConfig)->Unit) {
        val f=Form(context);val m=c.movement
        val enabled=f.flag("Movement enabled",m.enabled)
        val output=f.choice("Movement stick",listOf(TouchAimOutput.LEFT_STICK,TouchAimOutput.RIGHT_STICK),m.output)
        val sensitivity=f.number("Movement sensitivity",m.sensitivity)
        val curve=f.choice("Movement response",ButtonAimStickProfile.entries.toList(),m.profile)
        val center=f.flag("Auto-center on lift",m.autoCenter)
        val exclude=f.flag("Exclude movement from Camera Follow",m.excludeFromCameraFollow)
        val ix=f.number("Initial X (-1 to 1)",m.initialX);val iy=f.number("Initial Y (-1 to 1)",m.initialY)
        val boost=f.flag("Movement participates in boosts",m.participateInBoosts)
        f.note("Center is neutral. With Linear sensitivity 1, strength is distance / movement radius. Boost distance uses raw motion.")
        dialog("Movement",f) { save(c.copy(movement=m.copy(enabled=enabled(),output=output(),sensitivity=sensitivity(),
            profile=curve(),autoCenter=center(),excludeFromCameraFollow=exclude(),initialX=ix(),initialY=iy(),participateInBoosts=boost())));null }
    }
    private fun editAim(c:FloatingRingConfig,save:(FloatingRingConfig)->Unit) {
        val f=Form(context);val a=c.aim;val t=a.tuning
        val enabled=f.flag("Inner aiming enabled",a.enabled)
        val output=f.choice("Aim output",FloatingRingConfig.RING_AIM_OUTPUTS.toList(),t.output)
        val sensitivity=f.number("Normal aim sensitivity",t.sensitivity)
        val invert=f.flag("Invert vertical aim",t.invertY)
        val profile=f.choice("Mouse profile",ButtonAimMouseProfile.entries.toList(),t.mouseProfile)
        val curve=f.choice("Stick response",ButtonAimStickProfile.entries.toList(),t.stickProfile)
        val full=f.number("Stick full displacement (px)",t.stickFullDisplacementPx)
        val dead=f.number("Stick deadzone (px)",t.stickDeadzonePx)
        val position=f.flag("Stick uses control-center position",t.stickUsesTouchPosition)
        val center=f.flag("Aim auto-center on lift",t.autoCenter)
        val haptics=f.flag("Aim haptics",t.haptics)
        val outside=f.flag("Continue aiming outside inner boundary",a.continueOutside)
        val boost=f.flag("Aiming participates in boosts",a.participateInBoosts)
        val suppression=f.flag("Suppress Camera Follow while this mouse aim owns a claim",a.suppressCameraFollow)
        val fine=f.flag("Fine aim on return from selected boosts",a.fine.enabled)
        val fineSensitivity=f.number("Fine aim sensitivity",a.fine.sensitivity)
        val fine1=f.flag("Fine aim qualifies from Boost 1",RingBoostStage.ONE in a.fine.qualifyingStages)
        val fine2=f.flag("Fine aim qualifies from Boost 2",RingBoostStage.TWO in a.fine.qualifyingStages)
        val persist=f.flag("Keep fine sensitivity while qualifying ADS is latched",a.fine.persistWhileAdsLatched)
        f.note("Aiming continues during delays. Outside aim, timer cancellation, ADS/fire continuation and lift retention are independent.")
        dialog("Ring aiming",f) {
            save(c.copy(aim=a.copy(enabled=enabled(),tuning=t.copy(output=output(),sensitivity=sensitivity(),invertY=invert(),
                mouseProfile=profile(),stickProfile=curve(),stickFullDisplacementPx=full(),stickDeadzonePx=dead(),
                stickUsesTouchPosition=position(),autoCenter=center(),haptics=haptics()),continueOutside=outside(),
                participateInBoosts=boost(),suppressCameraFollow=suppression(),fine=a.fine.copy(enabled=fine(),
                    sensitivity=fineSensitivity(),qualifyingStages=stages(fine1(),fine2()),persistWhileAdsLatched=persist()))))
            null
        }
    }
    private fun stages(one:Boolean,two:Boolean)=buildSet { if(one)add(RingBoostStage.ONE);if(two)add(RingBoostStage.TWO) }
    private fun editKnobs(c:FloatingRingConfig,save:(FloatingRingConfig)->Unit) {
        val labels=c.knobs.map { it.name.ifBlank { it.id } }+if(c.knobs.size<3) listOf("Add aim knob") else emptyList()
        AlertDialog.Builder(context).setTitle("Aim knobs").setItems(labels.toTypedArray()) { _,i->
            if(i==c.knobs.size) {
                val index=c.knobs.size
                val knob=RingAimKnob(id(),name="Aim "+(index+1),x=if(index==1) -0.75f else 0.75f,targetDiameterDp=24f)
                editKnob(c.copy(knobs=c.knobs+knob),index,save)
            } else editKnob(c,i,save)
        }.setNegativeButton("Cancel",null).show()
    }
    private fun editKnob(c:FloatingRingConfig,index:Int,save:(FloatingRingConfig)->Unit) {
        val k=c.knobs[index];val s=k.fire;val f=Form(context)
        val name=f.text("Knob name",k.name);val enabled=f.flag("Knob enabled",k.enabled)
        val x=f.number("Knob X (inner-radius units)",k.x);val y=f.number("Knob Y (inner-radius units)",k.y)
        val size=f.number("Knob target diameter (dp)",k.targetDiameterDp)
        val mode=f.choice("Fire mode",RingFireMode.entries.toList(),s.mode)
        val ads=f.text("ADS payload (blank for none)",s.adsPayload)
        val fire=f.text("Fire payload (blank for none)",s.firePayload)
        val press=f.millis("Fire press duration (ms)",s.pulsePressMs);val gap=f.millis("Fire release gap (ms)",s.pulseGapMs)
        val retainAds=f.flag("Retain ADS on lift",s.retainAdsOnLift);val retainFire=f.flag("Retain fire / repeat on lift",s.retainFireOnLift)
        val adsOut=f.flag("Continue ADS outside aim area",s.continueAdsOutside);val fireOut=f.flag("Continue fire outside aim area",s.continueFireOutside)
        val cancelShot=f.flag("Cancel Release-shot on aim boundary exit",s.cancelReleaseShotOnBoundaryExit)
        val rearm=f.flag("Re-arm Release-shot on return",s.rearmReleaseShotOnBoundaryReturn)
        val ret=f.flag("Start fire on qualified return from boost",s.returnFire.enabled)
        val stage1=f.flag("Return fire qualifies from Boost 1",RingBoostStage.ONE in s.returnFire.qualifyingStages)
        val stage2=f.flag("Return fire qualifies from Boost 2",RingBoostStage.TWO in s.returnFire.qualifyingStages)
        val delay=f.millis("Return fire delay (ms)",s.returnFire.delayMs)
        val exit=f.flag("Cancel pending firing delay when leaving inner area",s.returnFire.cancelOnExit)
        f.note("None with blank ADS gives pure aim. Early lift cancels unexpired delay; delayed Release-shot arms, then waits for valid lift.")
        dialog("Aim knob",f) {
            val result=k.copy(name=name(),enabled=enabled(),x=x(),y=y(),targetDiameterDp=size(),fire=s.copy(mode=mode(),
                adsPayload=ads(),firePayload=fire(),pulsePressMs=press(),pulseGapMs=gap(),retainAdsOnLift=retainAds(),
                retainFireOnLift=retainFire(),continueAdsOutside=adsOut(),continueFireOutside=fireOut(),
                cancelReleaseShotOnBoundaryExit=cancelShot(),rearmReleaseShotOnBoundaryReturn=rearm(),
                returnFire=s.returnFire.copy(enabled=ret(),qualifyingStages=stages(stage1(),stage2()),delayMs=delay(),cancelOnExit=exit())))
            save(c.copy(knobs=c.knobs.mapIndexed { i,value->if(i==index) result else value }));null
        }
    }
    private fun editBoost(c:FloatingRingConfig,stage:RingBoostStage,save:(FloatingRingConfig)->Unit) {
        val b=c.boost(stage);val f=Form(context)
        val enabled=f.flag("Boost enabled",b.enabled);val distance=f.number("Circular threshold distance (dp)",b.distanceDp)
        val payload=f.text("Fallback payload (blank for none)",b.payload)
        val up=f.text("Up payload",b.directionalPayloads.up);val down=f.text("Down payload",b.directionalPayloads.down)
        val left=f.text("Left payload",b.directionalPayloads.left);val right=f.text("Right payload",b.directionalPayloads.right)
        val action=f.choice("Boost action",RingAction.entries.toList(),b.action)
        val press=f.millis("Press duration (ms)",b.pulsePressMs);val gap=f.millis("Release gap (ms)",b.pulseGapMs)
        val latch=f.flag("Hold: latch after threshold exit",b.latchOnExit)
        val repeat=f.flag("Repeat: continue after threshold exit",b.repeatAfterExit)
        val retain=f.flag("Retain activated output on lift",b.retainOnLift)
        val hyst=f.number("Threshold hysteresis (dp)",b.hysteresisDp)
        val direct=f.flag("Direct taps enabled",b.directTapEnabled)
        val inner=f.number("Direct area inner distance (dp)",b.directInnerDistanceDp)
        val outer=f.number("Direct area outer distance (dp)",b.directOuterDistanceDp)
        val delay=if(stage==RingBoostStage.TWO) f.millis("Boost 2 activation delay (ms)",b.activationDelayMs) else { {0L} }
        val cancel=f.flag("Cancel pending Boost 2 delay on threshold exit",b.cancelDelayOnExit)
        f.note("Drag boosts are cumulative. Direct Boost 2 starts only Boost 2. Hold latch, Repeat continuation and lift retention are separate.")
        dialog("Boost "+if(stage==RingBoostStage.ONE) "1" else "2",f) {
            val v=b.copy(enabled=enabled(),distanceDp=distance(),payload=payload(),
                directionalPayloads=DirectionalCommands(up=up(),down=down(),left=left(),right=right()),action=action(),
                pulsePressMs=press(),pulseGapMs=gap(),latchOnExit=latch(),repeatAfterExit=repeat(),retainOnLift=retain(),
                hysteresisDp=hyst(),directTapEnabled=direct(),directInnerDistanceDp=inner(),directOuterDistanceDp=outer(),
                activationDelayMs=delay(),cancelDelayOnExit=cancel())
            v.validationError()?.let { return@dialog it }
            save(if(stage==RingBoostStage.ONE)c.copy(boost1=v) else c.copy(boost2=v));null
        }
    }
    private fun boostProfiles(c:FloatingRingConfig,save:(FloatingRingConfig)->Unit) {
        val profiles=FloatingBoostProfileStore.list(context)
        val labels=listOf("Save as new")+profiles.map { it.name }
        AlertDialog.Builder(context).setTitle("Named boost profiles").setItems(labels.toTypedArray()) { _,i->
            if(i==0) {
                val f=Form(context);val name=f.text("Profile name","");val full=f.flag("Include optional ring aiming / firing preset",false)
                dialog("Save boost profile",f) {
                    val p=c.captureBoostProfile(id(),name(),System.currentTimeMillis(),full()) ?: return@dialog "The ring settings are invalid."
                    if(!FloatingBoostProfileStore.save(context,p).succeeded) "Could not save the boost profile." else null
                }
            } else {
                val p=profiles[i-1]
                AlertDialog.Builder(context).setTitle(p.name)
                    .setItems(arrayOf("Apply boost settings only","Apply saved ring aiming / actions","Rename","Delete")) { _,action->
                        when(action) {
                            0->p.applyBoostSettingsOnlyTo(c)?.let(save) ?: message("The boost geometry is incompatible; nothing changed.")
                            1->p.applyRingAimActionsTo(c)?.let(save) ?: message("The preset is missing or its knob slots are incompatible; nothing changed.")
                            2->{ val f=Form(context);val name=f.text("New name",p.name);dialog("Rename boost profile",f) {
                                if(FloatingBoostProfileStore.rename(context,p,name(),System.currentTimeMillis()).succeeded) null else "Could not rename."
                            } }
                            3->AlertDialog.Builder(context).setTitle("Delete reusable boost profile?")
                                .setMessage("Controls keep their applied snapshot.")
                                .setPositiveButton("Delete") { _,_->FloatingBoostProfileStore.delete(context,p.id) }.setNegativeButton("Cancel",null).show()
                        }
                    }.setNegativeButton("Cancel",null).show()
            }
        }.setNegativeButton("Cancel",null).show()
    }
    fun editControlOptions(c:Control) {
        val f=Form(context)
        val suppress=f.flag("Suppress Camera Follow when mouse output engages",c.mouseSuppressCameraFollow)
        val action=if(c.type==ControlType.BUTTON) f.choice("Scoped action",FloatingLocalAction.entries.toList(),c.floatingLocalAction)
            else { { c.floatingLocalAction } }
        val targets=(context as? MainActivity)?.floatingActionTargets().orEmpty()
        val target=if(c.type==ControlType.BUTTON) f.choice("Action target",listOf("")+targets.map { it.first },c.floatingActionTargetId) { key->
            targets.firstOrNull { it.first==key }?.second ?: "Choose target"
        } else { { c.floatingActionTargetId } }
        f.note("Suppression persists until its scoped end action or hard cleanup. This option is off for old profiles.")
        dialog("Floating / Camera Follow options",f) {
            if(action()!=FloatingLocalAction.NONE && target().isBlank()) return@dialog "Choose a scoped action target."
            val candidate=c.copy(mouseSuppressCameraFollow=suppress(),floatingLocalAction=action(),floatingActionTargetId=target())
            floatingLocalActionError(candidate)?.let { return@dialog it+" Keep existing features or use a separate ordinary Button." }
            c.mouseSuppressCameraFollow=suppress();c.floatingLocalAction=action();c.floatingActionTargetId=target();null
        }
    }
}

private class Form(val context:Context) {
    val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(24,8,24,16) }
    fun note(label:String) { body.addView(TextView(context).apply { text=label;setPadding(0,16,0,8) }) }
    fun text(label:String,value:String):()->String {
        note(label);val edit=EditText(context).apply { setText(value);contentDescription=label;setSingleLine(true) };body.addView(edit)
        return { edit.text.toString() }
    }
    fun number(label:String,value:Float):()->Float {
        val getter=text(label,value.toString())
        return { getter().toFloatOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException(label+" needs a finite number.") }
    }
    /** Sliders change exact fields only after deliberate user input; opening never rounds a value. */
    fun dimension(label:String,value:Float,canvasExtent:Int,position:Boolean=false):()->Float {
        note(label)
        val edit=EditText(context).apply {
            inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(value.toString());contentDescription=label;setSingleLine(true)
        }
        body.addView(edit)
        val limit=maxOf(canvasExtent,ceil(abs(value).toDouble()).toInt(),40).coerceAtMost(Int.MAX_VALUE/4)
        val minimum=if(position) -limit else 1
        val maximum=if(position) limit*2 else limit
        val seek=SeekBar(context).apply {
            max=maximum-minimum
            progress=(value.roundToInt().coerceIn(minimum,maximum)-minimum)
            minimumHeight=(48*resources.displayMetrics.density).toInt()
            contentDescription=label+" slider"
        }
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar:SeekBar?,progress:Int,fromUser:Boolean) {
                if(fromUser) edit.setText((progress+minimum).toString())
            }
            override fun onStartTrackingTouch(bar:SeekBar?) {}
            override fun onStopTrackingTouch(bar:SeekBar?) {}
        })
        edit.doAfterTextChanged {
            it.toString().toFloatOrNull()?.takeIf(Float::isFinite)?.let { number ->
                seek.progress=number.roundToInt().coerceIn(minimum,maximum)-minimum
            }
        }
        body.addView(seek)
        return { edit.text.toString().toFloatOrNull()?.takeIf(Float::isFinite)
            ?: throw IllegalArgumentException(label+" needs a finite number.") }
    }
    fun millis(label:String,value:Long):()->Long {
        val getter=text(label,value.toString())
        return { getter().toLongOrNull() ?: throw IllegalArgumentException(label+" needs a whole number.") }
    }
    fun flag(label:String,value:Boolean):()->Boolean {
        val check=CheckBox(context).apply { text=label;isChecked=value;minHeight=(48*resources.displayMetrics.density).toInt() }
        body.addView(check);return { check.isChecked }
    }
    fun <T> choice(label:String,values:List<T>,selected:T,display:(T)->String={it.toString()}):()->T {
        note(label)
        val spinner=Spinner(context).apply { adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,values.map(display))
            setSelection(values.indexOf(selected).coerceAtLeast(0));minimumHeight=(48*resources.displayMetrics.density).toInt() }
        body.addView(spinner);return { values[spinner.selectedItemPosition] }
    }
    fun button(label:String,click:()->Unit) { body.addView(Button(context).apply { text=label;setOnClickListener { click() } }) }
}
private fun dialog(title:String,f:Form,save:()->String?) {
    val scroll=ScrollView(f.context).apply { addView(f.body) }
    val d=AlertDialog.Builder(f.context).setTitle(title).setView(scroll)
        .setPositiveButton("Save",null).setNegativeButton("Cancel",null).create()
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val error=try { save() } catch(e:IllegalArgumentException) { e.message ?: "Invalid setting." }
            if(error==null) d.dismiss() else Toast.makeText(f.context,error,Toast.LENGTH_LONG).show()
        }
    };d.show()
}
private class RectangleSelection(context:Context,private val completion:FloatingSelectionSession<ControlGeometry>):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private var x0=0f;private var y0=0f;private var x1=0f;private var y1=0f
    init { isClickable=true;contentDescription="Draw a rectangle around Buttons. Release to preview members." }
    override fun onDraw(canvas:Canvas) {
        canvas.drawColor(Color.argb(80,0,0,0));paint.color=Color.CYAN;paint.style=Paint.Style.STROKE;paint.strokeWidth=3f
        canvas.drawRect(min(x0,x1),min(y0,y1),maxOf(x0,x1),maxOf(y0,y1),paint)
        paint.style=Paint.Style.FILL;paint.textSize=18f*resources.displayMetrics.scaledDensity
        canvas.drawText("Draw around Buttons; lift to preview",16f,40f*resources.displayMetrics.density,paint)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(completion.completed) return true
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN->{x0=e.x;y0=e.y;x1=x0;y1=y0}
            MotionEvent.ACTION_MOVE->{x1=e.x;y1=e.y}
            MotionEvent.ACTION_UP->{x1=e.x;y1=e.y;completion.complete(ControlGeometry(min(x0,x1),min(y0,y1),abs(x1-x0).coerceAtLeast(1f),abs(y1-y0).coerceAtLeast(1f)))}
            MotionEvent.ACTION_CANCEL->completion.complete(null)
        };invalidate();return true
    }
}
