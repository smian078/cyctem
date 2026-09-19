@echo off
setlocal
set "GRADLE_VERSION=8.11.1"
set "DIST_DIR=%USERPROFILE%\.gradle\cystem-gradle\%GRADLE_VERSION%"
set "GRADLE_BIN=%DIST_DIR%\gradle-%GRADLE_VERSION%\bin\gradle.bat"
if not exist "%GRADLE_BIN%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force -Path '%DIST_DIR%' | Out-Null; Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%DIST_DIR%\gradle.zip'; Expand-Archive -Force '%DIST_DIR%\gradle.zip' '%DIST_DIR%'; Remove-Item '%DIST_DIR%\gradle.zip'"
)
call "%GRADLE_BIN%" %*
endlocal
