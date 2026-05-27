@echo off
setlocal
set "REPO_DIR=%~dp0"
set "JDK21=D:\Code\pointcloud\sb api\jdk-21\jdk-21.0.10+7\bin\java.exe"

if exist "%JDK21%" (
  "%JDK21%" "%REPO_DIR%tools\hud-preview\HudPreview.java"
) else (
  java "%REPO_DIR%tools\hud-preview\HudPreview.java"
)
