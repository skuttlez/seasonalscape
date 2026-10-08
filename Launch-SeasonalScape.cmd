@echo off
setlocal
cd /d "%~dp0"
for /d %%J in ("%~dp0.tooling\jdk\*") do if exist "%%~fJ\bin\java.exe" set "JAVA_HOME=%%~fJ"
if not defined JAVA_HOME (
    echo Java 17 or newer is needed. Set JAVA_HOME to your JDK folder.
    pause
    exit /b 1
)
set "GRADLE_USER_HOME=%~dp0.tooling\gradle-cache"
call "%~dp0gradlew.bat" --no-daemon run
if errorlevel 1 pause
endlocal
