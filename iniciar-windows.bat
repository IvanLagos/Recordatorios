@echo off
rem App de Windows: ventana de Recordatorios + alerta siempre al frente.
rem Primero hay que tener el servidor funcionando (inicio.bat) o la direccion de Render en windows\config.json
cd /d "%~dp0windows"
where node >nul 2>&1
if errorlevel 1 (
  echo Necesitas instalar Node.js desde https://nodejs.org
  pause
  exit /b
)
if not exist node_modules call npm install
start "" /b cmd /c "npx electron ."
