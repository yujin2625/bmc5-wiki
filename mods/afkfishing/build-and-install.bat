@echo off
rem Build the mod and copy it into the BMC5 instance's mods folder.
rem NeoForm recompile needs a full JDK 21. Override JAVA_HOME / BMC5_MODS_DIR if your paths differ.
setlocal
cd /d "%~dp0"
if not defined JAVA_HOME set "JAVA_HOME=%USERPROFILE%\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"
if not defined BMC5_MODS_DIR set "BMC5_MODS_DIR=%USERPROFILE%\curseforge\minecraft\Instances\Better MC [NEOFORGE] BMC5\mods"

call "%~dp0gradlew.bat" build || exit /b 1
copy /Y "%~dp0build\libs\afkfishing-*.jar" "%BMC5_MODS_DIR%\" || exit /b 1
echo Installed to %BMC5_MODS_DIR% - restart the game to load it.
