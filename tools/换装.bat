@echo off
set "SC=%~dp0wardrobe_push.py"
for %%i in (pythonw.exe) do if not "%%~$PATH:i"=="" set "PY=%%~$PATH:i"
if not defined PY (
  echo [wardrobe] pythonw.exe not found in PATH.
  echo Install Python, or edit this .bat and set PY to your pythonw.exe.
  pause
  exit /b 1
)
if not exist "%SC%" (
  echo [wardrobe] script not found: %SC%
  pause
  exit /b 1
)
start "" "%PY%" "%SC%"
