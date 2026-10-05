@echo off
rem apply keybindings (see README.md). Set BMC5_DIR if the instance is not in the default CurseForge path.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0keybinds.ps1" -Mode apply
pause
