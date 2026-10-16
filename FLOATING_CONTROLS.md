# Floating controls

Floating controls require a Windows receiver that acknowledges PHONE_OUTPUT_V1 and its phone-only activation barrier. The Android app keeps its existing controls available with older receivers and ConsoleBridge/Pico. New floating zones are unavailable on those transports. Receiver source is included; an installed EXE or desktop shortcut is not changed by building the app.

The heartbeat sends unprefixed PING on an I/O worker, preserving the acknowledgement channel used by floating controls. Entering Play or returning to the foreground with enabled zones prepares the capability/activation handshake. If a zone touch arrives first, the app waits up to 1500 ms while that original finger stays down. After acknowledgement it places the saved starting target at the latest finger position with a fresh gesture clock; waiting movement and elapsed time cannot become aim travel, boost visits or firing delays. Lift, cancellation, changed route/page/orientation/geometry, lost focus or cleanup discards the pending contact. Floating groups cannot fall back to unscoped legacy output. An unavailable connection can still time out and require another touch.

## Create and edit

In Edit, tap + -> Floating ring -> Saved Presets or New. The same + menu offers Floating Button / group from selection and Manage floating controls. The manager lists every zone on the current page, including covered zones, and opens its settings by name; it also offers ring creation, group selection and full-ring presets. Holding Edit remains an alternate way to open this manager. In Play, holding Edit opens the active-instance Finish list, which supplies scoped Finish when an instance's Finish overlaps other visible controls. Selecting an instance stops its owned inputs and timers and hides it without deleting the saved zone. The Play layout and + visibility are unchanged.

For a group, draw a rectangle and lift. The preview initially selects fully contained ungrouped Buttons; each checkbox adjusts membership with one tap. Configure the zone and select its initial member, or explicitly select Reveal only. A single Button starts on its sole member. Ordinary Button properties retain their existing editor, payload, Hold, Turbo, Auto-Tap, Button Aim and directional validation.

After choosing rectangle selection, the phone's Back button cancels drawing, including before the first touch, and keeps the editor open. Cancellation opens no member preview and changes no controls. Selection completes once; overlay removal waits until touch dispatch finishes, so Android's cleanup cancellation cannot remove it twice. Pause, page/orientation changes, and leaving Edit cancel unfinished or queued selection; stale canvas-size results are discarded.

Conversion changes the working layout and blocks pause autosave until an explicit Save. The prompt offers Save a copy so the previous saved profile stays available. Save under a new name to preserve it. Zone resizing changes activation geometry only. In Edit, member movement updates the group's relative arrangement while its restoration geometry remains unchanged. Ungroup restores both original orientation arrangements while retaining the latest Button settings.

Zone outlines are visible in Edit and dormant zones are invisible in Play. Edit a zone to change its rectangle, enabled state, saved starting role, ring settings, group Swipe policy, stacking order, duplication or ungrouping. A duplicate receives detached settings and remapped member/zone/knob references.

In Edit, drag a visible zone outline with one finger to reposition its activation rectangle. Lift to keep the new position; cancellation or pointer loss discards that unfinished drag. Tap or hold the outline to open its settings, which offer left/top/width/height sliders alongside exact pixel fields. Values are measured on the displayed canvas, and opening settings preserves fractional and oversized saved values. Select a covered zone through + -> Manage floating controls, then its named entry; its position and size sliders remain available even when another control covers the outline. Existing Buttons and app controls keep touch priority. Zone outlines use the same overlap order as Play.

Moving or resizing a zone changes only its activation rectangle in the current editor orientation. Member Button sizes, group spacing/restoration positions, ring dimensions, starting role, and the other saved orientation remain unchanged. Profile Save retains the edited rectangle through the existing save path.

Output cleanup cancels unfinished Edit drags while retaining the zone outlines. Leaving and returning to the app in Edit mode keeps the zones available; page and orientation rendering replace their outlines normally.

## Play

A real initial DOWN on otherwise unused zone space places and activates the selected starting target once. Reveal-only groups produce no initial output. Existing visible controls and app UI take precedence. One instance per zone stays visible after lift until its scoped Finish; many instances may coexist.

Captured movement remains movement when dragged into the inner area. A fresh intentional aim touch selects an enabled knob; aim remains aim through boosts and return. Direct boost starts are command-only unless Start aiming when a direct boost enters inner area is enabled. Direct Boost 2 activates only its own stage by default.

Ring geometry offers Activate Boost 1 when returning from direct Boost 2, off by default. With it enabled, the first inward crossing past the Boost 2 direct area's inner edge activates enabled Boost 1 without requiring Boost 1 direct tap. Boost 1 then follows its normal distance, hysteresis, action, latch, repeat and lift settings for that gesture. A fast inward sample that skips the lower band still visits it once using the incoming direction and applies its exit rules; a nonlatched Hold ends upon leaving that threshold. Lift, Cancel and Finish cannot start a new lower action. Boost 2's independent delay and cancellation settings are unchanged. This option is saved with the controller and full-ring presets; boost-only presets preserve the target ring's geometry/start settings.

Targets are translated to fit, not shrunk. Placement keeps the original first-contact gesture origin after edge correction. A configuration whose visible control cannot fit gives an explicit error. Preview boost reachability shows actual-size ring/threshold geometry, disabled reference layers and unavailable cardinal sectors at the shown placement.

Each instance has Finish, including deliberate drag into its target. Finish cancels timers/shots/repeating and owned outputs, closes its transport scope and suppression claims, hides it, and consumes the rest of that gesture. Other legitimate owners continue. Release All remains global.

The zone option **Finish ignores swipes** is off by default and applies to rings and floating Buttons/groups. Enable it to pass through Finish during a captured drag, including historical samples and the final lift. Lift still follows the control's ordinary release/retention behavior. A fresh touch on Finish, the accessible Finish list and explicit scoped Finish actions still end the instance.

## Ring options and defaults

Movement, inner aim, Boost 1 and Boost 2 are enabled independently. Disabled settings remain stored. New rings start with movement and aim enabled, boosts disabled, one aim knob, Mouse aiming, no ADS and Fire mode None. These are configuration defaults, not game-tested recommendations.

New ring dimensions use dp. The default movement radius is 88 dp and inner radius 48 dp. Linear movement with sensitivity 1 is measured from the center: 22 dp = 25%, 44 dp = 50%, 88 dp = 100%; center is neutral and outward movement caps at full tilt. Boost distance uses raw physical distance independently of the response curve. Existing directional Buttons keep their saved first-contact pixel thresholds (defaults 48 and 96 px).

Each of up to three aim knobs chooses None, Hold, Tap, Release-shot or Repeat. ADS and fire bindings are independent and may be blank. Mouse, Game Mouse and Right Stick aim expose existing tuning semantics. Aim normally continues outside. Optional stop-outside discards outside mouse travel, centers RS, and resumes without a new DOWN or accumulated travel. Aim-output centering, ADS/fire outside behavior and lift retention remain separate.

Boost drag stages are cumulative; direct taps activate only the selected stage. Each stage selects Tap, Hold or Repeat. Hold latch after threshold exit and Repeat continuation after exit are separate from lift retention. Threshold hysteresis avoids MOVE-stream retriggering. Defaults are 120/160 dp thresholds, 35 ms press and 35 ms gap, zero Boost-2 delay, exit cancellation enabled, and latch/continuation/retention disabled. Direct-tap areas have finite saved inner/outer bounds and start disabled.

In **Ring geometry, layers and starting role**, two options bring drag boosts closer without rewriting saved radii or direct-tap areas. Both are off by default:

- **Compact boosts while aiming:** during an original Aim gesture, Boost 1 begins immediately outside the inner Aim radius and Boost 2 begins at the Movement radius (48/88 dp with default sizes). The Movement background returns on lift. Enable **Aiming participates in boosts** separately. Movement/direct-started gestures keep their captured role.
- **Boost 1 immediately outside Movement:** during a Movement gesture, Boost 1 begins just beyond the Movement radius. Boost 2 preserves the saved gap between the two boost thresholds: defaults become 88/128 dp. Movement still reaches full strength at 88 dp.

At the exact Aim/Movement edge the inner layer remains active; Boost 1 begins beyond it. Hysteresis is capped at half the derived band width when necessary so a large saved value cannot trap a closer boost. Edit shows labeled actual-size threshold guides behind the controls, including disabled references and dotted compact-Aim boundaries. They follow zone dragging and cancellation; they are sample summons at the activation rectangle's center, since the eventual Play position depends on the real first touch. The reachability preview supplies the matching cardinal feedback. Boost outlines remain invisible in Play, and fitting/visible target sizes are unchanged.

Boost direction changes recognize Up/Down and Left/Right reversals within a gesture and after lift. Neighboring direction sectors keep their 12 dp margin. Blank opposite-direction bindings cannot reuse a previous direction's payload; an already started Tap still completes normally. Deliberately latched or retained outputs keep their existing retention semantics.

Return-fire is off by default. When enabled, only a qualifying same-pointer boost visit followed by inner return starts the selected delay. Aim resumes immediately. Exit cancellation defaults on: leaving cancels and a newly qualified return starts a complete new delay. With it off, the original deadline continues without duplicate/restarted work; this does not enable outside aiming or make an otherwise canceled release-shot eligible.

Each aim knob also offers **Start fire delay when entering Boost**, off by default. With qualified boost firing enabled, the first qualifying Boost entry starts the countdown immediately; crossing another qualifying stage or returning to Aim keeps that deadline. With a 300 ms delay, a return after 200 ms leaves 100 ms to wait. If **Continue fire outside** is enabled, the deadline can fire before Aim return. With it disabled, an expired deadline becomes ready and fires on Aim return without another delay. Release-shot only arms at the deadline and still needs an eligible later lift. Early lift, Cancel, Finish and Stop fire invalidate unexpired work. Exit cancellation applies after the first Aim return; it does not cancel the initial outward countdown. A later qualified Aim-to-Boost cycle can start another delay. Direct Boost starts use the configured aim destination for early firing only when direct-to-Aim is enabled; command-only direct boosts remain command-only, and early firing alone does not start aiming, ADS or Camera Follow suppression. Boost-only preset application preserves this knob setting; explicit saved aiming/actions application includes it.

Boost-2 entry delay is separate from return-fire delay. It freezes the selected payload/action. UP before an unexpired deadline cancels the action completely, including with future retention enabled. A delayed Release-shot arms at its deadline and fires only on an eligible later lift; CANCEL and Finish never fire it. There is at most one waiting finite shot with a real release gap. Conflicting fire sources are rejected or indicated rather than stealing owners.

Fine sensitivity is opt-in, starts immediately on a selected boost return, and follows the selected backend/profile semantics. It remains while this ring's qualifying owned ADS latch remains, including later touches, or is gesture-scoped for a nonlatched qualifier. It does not change another aiming surface.

## Scoped actions and Camera Follow

An ordinary control's property sheet opens Floating / Camera Follow options. Buttons can assign Finish, Stop fire, Release ADS or End suppression to an explicit target. Stop fire retains independent ADS and movement. Output ownership preserves other controls and existing face/voice sources; a pulse cannot make a full release while another source holds that output. Actual masked-output feedback is shown by the ring.

Mouse suppression is opt-in. Engagement claims suppression even while stationary; a ring claim persists through lift until scoped end or hard cleanup. Movement/direct-boost summon alone does not claim it. Movement exclusion from Camera Follow remains a separate setting. Ending one claim preserves other claimants and the user's enabled preference.

## Saved formats and reusable profiles

Floating configuration or stored suppression/scoped actions require nested controller format 10, including inactive configuration. Legacy profiles retain their existing conditional formats. Transfer envelope 1 still checks nested formats before decoding. A named boost library requires backup envelope 2 independently of controller format; a full-ring preset library requires backup envelope 3. Historical backup 1 and 2 remain readable.

Enabling any of the closer-boost, Finish-swipe or Boost-entry fire-timing options requires nested controller format 11, even when the containing zone/layer is disabled. Full-ring snapshots use schema 2 only when they contain these options; older snapshots remain schema 1. A boost snapshot that includes the new knob fire timing uses boost schema 2; ordinary boost snapshots remain schema 1. Transfer/backup envelope versions stay unchanged. Readers reject unsupported or incorrectly labeled nested formats before importing settings.

Save a named boost profile as a frozen snapshot. Apply boost settings only preserves layers, placement, movement, zone geometry and all normal/fine aiming tuning. An optional saved ring aiming/actions preset has its own explicit apply action and validates knob-slot mapping before any change. Editing/deleting a reusable profile does not alter previously applied controls.

To move a complete tested ring into another controller profile, open its zone settings and choose Save full ring / activation zone preset. This captures the current draft's name, enabled switches, both saved activation rectangles, ring dimensions, movement, normal/fine aiming, knob positions and actions, ADS/fire, boosts, and starting settings. It saves configuration only, with no live position, held output or timers. Grouped Buttons are excluded from this library.

In the target profile, tap + -> Floating ring -> Saved Presets, select the preset, and choose Add a new ring to this profile. Review the zone editor and Save to add it, then save the controller profile normally. Each added ring has a new zone ID and independent settings; existing zones and controls remain in place. Saved canvas dimensions retain the usual proportional activation-zone placement when displayed on a different canvas. Rename/delete affect the reusable preset only. All-profile backup/restore includes this library and adds imported presets without replacing existing ones.

Summoned position/visibility, pointers, timers, suppression, ownership and armed shots are never serialized. Pause, page/profile/orientation/transport/player change, disconnect, removal and existing hard cleanup end affected sessions.

## Verification boundaries

JVM tests exercise pure ring transitions, pulse timing, ownership, geometry, serialization, version rejection and copies. Receiver tests use fake native outputs and local mock sockets. APK/AAB compilation and lint do not establish phone touch feel, Android dispatch on hardware, audio/haptics, receiver deployment, game timing or Pico behavior. No connected primary-phone test/install, data clearing, receiver EXE replacement or firmware flash belongs to these checks.

## Verified implementation checks - October 13, 2026

- 311 JVM tests passed in each of Sideload Debug and Play Release, including 41 ring tests and 19 shared-output coordinator tests.
- 265 receiver regressions passed with fake native outputs and local mock sockets. Root and Edit exe Folder receiver/helper sources match byte for byte.
- Synthetic examples verify 25% movement at 22 dp with an 88 dp radius; early lift cancels delayed firing; direct Boost 2 never invents Boost 1; Finish preserves another ADS/RT owner; lost earlier OFF packets still require terminal-state acknowledgement; and ending an LS macro restores another owner's held manual vector.
- Sideload APK and Play AAB packaging passed. Full lint passed for both variants with zero errors (197 Sideload warnings, 195 Play warnings).
- A separately authorized in-place ADB update succeeded on the connected S22. Signing certificates matched, the installed APK matched the built APK, and all 116 saved files retained identical names and SHA-256 hashes. The previous APK and a verified saved-file snapshot were retained in isolated local output, outside Git.

The app was not launched for touch/game testing. The installed receiver EXE was not rebuilt or replaced; new floating activation remains gated until that receiver acknowledges the new capability and activation barrier.

## Zone editor verification - October 14, 2026

The drag/slider update passed 312 JVM tests in each Android variant, Sideload APK and Play AAB packaging, and full lint with zero errors (201 Sideload warnings, 199 Play warnings). The added regression checks that a rectangle saved on a 1000 x 2000 canvas appears at half scale on 500 x 1000, preserves fractional dimensions, and stays unchanged when saved again on that canvas. Source review covered gesture cancellation, pause/resume, overlapping outlines, and resize/rotation guards; physical phone dragging and resizing were not exercised automatically.

The separately authorized in-place ADB update verified matching signing certificates and installed APK bytes. All 117 saved files matched the fresh pre-update snapshot by filename and SHA-256. No receiver source or protocol changes are needed for this editor update.

## Receiver recognition verification - October 15, 2026

Phone logs identified NetworkOnMainThreadException in the heartbeat, which closed the acknowledgement socket while the separate legacy UDP socket kept working. Heartbeats now use an I/O worker and captured route; listeners and queued send failures ignore replaced sockets. An intentional zone touch can reconnect a closed acknowledgement channel or retry unfinished negotiation. The misleading incompatible-receiver error is replaced with temporary connecting feedback while the scoped-output handshake completes.

Both Android variants passed 315 JVM tests, APK/AAB packaging, and full lint with zero errors (201 Sideload warnings, 199 Play warnings). Three local UDP regressions verify worker-thread dispatch with the unchanged PING payload, no send on an obsolete route, and failure reporting only for a current socket. These are JVM socket checks, not phone touch or gameplay tests.

The authorized in-place ADB update verified matching signing certificates and installed APK bytes. All 118 saved files retained identical names and SHA-256 hashes against the fresh pre-update snapshot. The app was not launched for touch/game testing; no receiver source or EXE change was needed.

## Selection, direction and full-ring preset verification - October 15, 2026

Both Android variants passed 338 JVM tests, APK/AAB packaging, and full lint with zero errors (201 Sideload warnings, 199 Play warnings). The 23 added tests cover eight boost direction/retention cases, five selection-completion cases and ten complete preset/backup cases. Examples include Up-to-Down reversal across lift without a duplicate Up Tap, preserved 12 dp neighboring-sector hysteresis, reentrant removal cancellation completing once, Back before drawing opening no preview, and a full preset retaining disabled settings and both orientation rectangles through serialization. Selection checks use a synthetic callback queue; they do not exercise Android's native view dispatch or Back button on hardware.

The authorized signing-compatible in-place ADB update verified the installed APK against the built APK. All 118 saved files retained identical names and SHA-256 hashes against a fresh pre-update snapshot. Phone drawing, native Back, full-preset dialogs and gameplay were not automatically exercised. Shared-RT ownership behavior, receiver source and the receiver EXE remain unchanged.

## First-touch, menu and inward boost verification - October 15, 2026

Both Android variants passed 360 JVM tests, APK/AAB packaging and full lint with zero errors (201 Sideload warnings, 199 Play warnings). Thirteen new pending-contact tests exercise latest-position activation with a fresh clock, cancellation before acknowledgement, bounded timeout, route loss and stale callback rejection. Eight inward-boost tests exercise no lower action on the initial direct Boost 2 touch, lower-stage activation without its direct-tap switch, 120/118 dp entry/exit hysteresis, a fast return qualifying fine aim, unchanged 300 ms delayed RT, disabled stages, cancellation, repeat timing and per-gesture reset. Serialization checks retain the new option in complete presets and preserve its disabled default when older saved rings omit the field.

MotionEvent history reconstruction, Edit menu access and inactive floated-output rejection received source review and compiled successfully. JVM checks do not exercise Android native touch dispatch, dialogs, physical phone interaction or game behavior. The candidate APK's signing certificate matches the retained previously installed APK. Installation is deferred until Tyler explicitly confirms that profile editing is finished; no phone files or installed app have been changed by this verification. Receiver source and EXE are unchanged.

## Closer boosts, Finish policy and entry-timed fire - October 16, 2026

Both Android variants passed 402 JVM tests, APK/AAB packaging and full lint with zero errors (205 Sideload warnings, 203 Play warnings). The 42 added tests cover closer geometry, early firing, Finish protection and saved options. Synthetic examples check no Boost 1 at the exact 48 dp Aim edge but activation just beyond it; Movement reaching full tilt at 88 dp before Boost 1 starts beyond that edge; Boost 2 at 128 dp for adjacent Movement defaults; preserved edge-fitting offsets; and cancellation of large-hysteresis nonlatched boosts. Timer cases check RT at 300 ms from Boost entry, one deadline across direct Boost 2-to-1 and Aim return, shallow 49-to-47-to-49 requalification, outside-disabled readiness, Release-shot eligibility, direct Repeat cleanup on lift, and stale callback rejection. Finish checks cover all captured ring roles, historical crossings, normal lift/release-shot behavior and a fresh direct Finish touch. Persistence checks round-trip all 16 combinations of the new options, including disabled configuration, through profiles and complete presets, and reject mislabeled nested versions.

Edit guides and grouped-button Finish dispatch received source review and compiled successfully. Pure JVM checks do not exercise Android native drawing, dialogs, touch feel or gameplay. The four additional lint warnings concern Edit-guide drawing allocations, its programmatic constructor and a suggested Canvas convenience API; no lint error was introduced. Receiver source, protocol and EXE are unchanged.

After explicit confirmation that profile edits were saved, the signing-compatible in-place ADB update succeeded on the S22. The installed APK matches the verified candidate; all 122 saved files retained identical names and SHA-256 hashes against the fresh snapshot. The prior APK and saved-file archive remain in `SimpleController profile backups/2026-10-16 ring reach update` beside the source directories. The app was not launched for automated touch/game testing. This APK remains a working build; the public release APK and release branch have not been updated.
