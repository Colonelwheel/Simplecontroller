@echo off
setlocal
cd /d "%~dp0"
set "OUTPUT_NAME=Simple Controller Receiver (Release All)"

echo Running receiver tests...
py -m unittest -q test_camera_follow.py test_receiver_release_all.py
if errorlevel 1 goto :failed

echo.
echo Building standalone receiver...
py -m PyInstaller --noconfirm --clean --onefile --console --name "%OUTPUT_NAME%" --add-binary "ViGEmClient\x64\ViGEmClient.dll;vgamepad\win\vigem\client\x64" --add-binary "ViGEmClient\x86\ViGEmClient.dll;vgamepad\win\vigem\client\x86" --workpath "%~dp0build" --distpath "%~dp0dist" --specpath "%~dp0" simple_controller_receiver.py
if errorlevel 1 goto :failed

copy /Y "%~dp0dist\%OUTPUT_NAME%.exe" "%~dp0%OUTPUT_NAME%.exe"
if errorlevel 1 goto :failed

echo.
echo Finished: %~dp0%OUTPUT_NAME%.exe
pause
exit /b 0

:failed
echo.
echo Build failed. Read the error above.
pause
exit /b 1
