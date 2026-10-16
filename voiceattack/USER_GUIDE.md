# VoiceAttack with SimpleController

Use a spoken command to press a keyboard key, click or move the mouse, or operate the SimpleController receiver's virtual Xbox controllers. You can keep editing commands in VoiceAttack's normal editor; no separate bridge program or imported command profile is required.

| Output you want | Action to use in VoiceAttack | Receiver needed? |
| --- | --- | --- |
| Keyboard keys or combinations | Built-in **Key Press** actions | No |
| Mouse clicks, held buttons, movement or scrolling | Built-in **Mouse** actions | No |
| Xbox buttons, D-pad, stick clicks, LT or RT | The included [VoiceAttackSender.cs](VoiceAttackSender.cs) helper | Yes |

The controller helper supports timed presses and persistent holds on the receiver's existing Player 1/2 controllers. Analog stick movement is not part of this voice helper. `LS` and `RS` below mean pressing the stick buttons, not moving the sticks.

[Main setup and downloads](../README.md) · [SimpleController user guide](../USER_GUIDE.md) · [Optional facial input](../facial-input/README.md)

## Before you start

Install VoiceAttack and configure its microphone and speech recognition. Confirm that the level meter responds and that its recognition log shows the phrase you intended. These instructions follow VoiceAttack 1.12's help; labels can vary between versions. The [official VoiceAttack help](https://voiceattack.com/help) explains microphone setup and the normal command editor.

Use distinct phrases such as **start aiming** and **stop aiming**. You do not have to keep a physical key or mouse button held while speaking. In the key editor, the small keyboard icon opens an on-screen key chooser, so selecting a key or modifier does not require a physical multi-key combination.

For controller output, install the [ViGEmBus driver](../ViGEmBus_1.22.0_x64_x86_arm64.exe) once, restart Windows if requested, and run the matching [SimpleControllerReceiver.exe](../SimpleControllerReceiver.exe) on the **same PC as VoiceAttack**. The EXE includes Python but does not install ViGEmBus. Keep one receiver running; multiple copies use the same phone port and create additional virtual controllers. A phone and the facial-input app are optional for voice-only use.

The voice connection is local TCP `127.0.0.1:42736`. You do not enter the PC's Wi-Fi address in the helper or forward this port through a router. The receiver's phone connection is separate, on UDP `42734`.

Preserve your existing VoiceAttack profile. Export a copy before substantial editing, or add these as new commands with unique names.

## Keyboard commands

1. Edit your active VoiceAttack profile and choose **New Command**.
2. Enable **When I say** and enter a phrase, for example `open map`.
3. Add a **Key Press** action. Use the on-screen key chooser to select the key your game actually uses, such as `M`.
4. Choose **Press and Release Key(s)** and set **Hold down for** to `0.10` seconds.
5. Save the command and choose **Done** or **Apply**. Focus the target application before speaking.

The key is a game binding, not a built-in meaning: `M` opens a map only if that application assigns M to its map.

For movement that continues after one phrase, create a matching pair:

| Spoken phrase | Key | Key Press setting |
| --- | --- | --- |
| `walk forward` | `W` | **Press Key(s)** |
| `stop walking` | `W` | **Release Key(s)** |

The first command holds W after the command finishes; the second releases it. Create the release command before testing the hold. For a modifier combination, select its modifier and key in the same Key Press action rather than physically holding both yourself.

## Mouse commands

Create a new spoken command, add a **Mouse** action, and select the appropriate click, move or scroll action.

| Spoken phrase | Mouse action |
| --- | --- |
| `left click` | **Mouse Click → Click → Left**, with a `0.10` second click duration for an initial game test |
| `hold left mouse` | **Mouse Click → Press down → Left** |
| `release left mouse` | **Mouse Click → Release → Left** |
| `move mouse right` | Adjust the mouse cursor **Right** by `50` pixels, using **Move from cursor position** |
| `scroll down` | **Scroll → Backward**, one wheel click |

Create and verify the release command before using a held mouse button. A right-button pair uses **Right** instead of **Left**. For games that ignore ordinary cursor movement, the mouse-movement editor offers **Move using relative data**; whether a game accepts it needs testing in that game. An absolute screen position is usually less portable between window sizes and displays than a relative movement.

Keyboard/mouse actions above go directly from VoiceAttack to Windows. They are not sent through the controller helper and do not acquire its phone/face/voice ownership protection. Avoid assigning the same desktop key or mouse button to simultaneous holds from several applications: one application's release can interrupt another's hold. Keep explicit native release commands for the keys and mouse buttons you use.

## Set up the controller helper once

1. Download or open [VoiceAttackSender.cs](VoiceAttackSender.cs). On GitHub, **Copy raw contents** copies the whole file without selecting hundreds of lines manually.
2. In your VoiceAttack profile, create a command named `SimpleController send`. This is a reusable helper called by your spoken commands; do not assign it a keyboard, mouse or joystick shortcut.
3. Add **Other → Advanced → Execute an Inline Function: C# or VB.net Code**. Choose **C#**.
4. Replace the generated sample in the code editor with the **entire** helper file. VoiceAttack supplies the `VA` object; do not add another `VA` declaration. The editor's selection/paste commands can be used without a keyboard chord.
5. Set **Referenced Assemblies** to this exact text:

   ```text
   System.dll;System.Core.dll;Microsoft.CSharp.dll;System.Web.Extensions.dll
   ```

6. Select **Wait for the inline function to finish before continuing**. **Retain Instance** is optional. Leave **Build Output** empty.
7. Use **Compile**, then save the helper and choose **Done** or **Apply**. No external plugin, DLL build or additional bridge process is needed.

**Test Run** actually executes the function. With no passed values, this helper reports an invalid/missing action; that is not a failed installation. Test it through a configured command below, with the receiver running and a controller tester ready.

## Create spoken controller commands

Create **release voice controls** first, followed by a simple timed command such as **jump**.

For each command:

1. Create a new command with its spoken phrase in **When I say**.
2. Add **Other → VoiceAttack Action → Execute Another Command**, selecting `SimpleController send` from the existing-command list.
3. Select **Wait until this command completes before continuing** on **every** helper call.
4. Open **Passed Values (Advanced)**. Copy each table entry into its separate **Text**, **Integers**, or **Decimals** box. Leave every unused box empty.
5. Save and apply your changes.

Use ordinary double quotes around each text value, with semicolons between values. Integers and decimals have no quotes. Decimal values use a point, for example `0.8`, not a comma. The action and player names below are lowercase; button names are uppercase.

| Spoken phrase | Text passed values | Integers | Decimals | Result |
| --- | --- | --- | --- | --- |
| `release voice controls` | `"clear"` | | | Releases voice controller inputs and timers on both players |
| `jump` | `"tap";"A";"player1"` | `100` | | A for 100 ms |
| `interact briefly` | `"hold";"X";"player1"` | `500` | | X for 500 ms |
| `start holding interact` | `"hold";"X";"player1"` | | | X until explicitly released |
| `stop holding interact` | `"release";"X";"player1"` | | | Releases voice's X hold |
| `start aiming` | `"trigger";"LT";"player1"` | | `1` | Full LT until explicitly released |
| `stop aiming` | `"release";"LT";"player1"` | | | Releases voice's LT |
| `fire briefly` | `"trigger";"RT";"player1"` | `250` | `0.8` | RT at 80% for 250 ms |
| `d pad up` | `"tap";"UP";"player1"` | `100` | | D-pad Up for 100 ms |
| `click left stick` | `"tap";"LS";"player1"` | `100` | | Left-stick button click |
| `player two jump` | `"tap";"A";"player2"` | `100` | | Player 2's A for 100 ms |
| `release all receiver inputs` | `"release_all"` | | | Global receiver cleanup; see the cleanup table below |

Do not paste Android payloads such as `X360A`, `LT:1.0`, `LS:R50`, `W`, or `MOUSE_LEFT_DOWN` into this helper. For example, its controller A command uses separate `"tap"` and `"A"` text values; its trigger uses `"trigger"`, `"LT"`, and the Decimals box.

Supported digital buttons are `A`, `B`, `X`, `Y`, `LB`, `RB`, `BACK`, `START`, `LS`, `RS`, `UP`, `DOWN`, `LEFT`, and `RIGHT`. The directional names mean the D-pad; LS/RS are stick clicks. Trigger names are `LT` and `RT`. Change the third text value to `"player2"` for Player 2; an omitted player defaults to Player 1. Cleanup affects both players, regardless of that default.

### Timed presses versus held output

Durations are **milliseconds**: `100` is one tenth of a second; `1000` is one second. Valid finite durations are `1` through `600000`. A tap with no integer defaults to 100 ms. A hold or trigger with no integer is intentionally persistent: it survives completion of the VoiceAttack command and the helper's TCP connection.

For a digital button, both `tap` and `hold` can use a finite duration. Their important difference is the default when duration is blank. Trigger values run from `0` to `1`; `0.4` is 40%, `1` is full, and `0` releases that voice trigger. An explicit `release` has no duration or decimal value.

The receiver owns finite release timing. **Wait until this command completes** waits for the helper/acknowledgement, not for the full button-hold duration. Add a normal VoiceAttack Pause when a later action must wait for that duration to finish.

## Sequences, pauses and repetitions

Keep sequences and conditions in VoiceAttack's normal editor. Every multi-step controller macro starts with a synchronous helper call using Text `"begin"`, with Integers and Decimals blank. Put it **before the first pause or loop**. It checks the receiver and pins the macro's generation without pressing anything.

For a spoken command **jump twice**, add these actions in order:

1. Call the helper with Text `"begin"`.
2. Call it with Text `"tap";"A";"player1"`, Integers `100`.
3. Add a VoiceAttack **Pause** of `0.20` **seconds**.
4. Call it with Text `"tap";"A";"player1"`, Integers `100`.

The 200 ms pause leaves room for the first 100 ms tap to release before the second starts. If you use a finite loop, repeat the tap-and-pause pair after a single initial `begin`. Keep the pause longer than the press duration when you need distinct press edges. A pulse on a button already held by voice does not interrupt that persistent hold; overlapping finite pulses extend its deadline rather than generating a guaranteed extra click.

For **aim and fire briefly**, one possible sequence is:

1. Helper: Text `"begin"`.
2. Helper: Text `"trigger";"LT";"player1"`, Decimals `1`, Integers blank.
3. Helper: Text `"trigger";"RT";"player1"`, Integers `250`, Decimals `0.8`.
4. VoiceAttack Pause: `0.30` seconds.
5. Helper: Text `"release";"LT";"player1"`, Integers and Decimals blank.

LT remains held while RT makes its finite press. If this macro is stopped before its final LT release, use **release voice controls**.

For a long macro or loop, enable the top-level command option **Allow other commands to execute while this one is running**, so a separate spoken cleanup command can run. This is separate from waiting for helper calls: keep both wait options described above enabled, including calls through nested commands.

`clear` and `release_all` invalidate later helper actions in controller macros that already began. Put cleanup last if you use it inside a macro; start a **new, separately invoked command** for subsequent controller work. The barrier does not stop VoiceAttack's own pauses, loops, keyboard/mouse actions, or unrelated actions. Do not edit the helper's `SCVoice.*` or `~~SCVoice.*` runtime variables.

## Share the controller with phone and facial input

All three inputs use the receiver's same controller pair. Digital buttons combine as **phone OR face OR voice**. If the phone is holding A, a voice tap cannot create a fresh press edge, and its expiry will not release the phone's hold. This can make a correctly received voice tap appear to do nothing until the other owner lets go.

Triggers use the **highest active value**. Phone RT at 40% plus voice RT at 80% produces 80%; releasing voice restores 40%. A facial full-trigger hold likewise remains until facial input releases it.

Within voice input, a persistent trigger is a baseline. If voice holds RT at 40%, a finite voice 100% press temporarily raises it, then restores 40%. A temporary lower value cannot lower that baseline. A new finite trigger replaces the earlier temporary value/deadline; a new persistent value cancels the temporary overlay. Explicit release or trigger value zero clears both voice layers for that trigger.

Voice ownership is shared across voice commands, not tracked separately for each spoken phrase: releasing X also releases X held by another voice command. Phone and facial ownership remain independent.

## Stop and recover

| Action | What it clears | What it does not guarantee |
| --- | --- | --- |
| Helper `"release";"X";"player1"` | Voice's X on Player 1 | Releasing phone/face holds, or other voice buttons |
| Helper `"clear"` / **release voice controls** | All voice controller holds and finite timers, both players; blocks old controller macro continuations | Stopping phone/facial inputs or native VoiceAttack keyboard/mouse actions |
| Helper `"release_all"` / **release all receiver inputs** | Global receiver cleanup, including its phone/face/voice outputs and timers | Turning off Android's internal Hold/Turbo loops; new phone packets can reassert them |
| VoiceAttack **Stop Commands** | Stops VoiceAttack macros; its help also documents release of keys held by VoiceAttack | Clearing persistent controller holds already owned by the receiver |
| Your native **Release Key(s)** / mouse **Release** commands | The desktop keys/buttons you explicitly name | Receiver-owned controller holds |

Global receiver cleanup disarms facial input; intentionally start facial output again when ready. Use the phone app's **Release All** to end its own Hold/Turbo/repeating state. For native keyboard/mouse output, keep the explicit release commands you configured; do not rely on receiver cleanup to manage those separate Windows actions.

If VoiceAttack crashes, persistent controller holds can remain. A new **release voice controls** command, the phone's global Release All, or a receiver restart provides recovery. Restarting the receiver also ends the old receiver session, so any surviving old macro must be invoked afresh.

If the helper times out or loses an acknowledgement, it logs an error and does **not** automatically retry. The action may already have reached the receiver. Start a new **release voice controls** command to recover instead of repeatedly rerunning the failed macro. Its later helper calls are blocked for that execution chain; unrelated native actions are not automatically canceled.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Speech is not recognized | Check VoiceAttack's selected profile, listening state, microphone meter and recognition log. Adjust the phrase rather than changing receiver tokens. |
| Inline compile error | Paste the complete helper, select C#, use the four assembly references above, and do not declare another `VA` field. |
| Test Run says the action is missing/invalid | Configure a spoken command's Passed Values; the reusable helper cannot guess an action. |
| Connection refused or timed out | Run the matching receiver on the same PC. Its console should report VoiceAttack listening at `127.0.0.1:42736`. Confirm ViGEmBus is installed and only one receiver is running. |
| Receiver reset/restarted, or macro already failed | Invoke a fresh command; old continuations are intentionally blocked after cleanup, restart or an ambiguous send. |
| Command appears recognized but controller does not respond | Check the helper's error log, Player selection and a controller tester. Check whether phone/face/another voice command already holds that button. Focus the game and confirm its controller bindings. |
| Keyboard/mouse works on the desktop but not in a game | Confirm the game's bindings and target/focus. For movement, try VoiceAttack's relative-data option. Games can restrict simulated input; desktop success is not game compatibility. |

## Helper provenance and technical reference

The adjacent `VoiceAttackSender.cs` is an unchanged copy of the existing VoiceAttack Bridge integration's `voiceattack/VoiceAttackSender.cs`. Its source text matches the helper in the SimpleController source checkout. It contains no user profile, settings, credentials or private host address. Its SHA-256 is:

```text
6387249cd33188817f4ad074a6754e88d8bdde44eec6c8a311705a3579ef7497
```

The corresponding receiver is [source/receiver/voice_input_server.py](../source/receiver/voice_input_server.py). Voice uses newline-delimited JSON **protocol version 1** on loopback TCP 42736, with receiver/session/generation/sequence checks and acknowledgements. The facial-input protocol is a separate connection on 42735; its version 3 is not a voice-helper version. The included helper handles the voice protocol automatically.

The setup descriptions were checked against the helper, matching receiver source, and VoiceAttack's shipped 1.12+ help: Key Press pages 41–42, Passed Values 48–49, inline functions 114–116, mouse actions 120–124, and command-shared scope 250–251. See the [official help](https://voiceattack.com/help). Source inspection and earlier synthetic protocol checks do not establish live speech recognition, controller/game acceptance or comfort on a particular setup.
