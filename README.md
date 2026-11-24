# Simple Controller — Setup and User Guide

Simple Controller turns an Android phone or tablet into a customizable, one-finger control surface for Windows games and desktop apps.

**Android phone/tablet → Wi-Fi or USB tethering → Windows receiver → keyboard, mouse, or Xbox-controller input**

This guide covers prerequisites, installation, first-time setup, creating profiles, adding buttons, button syntax, controller syntax, usage, and troubleshooting.

This is **`dev`, the development branch**. Both dev and the [`main` public release](https://github.com/Colonelwheel/Simplecontroller/tree/main) include Button Macro Mode, `(U...)` timed releases and controller-profile format 17. Dev retains additional receiver/voice development tools and historical feature notes. Its APK remains the existing November 17 feature delivery; main release `06b1e64` rebuilt and verified the same APK bytes without replacing this dev download. See [branches and compatibility](BRANCHES.md) before moving profiles between app versions.

- [Download the development Android app](https://github.com/Colonelwheel/Simplecontroller/raw/refs/heads/dev/SimpleController.apk) (open this link in your phone browser)
- [Download the Windows receiver](https://github.com/Colonelwheel/Simplecontroller/raw/refs/heads/dev/Simple%20Controller%20Receiver.exe)
- [Download the ViGEmBus driver](https://github.com/Colonelwheel/Simplecontroller/raw/refs/heads/dev/ViGEmBus_1.22.0_x64_x86_arm64.exe)
- [Click here for the facial input](https://github.com/Colonelwheel/Simplecontroller/releases/download/facial-input-windows-2026-10-16/FacialInput.exe) — optional standalone Windows app; [setup and source](facial-input/README.md).
- [Click here for the VoiceAttack user guide](voiceattack/USER_GUIDE.md)

---

# 1. What Simple Controller Does

Create profiles for different games, arrange large buttons and sticks within comfortable reach, and choose what each control does. Features include held/toggle buttons, Turbo, swipe-across buttons, mouse touchpads, stick and aiming controls, pages, and floating rings or button groups. You can combine actions such as movement and sprint in one control.

The Windows receiver must stay open while you use the Android app. The packaged receiver includes Python, so you do not need to install Python to run it.

# 2. Installation

You need an Android device running **Android 7.0 or newer**, a **Windows 10/11 x64 PC**, and a local connection through your home network or Android USB tethering.

1. On the PC, download and run the [ViGEmBus installer](https://github.com/Colonelwheel/Simplecontroller/raw/refs/heads/dev/ViGEmBus_1.22.0_x64_x86_arm64.exe). Follow its prompts and restart Windows if requested. This is a one-time prerequisite: **the receiver EXE does not install the driver automatically**, and this receiver creates its virtual Xbox controllers at startup.
2. Download the PC receiver above and open it. Allow it through Windows Firewall on your trusted **Private** network, and keep its console window open. The receiver is not code-signed; Windows may show an unfamiliar-publisher warning.
3. On Android, download and open the APK above. If asked, allow that browser or file manager to install apps from this source, then select **Install**.

**Optional facial input:** download `FacialInput.exe` above for Windows 10/11 x64. It includes its Python runtime, GUI, tracking libraries, and face model; running it does not require Python installation, pip, a model download, or administrator access. The receiver and its one-time ViGEmBus prerequisite remain separate. Open one receiver, then the facial app; choose your camera, calibrate, use **Test (no output)**, and select **Start** when ready. It starts paused and preserves your existing settings. See the [facial-input guide](facial-input/README.md).

**Updating an existing installation:** save your changes and export your profiles first; wait for the verified-export message. Install a compatible APK over the existing app. If Android reports a signing error, keep the installed app and your data while resolving it. Uninstalling or clearing app data deletes profiles stored only inside the app. See [backup and updates](USER_GUIDE.md#export-backup-and-import).

# 3. Connect the Phone and PC

The receiver's Python/console window displays the address to use, including when you run the EXE:

```text
IP Address: 192.168.1.25
Port:       42734
```

The IP above is an example; use the address shown on **your** PC. The current receiver uses **UDP port 42734**.

- **Wi-Fi:** connect both devices to the same home network; a PC on Ethernet is fine. Tap **Connect** in the Android app, enter the displayed IP address and `42734`, choose Player 1 or Player 2, and connect. Leave ConsoleBridge/CBv0 disabled for this receiver.
- **USB:** connect the phone to the PC with a data cable. In the app's connection dialog, select **USB Tether Mode**, enable USB tethering in Android settings, and return to the app. This mode discovers the receiver on the tethered connection; allow about 12 seconds. It preserves the saved Wi-Fi address.

Wi-Fi connection uses the saved/manual address; automatic discovery is part of USB Tether Mode. You normally do not need to open Command Prompt or run `ipconfig`, because the receiver already shows its IP address.

Test a Button with payload `w` in Notepad, or `X360A` in a controller tester, before configuring a full game profile. Start the receiver before starting a game that only checks for controllers at launch.

# 4. Create Your Controls

Enter **Edit**, choose **Load → New Layout** for an empty profile, and tap **+** to add controls. Long-press an existing control to edit its properties; drag it to move it and adjust its size in properties. Select **Save**, then **Done** to play.

Some useful payloads:

| Payload | Action |
| --- | --- |
| `w` | W key |
| `shift,w` | Shift and W together |
| `X360A` | Xbox A button |
| `X360UP` | Xbox D-pad Up |
| `LT:1.0` | Full left trigger |
| `LS:R50` | Left stick, right at 50% |
| `LS:UR100,X360RB,X360A` | Diagonal stick movement plus RB and A |

For complete syntax, Hold/Turbo behavior, aiming, profile backups, floating controls and troubleshooting, open the **[User Guide](USER_GUIDE.md)**.

# 5. Downloads and Source

| Item | Location |
| --- | --- |
| Current Android app | [SimpleController.apk](SimpleController.apk) |
| Matching Windows receiver | [Simple Controller Receiver.exe](<Simple Controller Receiver.exe>) |
| Virtual controller driver | [ViGEmBus installer](ViGEmBus_1.22.0_x64_x86_arm64.exe) |
| Matching Android source and build files | [source/android](source/android/) |
| Matching receiver source and packaging files | [source/receiver](source/receiver/) |
| Standalone facial-input app | [FacialInput.exe](https://github.com/Colonelwheel/Simplecontroller/releases/download/facial-input-windows-2026-10-16/FacialInput.exe) |
| Facial-input setup, matching source and packaging recipe | [facial-input](facial-input/README.md) |
| VoiceAttack setup and controller outputs | [VoiceAttack guide](voiceattack/USER_GUIDE.md) |
| Branch roles, source layout, and release workflow | [Branches and compatibility](BRANCHES.md) |
| Implementation map, file responsibilities, and change/test entry points | [Architecture and source map](ARCHITECTURE.md) |

The Android implementation is preserved from floating-controls snapshot `1ea6ffa`, including Macro Mode and `(U...)` timed releases added in `9bb9187`. The supplied APK is the existing November 17, 2026 feature delivery, with the standalone Touch Sensor Test screen and extra launcher removed. TouchAim, calibration, floating controls, Delay, repeat timing, HRD, and stage tuning remain available. Dev's receiver now includes the hang diagnostics from the November 10 desktop build and is supplied as `Simple Controller Receiver.exe`. The diagnostic observer records bounded timing and stack-location data; core input logic is preserved. Main retains its previous receiver. See [diagnostic capture and retention](source/receiver/RECEIVER_TIMING_DIAGNOSTICS.md). See [payload timing](source/android/PAYLOAD_MODIFIERS_AND_STAGE_TUNING.md), [Macro Mode examples](source/android/MACRO_MODE_AND_PAYLOAD_PORTING.md), and [the source map](ARCHITECTURE.md).

Historical verification for the November 17, 2026 feature delivery passed 678 JVM tests per Android variant, APK/AAB packaging, and lint with zero errors. Establishing `main` and `dev` did not rebuild or install an app or receiver. The November 23 dev receiver update passed 294 mocked tests and static verification of all eight packaged Python modules and both ViGEm DLLs; the rebuilt EXE was not launched during verification. Compilation and package checks do not establish compatibility with every game, webcam or network.

SHA-256 checksums:

```text
SimpleController.apk
e0869e5b346c03f282a74d291c234783dee3c60eb18fbb3d62c5341bf2316544

Simple Controller Receiver.exe
10da167a2a085fabd0f59ecac0e36165682df728969f05a2fc88fac73d44da21

FacialInput.exe
4dd1edf58afda99789ee619696fac718f916e80375f61fe93c80643907efbedf
```

To build the Android source, install JDK 21 and Android SDK 36, open `source/android`, and run `gradlew.bat assembleSideloadDebug`. The Play bundle task is `bundlePlayRelease`; Play signing and upload are separate from this APK download. A local build uses your own signing setup.

The [receiver source guide](source/receiver/README.md) explains its entrypoint and packaging with separate build outputs. ViGEmBus must already be installed before running a receiver. Facial input has its own [setup and developer build instructions](facial-input/README.md#build-the-standalone-exe) and [third-party notices](facial-input/packaging/THIRD_PARTY_NOTICES.md).
