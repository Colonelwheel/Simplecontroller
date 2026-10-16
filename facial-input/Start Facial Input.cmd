@echo off
setlocal
set "FACIAL_PROJECT=%~dp0"
set "FACIAL_PYTHON=%FACIAL_PROJECT%.venv\Scripts\pythonw.exe"
if not exist "%FACIAL_PYTHON%" (
    echo Facial Input has not been set up in this project's isolated .venv.
    echo Follow the README setup instructions. This launcher does not install packages or open a camera.
    pause
    exit /b 1
)
set "PYTHONPATH=%FACIAL_PROJECT%src"
set "PYTHONDONTWRITEBYTECODE=1"
start "Facial Input" /D "%FACIAL_PROJECT%" "%FACIAL_PYTHON%" -B -m facial_input %*
endlocal
