SIMPLECONTROLLER RECEIVER EDIT/BUILD FOLDER

Edit the Camera Follow values in:
    simple_controller_receiver.py

The effective CAMERA_FOLLOW_SETTINGS block is near the top of that file.
camera_follow.py contains the Camera Follow state machine and is also required.

Before rebuilding, close the currently running receiver EXE. Then either
double-click "Rebuild SimpleController Receiver.bat" or run this in Command Prompt:

    cd /d "C:\Users\Tyler\OneDrive\Desktop\Edit exe Folder"
    "Rebuild SimpleController Receiver.bat"

The finished file will be:
    SimpleControllerReceiver-USB-Tether.exe

The batch file runs the Camera Follow tests first and stops if they fail. It
then packages the local x64/x86 ViGEmClient DLL copies into the standalone EXE.

Required software that remains installed on Windows:
    Python, PyInstaller, vgamepad, keyboard, mouse, pyautogui, and ViGEmBus.

This folder is a separate editable copy. Changes made here do not automatically
change the source under "New codex\Simplecontroller".
