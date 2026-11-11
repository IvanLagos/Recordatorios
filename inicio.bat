@echo off
cd /d "%~dp0servidor"
where node >nul 2>&1
if errorlevel 1 (
  echo Necesitas instalar Node.js desde https://nodejs.org
  pause
  exit /b
)
if not exist node_modules call npm install
rem Inicia el servidor (minimizado) y abre la app
start "Recordatorios (servidor)" /min cmd /c "set PORT=8123&& node server.js"
timeout /t 2 /nobreak >nul
start "" "http://localhost:8123/"
