# Simple Controller — Setup and User Guide

Simple Controller is an accessibility-focused Android controller app that lets you use a phone or tablet as a custom input device for a Windows PC. It is designed especially for people who need simple, touch-based, one-finger-friendly controls for PC games or desktop input.

The app works by sending input from the Android device to a companion receiver running on the Windows PC. The Windows receiver then turns those commands into keyboard, mouse, or virtual Xbox-controller input.

The basic setup is:

**Android phone/tablet → Wi-Fi → Windows receiver/server → PC game or desktop app**

This guide covers prerequisites, installation, first-time setup, creating profiles, adding buttons, button syntax, controller syntax, usage, and troubleshooting.

---

# 1. What Simple Controller Does

Simple Controller turns an Android phone or tablet into a customizable control surface for a Windows computer.

Depending on the profile and PC receiver setup, it can support:

* On-screen buttons
* Keyboard key presses
* Mouse movement
* Mouse clicks
* Left-stick style movement
* Right-stick style aiming/camera control
* Xbox-style controller buttons
* Xbox-style triggers
* Xbox-style analog sticks
* Hold/toggle buttons
* Turbo/repeating buttons
* Touchpad-style mouse movement
* Click lock / drag lock
* Custom profiles for different games or programs

Simple Controller has two main parts:

1. **The Android app**

   * This is the touch controller.
   * This is where you create profiles, add buttons, move controls, and assign inputs.

2. **The Windows receiver**

   * This runs on the PC.
   * It receives commands from the Android app.
   * It sends those commands to Windows as keyboard, mouse, or virtual Xbox-controller input.

The Android app does not directly control the PC by itself. The Windows receiver must be running at the same time.

---

# 2. Important Notes Before You Start

## This is a local-network app

Simple Controller is intended to work on a local connection between your Android device and Windows PC. Usually that means the same Wi-Fi/router. It can also work over Android USB tethering when Wi-Fi/router internet is down, as long as the Windows receiver is allowed through Firewall.

For best results:

* Use your home Wi-Fi network.
* Avoid public Wi-Fi.
* Avoid guest Wi-Fi networks, because they often block devices from seeing each other.
* Keep the phone/tablet close to the router or PC if possible.
* Use Ethernet for the PC if possible.

## The Windows receiver must stay open

The Android app sends input to the PC receiver. If the receiver/server is closed, the phone app may still open, but it will not control anything.

## ViGEm is required for virtual Xbox-controller mode

For Xbox-controller/X360 output, the **ViGEm driver is required**.

The ViGEm driver is included in the GitHub/download package. Install it before using controller mode.

Keyboard and mouse mode may work without ViGEm, but controller mode requires it.

## Some games may need to be restarted

Some PC games only detect controllers when the game starts. If controller mode is not detected, try closing and reopening the game after the receiver is already running.

## Antivirus and firewall warnings are possible

Because the PC receiver listens for input from your phone over the local network, Windows Firewall may ask whether to allow it. This is normal for local-network software.

Only allow access if you downloaded the app from the official source provided by the developer.

---

# 3. Requirements

## Required hardware

You need:

1. **A Windows PC**

   * Windows 10 or Windows 11 recommended.
   * The PC should be able to run the game or program you want to control.

2. **An Android phone or tablet**

   * A phone or tablet with a working touchscreen.
   * The device must be able to install the Simple Controller APK.

3. **A local network path**

   * The Android device and Windows PC should usually be on the same Wi-Fi network.
   * Android USB tethering can also provide a direct local path to the PC.

---

# 4. Required Software

Simple Controller usually has two parts:

1. **The Android app**

   * Installed on your phone or tablet.

2. **The Windows receiver/server**

   * Runs on your PC.
   * Receives the phone’s input and turns it into keyboard, mouse, or controller input.

Depending on the version you received, the Windows receiver may be provided as:

* A ready-to-run `.exe`
* A folder with a `.bat` file
* A Python script
* A packaged release folder

Use the version provided with your download.

---

# 5. Optional / Included Software for Controller Mode

For Xbox-style controller output, Simple Controller uses a virtual gamepad system.

## ViGEm driver

The **ViGEm driver is required for virtual Xbox-controller mode**.

It is included in the GitHub/download package.

Install ViGEm before trying to use any X360 controller commands such as:

* `X360A`
* `X360X`
* `RT:1.0`
* `LT:0.5`
* `STICK_L:0.0,-1.0`

If ViGEm is not installed, keyboard and mouse input may still work, but virtual Xbox-controller input will not work correctly.

---

# 6. Downloading the App

You should receive a download package from the developer.

A typical package may include:

* `SimpleController.apk`
* A Windows receiver folder
* The ViGEm driver installer
* A setup/readme file
* Optional example profiles
* Optional example layouts

Keep all of the downloaded files together in one folder on your PC.

Recommended folder location:

`Documents\SimpleController`

or

`Desktop\SimpleController`

Avoid putting it inside a protected Windows folder like:

`C:\Program Files`

unless the installer specifically tells you to.

---

# 7. Installing the Android App

## Step 1: Transfer or download the APK

On your Android phone/tablet, get the `SimpleController.apk` file.

You can do this by:

* Downloading it directly on the Android device
* Sending it to yourself
* Using Google Drive
* Using USB transfer
* Scanning a download link or QR code if provided

## Step 2: Open the APK

Tap the APK file.

Android may show a warning saying the app is from an unknown source. This is normal for apps installed outside the Google Play Store.

## Step 3: Allow installation from this source

Android may ask you to allow the browser, file manager, or Drive app to install unknown apps.

The wording may look like:

* “Install unknown apps”
* “Allow from this source”
* “For your security, your phone is not allowed to install unknown apps from this source”

Enable the permission for the app you are using to open the APK.

## Step 4: Install Simple Controller

After allowing installation, go back and tap the APK again if needed.

Tap:

**Install**

When installation is finished, tap:

**Open**

or find Simple Controller in your app drawer.

---

# 8. Installing the Windows Receiver

The exact steps depend on how the developer packaged the PC receiver.

## Option A: If you received a Windows `.exe`

1. Open the Simple Controller PC folder.
2. Double-click the receiver `.exe`.
3. If Windows warns you about running an unknown app, only continue if you trust the source.
4. When Windows Firewall asks for permission, allow it on your private/home network.
5. Leave the receiver window open.

## Option B: If you received a `.bat` file

1. Open the Simple Controller PC folder.
2. Double-click the `.bat` file, such as:

   * `run_server.bat`
   * `start_receiver.bat`
   * `SimpleControllerServer.bat`
3. A command window should open.
4. Do not close this window while using the app.
5. If Windows Firewall asks for permission, allow it on your private/home network.

## Option C: If you received Python source files

Only use this method if the release instructions say to.

1. Install Python for Windows.

2. During Python installation, check:

   * **Add Python to PATH**

3. Open the Simple Controller PC folder.

4. Install the required Python packages if a requirements file is provided:

   `pip install -r requirements.txt`

5. Start the receiver using the command provided by the developer. For the current Python receiver, use:

   `python simple_controller_receiver.py`

6. Leave the command window open while using Simple Controller.

---

# 9. Installing ViGEm for Controller Mode

If you want to use Xbox-controller input, install the included ViGEm driver.

## When you need ViGEm

You need ViGEm if you want to use commands like:

* `X360A`
* `X360B`
* `X360X`
* `X360Y`
* `X360LB`
* `X360RB`
* `LT:1.0`
* `RT:1.0`
* `STICK_L:0.0,-1.0`
* `STICK_R:1.0,0.0`

## How to install it

1. Open the GitHub/download package.
2. Find the included ViGEm installer.
3. Run the installer.
4. Follow the install prompts.
5. Restart the PC if Windows or the installer asks you to.
6. Start the Simple Controller receiver again.

If ViGEm is not installed, controller mode may silently fail or games may not see a controller.

---

# 10. Receiver Auto-Detection and IP Address

The Android app needs to know where to send input. Current versions first try the saved address, then auto-detect `simple_controller_receiver.py` on Wi-Fi or Android USB tethering. In many cases you can leave the IP field alone and just tap Connect.

## Easy method

Start `simple_controller_receiver.py` on Windows, allow Firewall access on private networks, then tap Connect in the Android app.

The receiver also displays the IP address and port it is listening on.

Look for something like:

`IP Address: 192.168.1.25`

or

`Port:       9001`

Use those values manually only if auto-detection does not connect.

## Manual method

If auto-detection does not work:

1. On Windows, press the Start button.

2. Type:

   `cmd`

3. Open Command Prompt.

4. Type:

   `ipconfig`

5. Press Enter.

6. Look for your active Wi-Fi or Ethernet adapter.

7. Find:

   `IPv4 Address`

It will usually look like:

`192.168.x.x`

or

`10.0.x.x`

Example:

`192.168.1.25`

That is the address to enter into the Simple Controller Android app. The current receiver port is `9001`.

---

# 11. First-Time Connection Setup

## On the Windows PC

1. Connect the PC to your Wi-Fi or Ethernet network.
2. Open `simple_controller_receiver.py` or the packaged Simple Controller receiver.
3. Allow Windows Firewall access if prompted.
4. Keep the receiver window open.
5. Note the IP address and port number shown by the receiver only if manual entry is needed.

## On the Android device

1. Connect the Android device to the same Wi-Fi network as the PC, or enable Android USB tethering to the PC.
2. Open Simple Controller.
3. Go to the connection/settings area.
4. Tap Connect.
5. If auto-detection does not work, enter the PC IP address and port `9001`.
6. Try pressing a button in the app.

If everything is working, you should see activity in the Windows receiver, or the PC should respond to the input.

---

# 12. Profiles and Layouts

Simple Controller uses profiles/layouts so you can make different control setups for different games or programs.

For example, you might create:

* A desktop mouse/keyboard profile
* A Diablo-style click/action profile
* A first-person shooter profile
* A controller/X360 profile
* A menu navigation profile
* A one-button/turbo accessibility profile
* A profile for a specific game

Each profile can have its own buttons, sticks, touchpads, labels, sizes, positions, and command payloads.

---

# 13. Creating a New Profile

The exact screen names may vary slightly by version, but the general process is:

1. Open Simple Controller on Android.
2. Open the profile/layout menu.
3. Choose something like:

   * **New Profile**
   * **Create Profile**
   * **Add Profile**
   * **Duplicate Profile**
4. Give the profile a name.

Good profile names are specific and easy to recognize.

Examples:

* `Desktop Mouse`
* `Diablo IV`
* `Hades`
* `FPS Basic`
* `Xbox Controller`
* `Menu Navigation`
* `Testing Profile`

After creating the profile, switch to it before adding buttons.

---

# 14. Editing a Profile

To change a profile, enter edit mode.

A typical editing flow is:

1. Open the profile you want to edit.
2. Turn on **Edit Mode**.
3. Use the add button, usually a `+` button, to add controls.
4. Tap a control’s gear/settings icon to edit it.
5. Drag controls to move them.
6. Resize controls if the version supports resizing.
7. Save the profile/layout.
8. Turn off Edit Mode when you are done.

Do not forget to save after editing.

---

# 15. Adding Buttons, Sticks, and Touchpads

In edit mode, you can add different control types.

Common control types include:

## Button

A button sends one or more commands when pressed.

Examples:

* A keyboard key
* A mouse click
* An Xbox button
* A trigger value
* Multiple commands at once

## Stick

A stick sends analog movement.

Examples:

* Left stick for movement
* Right stick for camera/aiming
* Digital direction pad behavior
* Snap-to-center movement

## Touchpad

A touchpad sends mouse-like movement or aiming movement.

Examples:

* Move the Windows mouse
* Control camera movement
* Drag/click with mouse buttons
* Use click lock or drag lock

---

# 16. Button Settings

When editing a button, you may see settings such as:

## Label

The text shown on the button.

Examples:

* `Jump`
* `Attack`
* `A`
* `Sprint`
* `Map`
* `Left Click`

The label is only what the user sees. The label does not have to match the command.

## Payload / Command

The payload is the actual command sent to the PC receiver.

Examples:

* `space`
* `shift`
* `X360A`
* `X360X`
* `RT:1.0`
* `w,shift`
* `X360LT:0.5,X360A`

## Width and height

Controls can be made larger or smaller.

For accessibility, important buttons should usually be larger.

## Hold / toggle / latch behavior

Some buttons can be set to hold while pressed, toggle on/off, or repeat/turbo.

## Sensitivity

Used mainly for sticks and touchpads.

## Auto-center / snap recenter

Used mainly for sticks. When enabled, the stick returns to center when released.

---

# 17. Payload Basics

The most important part of creating a button is the payload.

The payload tells the PC receiver what to press.

A payload can be:

* A keyboard key
* A mouse command
* A controller button
* A trigger value
* A stick value
* Multiple commands separated by commas

## One command

Example:

`space`

This sends the Space key.

## Multiple commands

Each button can send multiple commands.

Separate each command with a comma.

Example:

`shift,w`

This sends Shift and W together.

Another example:

`X360LT:1.0,X360RT:1.0`

This sends both Xbox triggers fully pressed.

Another example:

`X360LB,X360X`

This sends LB and X together.

Spaces after commas are okay if the app/receiver trims them, but the safest format is no spaces:

Correct:

`shift,w`

Also usually okay:

`shift, w`

Safest recommendation:

Use commas with no extra spaces.

---

# 18. Keyboard Syntax

Keyboard keys can usually be written as simple standalone text.

## Letters

Use the letter by itself.

Examples:

`w`

`a`

`s`

`d`

`e`

`q`

`r`

`f`

Letters may also work uppercase, but lowercase is usually easiest.

Examples:

`W`

`A`

`S`

`D`

## Numbers

Use the number by itself.

Examples:

`1`

`2`

`3`

`4`

`5`

`0`

## Common named keys

For keys that are not letters or numbers, write the key name as a word.

Examples:

`shift`

`ctrl`

`alt`

`space`

`enter`

`tab`

`esc`

`backspace`

`delete`

`up`

`down`

`left`

`right`

`home`

`end`

`pageup`

`pagedown`

## Function keys

Function keys are written like this:

`f1`

`f2`

`f3`

`f4`

`f5`

`f6`

`f7`

`f8`

`f9`

`f10`

`f11`

`f12`

## Modifier examples

Sprint forward:

`shift,w`

Crouch and move forward:

`ctrl,w`

Alt key:

`alt`

Open menu key:

`esc`

Jump:

`space`

Interact:

`e`

Reload:

`r`

Use item slot 1:

`1`

Use item slot 2:

`2`

---

# 19. Keyboard Hold and Release Syntax

Most normal buttons should handle press/release automatically based on how the app and receiver are configured.

For example, a button with:

`w`

may press W when your finger is down and release W when your finger lifts.

However, some advanced profiles may use explicit down/up commands.

Common explicit keyboard syntax:

`KEY_DOWN:shift`

`KEY_UP:shift`

`KEY_DOWN:w`

`KEY_UP:w`

Examples:

Hold Shift:

`KEY_DOWN:shift`

Release Shift:

`KEY_UP:shift`

Hold W:

`KEY_DOWN:w`

Release W:

`KEY_UP:w`

This is mainly useful for advanced layouts, special toggles, or macro-style profiles.

For most users, standalone key names like `w`, `space`, and `shift` are simpler.

---

# 20. Mouse Syntax

Mouse commands depend on the receiver version, but common mouse-style commands include:

## Mouse buttons

Left click:

`MOUSE_LEFT`

Right click:

`MOUSE_RIGHT`

Middle click:

`MOUSE_MIDDLE`

## Mouse button down/up

For explicit holds:

`MOUSE_LEFT_DOWN`

`MOUSE_LEFT_UP`

`MOUSE_RIGHT_DOWN`

`MOUSE_RIGHT_UP`

`MOUSE_MIDDLE_DOWN`

`MOUSE_MIDDLE_UP`

Examples:

Hold left mouse:

`MOUSE_LEFT_DOWN`

Release left mouse:

`MOUSE_LEFT_UP`

Hold right mouse:

`MOUSE_RIGHT_DOWN`

Release right mouse:

`MOUSE_RIGHT_UP`

## Mouse movement / delta

Mouse movement may use delta syntax.

Example:

`DELTA:10,0`

This means move the mouse right.

Example:

`DELTA:-10,0`

This means move the mouse left.

Example:

`DELTA:0,-10`

This means move the mouse up.

Example:

`DELTA:0,10`

This means move the mouse down.

Touchpad controls usually generate mouse delta automatically, so most users do not need to type `DELTA` manually.

---

# 21. Xbox / X360 Controller Syntax

For controller input, Simple Controller uses X360-style commands.

The ViGEm driver is required for these commands to work.

## Face buttons

Use these for Xbox-style face buttons:

`X360A`

`X360B`

`X360X`

`X360Y`

Examples:

Xbox A button:

`X360A`

Xbox B button:

`X360B`

Xbox X button:

`X360X`

Xbox Y button:

`X360Y`

## Shoulder buttons

Left bumper:

`X360LB`

Right bumper:

`X360RB`

Examples:

`X360LB`

`X360RB`

## Menu buttons

Back/View:

`X360BACK`

Start/Menu:

`X360START`

Guide/Home, if supported:

`X360GUIDE`

Examples:

`X360BACK`

`X360START`

`X360GUIDE`

## Stick clicks

Left stick click:

`X360LS`

Right stick click:

`X360RS`

Examples:

`X360LS`

`X360RS`

---

# 22. Controller Trigger Syntax

Triggers can be sent as analog values.

Use a value from `0.0` to `1.0`.

* `0.0` means not pressed.
* `0.5` means half pressed.
* `1.0` means fully pressed.

## Right trigger

Fully press right trigger:

`RT:1.0`

Half press right trigger:

`RT:0.5`

Release right trigger:

`RT:0.0`

## Left trigger

Fully press left trigger:

`LT:1.0`

Half press left trigger:

`LT:0.5`

Release left trigger:

`LT:0.0`

## X360 trigger names

Depending on the receiver version, trigger commands may also use X360-style names:

`X360RT:1.0`

`X360LT:1.0`

If both versions are supported by your receiver, these are equivalent in purpose:

`RT:1.0`

and

`X360RT:1.0`

Likewise:

`LT:1.0`

and

`X360LT:1.0`

## Trigger examples

Fire:

`RT:1.0`

Aim:

`LT:1.0`

Aim and fire:

`LT:1.0,RT:1.0`

Half-pull right trigger:

`RT:0.5`

Release both triggers:

`LT:0.0,RT:0.0`

---

# 23. Controller Stick Syntax

Controller sticks use X/Y values.

Values usually range from `-1.0` to `1.0`.

* `0.0,0.0` means centered.
* Negative X usually means left.
* Positive X usually means right.
* Negative Y usually means up/forward.
* Positive Y usually means down/back.

## Left stick

Left stick syntax:

`STICK_L:x,y`

Examples:

Move left stick forward:

`STICK_L:0.0,-1.0`

Move left stick backward:

`STICK_L:0.0,1.0`

Move left stick left:

`STICK_L:-1.0,0.0`

Move left stick right:

`STICK_L:1.0,0.0`

Center left stick:

`STICK_L:0.0,0.0`

Move diagonally forward-right:

`STICK_L:0.7,-0.7`

## Right stick

Right stick syntax:

`STICK_R:x,y`

Examples:

Look up:

`STICK_R:0.0,-1.0`

Look down:

`STICK_R:0.0,1.0`

Look left:

`STICK_R:-1.0,0.0`

Look right:

`STICK_R:1.0,0.0`

Center right stick:

`STICK_R:0.0,0.0`

Move diagonally up-right:

`STICK_R:0.7,-0.7`

## Alternate stick names

Some older or alternate receiver versions may use names like:

`STICK_LEFT:-1.0,0.0`

`STICK_RIGHT:1.0,0.0`

or:

`STICK_LEFT -1.0 0.0`

`STICK_RIGHT 1.0 0.0`

The recommended syntax for current profiles is:

`STICK_L:x,y`

`STICK_R:x,y`

---

# 24. D-Pad Syntax

If supported by the receiver, D-pad commands may look like this:

`DPAD:UP`

`DPAD:DOWN`

`DPAD:LEFT`

`DPAD:RIGHT`

`DPAD:NEUTRAL`

Some versions may also support:

`DPAD UP`

`DPAD DOWN`

`DPAD LEFT`

`DPAD RIGHT`

`DPAD NEUTRAL`

Recommended format:

`DPAD:UP`

Examples:

D-pad up:

`DPAD:UP`

D-pad right:

`DPAD:RIGHT`

Release/neutral D-pad:

`DPAD:NEUTRAL`

---

# 25. Controller Release Syntax

Most normal buttons should release automatically when you lift your finger.

For advanced layouts, explicit release commands may be supported.

Common release format:

`X360A_RELEASE`

`X360B_RELEASE`

`X360X_RELEASE`

`X360Y_RELEASE`

`X360LB_RELEASE`

`X360RB_RELEASE`

`X360BACK_RELEASE`

`X360START_RELEASE`

`X360LS_RELEASE`

`X360RS_RELEASE`

Examples:

Press X:

`X360X`

Release X:

`X360X_RELEASE`

Press A and B:

`X360A,X360B`

Release A and B:

`X360A_RELEASE,X360B_RELEASE`

For most users, you should not need to manually use release commands unless building a custom toggle, macro, or special profile.

---

# 26. Multiple Commands on One Button

Each button can send more than one command.

Separate commands with commas.

## Keyboard combo examples

Sprint forward:

`shift,w`

Crouch forward:

`ctrl,w`

Jump while moving forward:

`w,space`

Use item while holding shift:

`shift,e`

## Controller combo examples

Aim and fire:

`LT:1.0,RT:1.0`

Hold left bumper and press X:

`X360LB,X360X`

Press A and right trigger:

`X360A,RT:1.0`

Move forward and sprint/click left stick:

`STICK_L:0.0,-1.0,X360LS`

## Mixed keyboard/mouse examples

Move forward and left click:

`w,MOUSE_LEFT`

Hold shift and left click:

`shift,MOUSE_LEFT`

Right click and press E:

`MOUSE_RIGHT,e`

## Mixed keyboard/controller examples

These may work if the receiver supports both output types at once:

`w,X360A`

`space,X360B`

`shift,X360LS`

However, for most games, it is usually better to use either keyboard/mouse mode or controller mode, not both, unless you specifically need mixed input.

---

# 27. Macro / Delay Syntax

Some versions of the receiver may support simple wait/delay commands.

Common examples:

`WAIT:150`

`DELAY:150`

or:

`WAIT_150`

These mean wait about 150 milliseconds before the next command.

Example:

`X360A,WAIT:150,X360A`

This would press A, wait briefly, then press A again if macro support is enabled.

Another example:

`e,WAIT:200,space`

This would press E, wait briefly, then press Space.

Macro support depends on the receiver version. If a delay command does nothing, your receiver may not support macros yet.

---

# 28. Payload Syntax Quick Reference

## Keyboard

Letters:

`w`

`a`

`s`

`d`

Numbers:

`1`

`2`

`3`

Named keys:

`shift`

`ctrl`

`alt`

`space`

`enter`

`tab`

`esc`

`backspace`

`delete`

Arrow keys:

`up`

`down`

`left`

`right`

Function keys:

`f1`

`f2`

`f3`

through

`f12`

## Keyboard explicit down/up

`KEY_DOWN:shift`

`KEY_UP:shift`

`KEY_DOWN:w`

`KEY_UP:w`

## Mouse

`MOUSE_LEFT`

`MOUSE_RIGHT`

`MOUSE_MIDDLE`

`MOUSE_LEFT_DOWN`

`MOUSE_LEFT_UP`

`MOUSE_RIGHT_DOWN`

`MOUSE_RIGHT_UP`

`DELTA:10,0`

`DELTA:-10,0`

`DELTA:0,10`

`DELTA:0,-10`

## Xbox buttons

`X360A`

`X360B`

`X360X`

`X360Y`

`X360LB`

`X360RB`

`X360BACK`

`X360START`

`X360GUIDE`

`X360LS`

`X360RS`

## Xbox triggers

`LT:0.0`

`LT:0.5`

`LT:1.0`

`RT:0.0`

`RT:0.5`

`RT:1.0`

Optional X360-style trigger names if supported:

`X360LT:1.0`

`X360RT:1.0`

## Xbox sticks

`STICK_L:0.0,-1.0`

`STICK_L:0.0,1.0`

`STICK_L:-1.0,0.0`

`STICK_L:1.0,0.0`

`STICK_L:0.0,0.0`

`STICK_R:0.0,-1.0`

`STICK_R:0.0,1.0`

`STICK_R:-1.0,0.0`

`STICK_R:1.0,0.0`

`STICK_R:0.0,0.0`

## D-pad

`DPAD:UP`

`DPAD:DOWN`

`DPAD:LEFT`

`DPAD:RIGHT`

`DPAD:NEUTRAL`

## Multiple commands

Separate with commas:

`shift,w`

`X360LB,X360X`

`LT:1.0,RT:1.0`

`w,MOUSE_LEFT`

---

# 29. Example Button Setups

## Basic WASD keyboard movement

Forward:

Label:

`W`

Payload:

`w`

Left:

Label:

`A`

Payload:

`a`

Back:

Label:

`S`

Payload:

`s`

Right:

Label:

`D`

Payload:

`d`

Jump:

Label:

`Jump`

Payload:

`space`

Sprint:

Label:

`Sprint`

Payload:

`shift`

Interact:

Label:

`Use`

Payload:

`e`

---

## Basic mouse profile

Left click:

Label:

`Left Click`

Payload:

`MOUSE_LEFT`

Right click:

Label:

`Right Click`

Payload:

`MOUSE_RIGHT`

Middle click:

Label:

`Middle`

Payload:

`MOUSE_MIDDLE`

Drag lock, if using explicit commands:

Label:

`Hold Left`

Payload:

`MOUSE_LEFT_DOWN`

Label:

`Release Left`

Payload:

`MOUSE_LEFT_UP`

---

## Basic Xbox controller profile

A button:

Label:

`A`

Payload:

`X360A`

B button:

Label:

`B`

Payload:

`X360B`

X button:

Label:

`X`

Payload:

`X360X`

Y button:

Label:

`Y`

Payload:

`X360Y`

Left bumper:

Label:

`LB`

Payload:

`X360LB`

Right bumper:

Label:

`RB`

Payload:

`X360RB`

Left trigger:

Label:

`LT`

Payload:

`LT:1.0`

Right trigger:

Label:

`RT`

Payload:

`RT:1.0`

Start/Menu:

Label:

`Start`

Payload:

`X360START`

Back/View:

Label:

`Back`

Payload:

`X360BACK`

---

## Aim and fire button

Label:

`Aim + Fire`

Payload:

`LT:1.0,RT:1.0`

---

## Sprint forward button

Keyboard version:

Label:

`Sprint Forward`

Payload:

`shift,w`

Controller version:

Label:

`Sprint Forward`

Payload:

`STICK_L:0.0,-1.0,X360LS`

---

## Use/interact combo

Label:

`Use + Jump`

Payload:

`e,space`

---

# 30. Basic Usage

Once connected, Simple Controller works like a touch controller.

Depending on your profile, you may see:

* Directional controls
* Stick areas
* Action buttons
* Mouse/touchpad area
* Keyboard buttons
* Toggle buttons
* Turbo buttons
* Mode switches

## Pressing buttons

Tap a button to send its assigned input.

For example:

* Tap `W` to move forward in a game.
* Tap `Space` to jump.
* Tap `Left Click` to click the mouse.
* Tap `X360A` to send the Xbox A button if controller mode is enabled.

## Holding buttons

Some buttons can be held by keeping your finger down.

For example:

* Hold a movement button to keep moving.
* Hold an aim button to stay aiming.
* Hold a sprint button to keep sprinting.

## Toggle or latch buttons

Some layouts may include hold/latch buttons.

A latch button means:

* Tap once to turn the input on.
* Tap again to turn the input off.

This can be useful for people who cannot comfortably hold a button down for a long time.

Examples:

* Toggle sprint
* Toggle aim
* Toggle crouch
* Toggle walk
* Toggle mouse drag
* Toggle left trigger
* Toggle right trigger

## Turbo buttons

Turbo means the app repeats an input automatically while active.

This can be useful for:

* Repeated attacks
* Rapid clicking
* Repeated interaction
* Games where a button has to be pressed many times

Use turbo carefully. Some games may not like extremely fast repeated input.

---

# 31. Stick Controls

Simple Controller may include left-stick and right-stick controls.

## Left stick

The left stick is usually used for movement.

Typical uses:

* Move character
* Navigate menus
* Walk/run direction

## Right stick

The right stick is usually used for camera or aiming.

Typical uses:

* Look around
* Aim
* Move camera
* Rotate view

## Snap recenter

Some stick modes may snap back to center automatically when you release your finger.

This is useful because it prevents the character or camera from drifting after you stop touching the screen.

## Stick modes

Depending on the version and layout, there may be different stick modes.

Common examples:

### Analog mode

Acts more like a real joystick. Moving farther from the center sends a stronger stick movement.

Good for:

* Games that need smooth walking or aiming
* Controller-style movement
* Camera control

### Threshold mode

Acts more like digital direction buttons. Once you move far enough in a direction, the app sends that direction.

Good for:

* Simple movement
* Menu navigation
* Games that do not need fine analog control

### Stick+ mode

A combined or enhanced stick mode may be available depending on the layout.

Use this if the provided profile recommends it for a specific game.

---

# 32. Touchpad / Mouse Usage

Some layouts include a touchpad area.

The touchpad can be used to move the PC mouse or aim in games that use mouse movement.

## Moving the mouse

Drag your finger across the touchpad area to move the mouse.

## Clicking

A layout may include:

* Left click
* Right click
* Middle click
* Double click
* Click lock

## Click lock / drag lock

Click lock lets you keep the mouse button held down without physically holding your finger down.

This can be useful for:

* Dragging windows
* Holding aim
* Holding attack
* Dragging items in games
* Inventory management
* Accessibility situations where holding is difficult

A common pattern is:

* Double tap to lock
* Double tap again to unlock

The exact behavior depends on the layout/version you are using.

---

# 33. Gamepad Mode

If the PC receiver supports virtual controller output, Simple Controller can appear to the game as an Xbox-style controller.

This is useful for games that work better with controller input than keyboard/mouse input.

## Before using gamepad mode

Make sure:

1. The PC receiver is running.
2. The included ViGEm driver is installed.
3. The game is opened after the receiver is running.
4. The game supports controller input.
5. The game is set to controller mode if it has a setting for that.

## Testing gamepad mode

A good test is:

1. Install ViGEm.
2. Start the Simple Controller receiver.
3. Open Simple Controller on Android.
4. Connect to the PC.
5. Open a game that supports Xbox controllers.
6. Press a basic button such as A/B/X/Y.
7. Move the stick area.

If the game does not respond, close and reopen the game while keeping the receiver open.

---

# 34. Keyboard and Mouse Mode

If using keyboard/mouse output, the receiver sends PC keyboard or mouse commands.

This can be useful for:

* PC games
* Browser games
* Desktop control
* Launchers
* Menus
* Emulators
* Accessibility workflows

Make sure the game or program window is active. Click into the game window first if needed.

Some games block simulated input depending on how they are built. If one game does not work, test in Notepad or on the desktop first to confirm the receiver is working.

---

# 35. Recommended First Test

Before trying a full game, test the basic connection.

## Keyboard test

1. Open Notepad on the PC.
2. Start the Simple Controller receiver.
3. Open the Android app.
4. Connect to the PC.
5. Tap a keyboard button in the app.
6. See if text appears in Notepad.

## Mouse test

1. Start the Simple Controller receiver.
2. Open the Android app.
3. Connect to the PC.
4. Use the touchpad area.
5. See if the mouse pointer moves.

## Gamepad test

1. Install ViGEm.
2. Start the Simple Controller receiver.
3. Open a controller-compatible game.
4. Connect from the Android app.
5. Try the A/B/X/Y buttons or stick controls.

Testing outside a game first makes troubleshooting much easier.

---

# 36. Suggested Setup Order

For the smoothest first-time setup, do it in this order:

1. Download the Simple Controller package.
2. Install the included ViGEm driver if using controller mode.
3. Install or open the Windows receiver.
4. Allow Windows Firewall access.
5. Install the Android APK.
6. Connect the Android device to the same Wi-Fi, or use Android USB tethering.
7. Tap Connect and let the app auto-detect the receiver.
8. If auto-detection fails, enter the PC IP address and port `9001`.
9. Test in Notepad or on the desktop.
10. Test in the game.
11. Create or adjust a profile.
12. Add buttons/sticks/touchpads.
13. Adjust layout, sensitivity, or modes as needed.

---

# 37. Using Simple Controller With Games

## Step 1: Start the receiver first

Always open the PC receiver before opening the game, especially if you plan to use controller mode.

## Step 2: Open the game

Launch the game normally.

## Step 3: Make sure the game window is focused

Click the game window or use Alt+Tab to make sure the game is active.

## Step 4: Open the Android app

Connect to the PC.

## Step 5: Test simple inputs

Try:

* Movement
* Jump/interact
* Mouse/camera
* Menu buttons
* Attack/action buttons

## Step 6: Adjust settings

If movement is too fast, too slow, or not responding correctly, check:

* Stick sensitivity
* Touchpad sensitivity
* Mouse sensitivity in the game
* Controller settings in the game
* Whether the game is using keyboard/mouse or controller mode
* Whether ViGEm is installed for controller mode

---

# 38. Accessibility Tips

Simple Controller is meant to be flexible, especially for users who cannot use a standard controller or keyboard.

Useful accessibility strategies include:

## Use toggle buttons instead of holds

If holding a button is tiring or impossible, use latch/toggle buttons for actions like:

* Sprint
* Aim
* Crouch
* Walk
* Attack
* Drag
* Modifier keys
* Left trigger
* Right trigger

## Use larger buttons

If the layout allows customization, make the most important buttons large and easy to hit.

Good candidates for larger buttons:

* Attack
* Jump
* Interact
* Dodge
* Pause/menu
* Sprint toggle
* Left click
* Right click

## Put common actions near your easiest finger position

For one-finger use, place the most important controls where your finger naturally rests.

## Use multiple commands to reduce effort

A single button can send more than one command.

Examples:

`shift,w`

`LT:1.0,RT:1.0`

`X360LB,X360X`

This can reduce the need to press multiple buttons at once.

## Use turbo only when helpful

Turbo can reduce repeated tapping, but it may make some actions harder to control.

## Use touchpad lock for dragging

If dragging is physically difficult, click lock or drag lock can make desktop and inventory management much easier.

## Test one feature at a time

Do not try to configure everything at once. First get basic movement working, then add camera, then attacks, then advanced toggles.

---

# 39. Troubleshooting

## The Android app opens, but nothing happens on the PC

Check:

* Is the Windows receiver open?
* Are the phone and PC on the same Wi-Fi, or connected with Android USB tethering?
* If manual mode is needed, did you enter the correct PC IP address?
* Did Windows Firewall block the receiver?
* Is the port number correct?
* Is the game or target window active?
* Are you using a guest Wi-Fi network?

Try testing in Notepad first.

---

## The app says it is connected, but the game does not respond

Possible causes:

* The game window is not focused.
* The game does not accept that input type.
* The game is expecting controller input but the app is sending keyboard input.
* The game is expecting keyboard input but the app is sending controller input.
* The game only detects controllers at startup.
* ViGEm is not installed.
* The virtual controller is not active.

Try:

1. Start the receiver.
2. Open the game after the receiver is already running.
3. Check the game’s input settings.
4. Test in another game or in Notepad.

---

## Controller mode does not work

Check:

* Was the included ViGEm driver installed?
* Was the PC restarted after installing the driver?
* Was the receiver opened before the game?
* Does the game support Xbox controllers?
* Is the game set to controller input?
* Is another controller interfering?

Try unplugging other controllers and restarting the receiver.

---

## Keyboard input works in Notepad but not in the game

The game may block simulated input, require administrator permissions, or use a different input system.

Try:

* Running the receiver as administrator.
* Running the game normally, not as administrator.
* Running both the receiver and the game as administrator.
* Switching to controller mode if available.
* Checking the game’s keybind settings.

Only run programs as administrator if you trust them.

---

## Mouse movement is too fast or too slow

Adjust:

* Simple Controller touchpad sensitivity
* Windows mouse speed
* In-game mouse sensitivity
* In-game camera sensitivity
* Stick/camera sensitivity if using joystick mode

Start with small changes.

---

## Input feels laggy

Try:

* Move the phone closer to the router.
* Move the PC closer to the router.
* Use Ethernet for the PC if possible.
* Avoid public or crowded Wi-Fi.
* Close downloads or streaming apps.
* Make sure the phone is not in battery saver mode.
* Make sure the PC is not overloaded.
* Use a 5 GHz Wi-Fi network if available.

---

## The PC IP address changed

Local IP addresses can change after restarting your router, PC, or Wi-Fi.

If the app suddenly stops connecting:

1. Start `simple_controller_receiver.py`.
2. Tap Connect again so the app can auto-detect the receiver.
3. If auto-detection fails, check the PC IP address and enter it manually with port `9001`.

---

## Windows Firewall blocked the receiver

If you clicked “Cancel” or “Block” by mistake:

1. Open Windows Security.
2. Go to Firewall & network protection.
3. Choose “Allow an app through firewall.”
4. Find the Simple Controller receiver.
5. Allow it on private networks.

Alternatively, delete the old firewall rule and reopen the receiver so Windows asks again.

---

## The phone and PC are on the same Wi-Fi but still cannot connect

Some routers isolate devices from each other.

Check:

* Are you on a guest network?
* Is “AP isolation” or “client isolation” enabled on the router?
* Is the PC connected to a VPN?
* Is the phone connected to a VPN?
* Is the PC on Ethernet while the phone is on Wi-Fi? This is usually okay, but some routers separate them.

Try temporarily disabling VPNs and using the main home Wi-Fi network. If router internet is down or the router is isolating devices, enable Android USB tethering and tap Connect again so the app can discover the receiver on the tethered link.

---

# 40. Safety and Privacy

Simple Controller is designed for local control between your Android device and your PC.

General safety advice:

* Only download the app from the official source.
* Do not install APKs from people you do not trust.
* Do not open firewall access on public networks.
* Do not share your PC IP address publicly.
* Do not leave the receiver running when you are not using it if you are concerned about local-network access.
* Keep the app and receiver updated when new versions are released.

The app should not need your passwords. Never enter account passwords into an unofficial controller app unless you fully understand why it is asking.

---

# 41. Updating Simple Controller

To update the Android app:

1. Download the new APK.
2. Open it on the Android device.
3. Install it over the old version.
4. Keep your settings if Android allows it.
5. Reopen the app and test the connection.

To update the Windows receiver:

1. Close the old receiver.
2. Download the new receiver package.
3. Extract it to a new folder or replace the old files as instructed.
4. Start the new receiver.
5. Allow firewall access again if prompted.
6. Test with the Android app.

If something breaks after updating, try restarting both the phone and the PC.

---

# 42. Uninstalling

## Uninstall the Android app

1. Find Simple Controller on your Android device.
2. Long-press the app icon.
3. Tap App info.
4. Tap Uninstall.

## Remove the Windows receiver

If the receiver is just a folder, close it and delete the folder.

If you installed ViGEm, remove it from Windows only if you no longer need it for Simple Controller or any other program that uses virtual controller support.

---

# 43. Basic Support Checklist

If you need help, provide the following information:

1. Windows version
2. Android device model
3. Android version
4. Simple Controller app version
5. Receiver/server version
6. Whether ViGEm is installed
7. Whether you are using keyboard/mouse mode or controller mode
8. The game or program you are trying to control
9. Whether Notepad testing works
10. Whether mouse movement works
11. Any error message shown by the PC receiver
12. Whether Windows Firewall asked for permission
13. Whether the phone and PC are on the same Wi-Fi network or Android USB tethering
14. The exact payload/command you typed into the button

This information makes it much easier to figure out what is wrong.

---

# 44. Quick Start Summary

For most users, the setup is:

1. Install the Simple Controller APK on Android.
2. Install the included ViGEm driver if you want controller mode.
3. Start the Simple Controller receiver on Windows.
4. Allow Windows Firewall access on your private network.
5. Make sure the phone and PC are on the same Wi-Fi, or connect them with Android USB tethering.
6. Tap Connect and let the app auto-detect the receiver.
7. Enter the PC IP address and port `9001` only if auto-detection fails.
8. Test in Notepad or on the desktop.
9. Create or choose a profile.
10. Add buttons/sticks/touchpads.
11. Enter payloads such as `w`, `space`, `X360A`, or `RT:1.0`.
12. Open your game.
13. Adjust controls and sensitivity as needed.

Simple Controller must have both parts running: the Android app and the Windows receiver.

---

# 45. Recommended First Message to New Users

Use this when sharing the app with someone:

“Simple Controller has two parts: the Android app and the Windows receiver. Install the APK on your Android device, run the receiver on your Windows PC, make sure both devices are on the same Wi-Fi or connected with Android USB tethering, then tap Connect so the app can auto-detect the receiver. If you want Xbox-controller input, install the included ViGEm driver from the GitHub/download package. Start by testing a simple keyboard button like `w` or `space` in Notepad before trying a game. Buttons can send one command or multiple commands separated by commas, like `shift,w` or `LT:1.0,RT:1.0`.”

---

# 46. What to Try First

The easiest first test is:

1. Open the Windows receiver.

2. Open Notepad.

3. Open Simple Controller on Android.

4. Connect to the PC.

5. Create or open a test profile.

6. Add a button.

7. Set the button label to:

   `W`

8. Set the payload to:

   `w`

9. Press the button.

If `w` appears in Notepad, the basic connection is working.

After that, test mouse movement or controller mode depending on what you want to use.

---

# 47. Known Limitations

Simple Controller may not work perfectly with every game or every network setup.

Possible limitations:

* Some games block simulated keyboard or mouse input.
* Some games only detect controllers when launched.
* Public or guest Wi-Fi may block the phone from reaching the PC.
* Local IP addresses can change.
* Controller mode requires the included ViGEm driver.
* Very fast turbo input may not behave correctly in every game.
* Touchscreen controls may need sensitivity adjustment for each user.
* Some antivirus programs may warn about local-network receiver apps.
* Some advanced syntax may depend on the receiver version.

These are normal issues for this type of accessibility/control software and can usually be worked around.

---

# 48. Best Practices

For the best experience:

* Start the PC receiver before the game.
* Install ViGEm before using controller mode.
* Use the same Wi-Fi network for the phone and PC, or Android USB tethering when Wi-Fi/router internet is down.
* Test in Notepad before troubleshooting a game.
* Use Ethernet for the PC if possible.
* Keep the phone plugged in during long sessions.
* Disable battery saver on the phone if input becomes inconsistent.
* Use toggle buttons for actions that are hard to hold.
* Use comma-separated payloads for multi-button actions.
* Save working profiles.
* Change one setting at a time when troubleshooting.

---

# 49. Final Reminder

Simple Controller is meant to make PC control more flexible and accessible. It may take a little setup the first time, but once the Android app and Windows receiver are connected, it can reduce the need for a standard keyboard, mouse, or controller.

If something does not work, test in this order:

1. Is the receiver open?
2. Are both devices on the same network?
3. Is the IP address correct?
4. Did Windows Firewall allow the receiver?
5. Does input work in Notepad?
6. Does mouse input work on the desktop?
7. Is ViGEm installed for controller mode?
8. Does the game support the input mode you are using?
9. Is the button payload written correctly?
10. Are multiple commands separated by commas?

Start simple, confirm the connection works, then customize from there.
