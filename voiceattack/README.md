# VoiceAttack controls for the existing SimpleController receiver

This sender is for the receiver with the new VoiceAttack endpoint. The new desktop
shortcut **Simplecontroller Receiver - VoiceAttack and Facial Input** points to
the separately packaged update. The original facial-input EXE and shortcut remain
available. Close the old receiver yourself before starting the new one.

VoiceAttack talks directly to `127.0.0.1:42736`; the receiver writes its existing Player 1/2 controllers. There is no keyboard intermediary, extra controller, or separate running bridge. Facial input remains a separate feature.

## Verified baseline and limits

Installed VoiceAttack is **1.12**, at `C:\Program Files (x86)\VoiceAttack\VoiceAttack.exe`. Its shipped `VoiceAttackHelp.pdf` documents the inline editor (pages 114-116), passed values (48-49), proxy variable methods (215-216), and command-shared variables (250-251). The [official help](https://voiceattack.com/help) and [official forum sender example](https://forum.voiceattack.com/smf/index.php?topic=4359.0) also describe these features.

The sender compiles using the installed .NET Framework C#5 compiler. Automated checks use a fake VoiceAttack proxy and fake controller output. They do **not** verify the real VoiceAttack editor, speech recognition, physical controller, game, camera, or your packaged EXE.

No existing VoiceAttack profile or application data is changed by these files. The installed sample `.vap` files contain external-plugin actions, not an inline-function export; an importable profile is withheld until the actual inline export format is verified. The following setup uses VoiceAttack's normal editor.

## One reusable sender command

1. In VoiceAttack, create a new command named `SimpleController send`. Make it a helper called by other commands; do not assign a keyboard, mouse, or joystick shortcut.
2. Add **Execute an Inline Function: C# or VB.net Code**, choose C#, and paste the entire `VoiceAttackSender.cs` file into its code editor. VoiceAttack supplies the `VA` object.
3. Set **Referenced Assemblies** to `System.dll;System.Core.dll;Microsoft.CSharp.dll;System.Web.Extensions.dll`. These are existing .NET Framework assemblies; no installation or plugin is required.
4. Select **Wait for the inline function to finish before continuing**. **Retain Instance** is optional; the sender uses static locking and runtime variables. Leave **Build Output** empty.
5. Use **Compile** to check the editor integration. **Test Run** executes code; do not use it to send controller actions during setup.
6. Save the command, then use **Done** or **Apply** to make new commands available.

## Starter spoken commands

Create each spoken command below with one **Execute Another Command** action calling `SimpleController send`. Select **Wait until this command completes before continuing** on every call. Open its **Passed Values (Advanced)** section and enter the shown values into their separate Text, Integers, and Decimals boxes. Blank cells mean leave that box empty. Literal text requires double quotes; integer/decimal values do not.

| Spoken phrase | Text passed values | Integers | Decimals | Effect |
| --- | --- | --- | --- | --- |
| jump | `"tap";"A";"player1"` | `100` | | A for 100 milliseconds |
| hold interact briefly | `"hold";"X";"player1"` | `500` | | X for 500 milliseconds |
| start holding interact | `"hold";"X";"player1"` | | | X until explicitly released |
| stop holding interact | `"release";"X";"player1"` | | | Releases voice's X ownership |
| start aiming | `"trigger";"LT";"player1"` | | `1` | LT at 100% until released |
| stop aiming | `"release";"LT";"player1"` | | | Releases voice's LT ownership |
| fire briefly | `"trigger";"RT";"player1"` | `250` | `0.8` | RT at 80% for 250 milliseconds |
| release voice controls | `"clear"` | | | Clears voice inputs/timers on both players |
| release everything | `"release_all"` | | | Calls existing global Release All |

For `start aiming`, the Decimals box contains `1`; all other unused boxes remain empty. A decimal of `0.4` means 40%, `0.8` means 80%, and `1` means 100%. Trigger `0` also releases that voice trigger. Use `"player2"` for Player 2. If omitted, the player defaults to Player 1; cleanup always affects both players.

Digital buttons: `A`, `B`, `X`, `Y`, `LB`, `RB`, `BACK`, `START`, `LS`, `RS`, `UP`, `DOWN`, `LEFT`, `RIGHT`. Here `LS`/`RS` mean stick **clicks**; UP/DOWN/LEFT/RIGHT mean D-pad buttons. Voice stick movement is outside this first version. Valid timed durations are 1-600000 milliseconds. An omitted tap duration defaults to 100 milliseconds. An omitted hold/trigger duration intentionally persists after the command and connection finish.

The helper reads `~passedText1` as action, `~passedText2` as button, `~passedText3` as player, `~passedInteger1` as duration, and `~passedDecimal1` as trigger value. Empty button/player positions must not accidentally shift later text arguments; use the examples verbatim.

## Pauses, repetitions, and conditions

Keep normal macro editing in VoiceAttack. For every macro with multiple actions, begin with a synchronous `SimpleController send` call whose Text passed values are `"begin"`, with Integers and Decimals blank. This only reads the receiver greeting and pins that macro's generation; it sends no controller action. Put this first, before any pause or loop.

Example `jump twice`:

1. Call the helper with Text `"begin"`.
2. Call it with Text `"tap";"A";"player1"`, Integers `100`.
3. Add a normal VoiceAttack pause of **0.2 seconds**.
4. Call it with Text `"tap";"A";"player1"`, Integers `100`.

Or repeat steps 2-3 in VoiceAttack's normal loop. Keep every helper call synchronous, including nested commands, so `~~SCVoice.receiver`, `~~SCVoice.epoch`, and `~~SCVoice.failed` remain shared throughout that execution chain. Do not clear or overwrite these internal variables.

The receiver owns each timed release. A finite button pulse never cuts short a persistent voice hold already active on that same button. Overlapping finite button actions extend the voice hold to the latest deadline; leave a pause longer than the tap duration between repeated taps if you need separate presses.

A persistent voice trigger value remains as a baseline under a finite voice trigger action. Example: voice holds RT at 40%, then requests 100% for 200 milliseconds; RT returns to voice's 40% when the finite action expires. The higher of that baseline and the temporary action is used, so a temporary 20% action does not lower a 40% baseline. A new finite trigger action replaces the prior temporary value/deadline. A new persistent trigger value replaces the baseline and clears the temporary action. An explicit trigger release clears both voice values; phone/facial trigger values remain independent.

## Ownership and recovery

A digital button stays held while phone, face, **or** voice owns it. A voice release or expiry preserves phone/facial holds. Example: the phone holds A; voice taps A; after 100 milliseconds A remains held until the phone releases. That means a tap or repeated voice pulse cannot create a fresh press edge while another source keeps the button held.

Triggers use the highest active value. Example: phone RT at 40%, voice RT at 80% -> 80%; voice release -> 40%. A facial RT hold gives 100%; releasing face restores the highest remaining value.

`release voice controls` preserves phone/facial inputs and clears voice's deadlines on both players. `release everything` uses the existing receiver reset path and requires facial input to be started again. New phone messages may reassert held/turbo inputs afterward: receiver Release All cannot turn off the Android app's internal Hold/Turbo state.

Either cleanup invalidates later sender actions in macros that have already begun. A new, separately invoked spoken command uses the latest greeting and works normally. Cleanup at the end of a macro should be its final controller action. The generation barrier prevents stale output; it does not stop VoiceAttack's own pauses or unrelated actions.

If a connection or acknowledgement fails, the sender logs the problem, performs no automatic replay, and blocks later helper steps in that same macro. A missing acknowledgement can mean the action reached the receiver. Invoke a **new** `release voice controls` command to recover; use global cleanup if you intend to clear all sources. Persistent holds intentionally survive VoiceAttack command completion and client disconnect; after a VoiceAttack crash they require receiver cleanup or restart.

## Isolated validation

`test_sender_mock.py` compiles a temporary copy with a fake `VA` field and substitutes its own mock server's randomly assigned loopback port. It never contacts the production endpoint. Each run creates and preserves a new `voiceattack/.mock-builds/run-*` directory; existing outputs are not replaced or deleted. Run it with the bundled Python or another suitable local Python installation.

The delivered inline source intentionally has no fake `VA` field. VoiceAttack provides that field during its own compilation. The test harness only supplies the equivalent mock field in its generated copy.

`test_sender_interop.py` compiles that same sender and connects it to the actual
Python voice protocol and merged pad with fake hardware, using only an ephemeral
port. Use `--receiver-dir` to test a selected source directory. Neither test
imports the production receiver entrypoint or loads the native input libraries.

The final receiver/facial suite passed **106 synthetic tests**, including the
existing facial client's expression-to-TCP path and the legacy Camera Follow and
Release All tests. The compiled C# sender passed **49 mock assertions** and **46
assertions against the Python protocol**. Examples include phone/facial holds
surviving voice expiry, Player 2 isolation, LT/RT baseline restoration, stale
macro rejection, and global cleanup continuing after an injected voice-pad
failure. These are distinct from real VoiceAttack/editor or game validation.

## Delivered EXE, switching, and future rebuilds

**The new EXE contains these changes; the original EXE remains unchanged.**
The new delivery is under `Voiceattack Bridge/deliveries/receiver-voiceattack-20261009/`.
Its archive was inspected without launching it: voice/facial/Camera Follow modules
and both ViGEmClient DLLs are present. The build report records source and EXE
hashes. Do not use the older `Rebuild SimpleController Receiver.bat`:
it cleans/overwrites existing packaging outputs.

For future receiver source changes, **manually rebuild the EXE**; editing Python
does not update a packaged receiver. Run the mock verifier, then the new helper
from PowerShell. Choose an absolute
delivery directory that does not exist; the helper refuses to overwrite it.
It uses the installed Python/PyInstaller/DLLs, installs nothing, and places every
packaging output under that new directory. It does not launch the EXE or change
any shortcut. Example after this patch is applied to the Play checkout:

```powershell
& 'C:\Users\Tyler\AppData\Local\Programs\Python\Python313\python.exe' -B 'C:\Codex Projects\New codex\Simplecontroller-play-release\voiceattack\verify_receiver.py'
& 'C:\Codex Projects\New codex\Simplecontroller-play-release\voiceattack\build_receiver.ps1' -DeliveryDirectory 'C:\Users\Tyler\OneDrive\Documents\ChatGPT\Voiceattack Bridge\deliveries\receiver-voiceattack-next-build'
```

The new filename is `Simple Controller Receiver (VoiceAttack and Facial Input).exe`.
When ready, close the old receiver yourself before launching the new one; each
receiver creates its own Player 1/2 controller pair and uses the same phone UDP
port. Keep the original EXE and shortcut for recovery. The packaging helper
completed successfully for this delivery. Neither receiver was launched.

Once the new EXE is selected, compile the inline helper in the real VoiceAttack
editor, then perform an explicitly authorized controller-tester/game check.
Verify phone controls and facial input first, then voice tap/hold/triggers,
overlap, and both cleanup commands. Acknowledgements confirm receiver processing,
not game recognition or physical acceptance. No live controller/VoiceAttack/game
testing was performed during this implementation.
