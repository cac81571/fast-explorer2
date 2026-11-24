@echo off
setlocal
cd /d "%~dp0"
java --add-opens java.base/sun.nio.fs=ALL-UNNAMED --add-exports java.base/sun.nio.fs=ALL-UNNAMED -jar "%~dp0target\fast-explorer.jar" %*
exit /b %ERRORLEVEL%
