@echo off
rem Starts the Budget sync helper. Keep this window open while you want the phone to sync.
set "JAVA=java"
if exist "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot\bin\java.exe" set "JAVA=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot\bin\java.exe"
"%JAVA%" -jar "%~dp0helper\build\libs\budget-sync-helper.jar" %*
pause
