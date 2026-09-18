@echo off
setlocal
cd /d "%~dp0"
set "DPH_JAVA=java"
set "DPH_JAVAC=javac"
if defined JAVA_HOME (
  set "DPH_JAVA=%JAVA_HOME%\bin\java.exe"
  set "DPH_JAVAC=%JAVA_HOME%\bin\javac.exe"
)
if not exist "build\hud-preview" mkdir "build\hud-preview"
"%DPH_JAVAC%" -d "build\hud-preview" "src\main\java\dev\krister\dungeonprogresshud\HudGeometry.java" "tools\hud-preview\HudPreview.java"
if errorlevel 1 exit /b 1
"%DPH_JAVA%" -cp "build\hud-preview" HudPreview
