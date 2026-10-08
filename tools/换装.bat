@echo off
if not defined PY for %%i in (pythonw.exe) do set "PY=%%~$PATH:i"
set "SC=%~dp0wardrobe_push.py"
if not exist "%PY%" (
  echo [wardrobe] pythonw.exe not found: %PY%
  echo Edit this .bat and point PY at your python.
  pause
  exit /b 1
)
if not exist "%SC%" (
  echo [wardrobe] script not found: %SC%
  pause
  exit /b 1
)
start "" "%PY%" "%SC%"
