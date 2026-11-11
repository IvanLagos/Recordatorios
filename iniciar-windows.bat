@echo off
rem App de Windows: ventana de Recordatorios + alerta siempre al frente.
rem Queda en segundo plano (bandeja, junto al reloj) aunque cierres la ventana.
set "APP=%~dp0windows"
set "EXE=%APP%\node_modules\electron\dist\electron.exe"
if not exist "%EXE%" (
  where node >nul 2>&1
  if errorlevel 1 (
    echo Necesitas instalar Node.js desde https://nodejs.org
    pause
    exit /b
  )
  echo Instalando por primera vez, espera un momento...
  pushd "%APP%"
  call npm install
  popd
)
rem Se abre sin ventana de consola, asi no se cierra por accidente
start "" "%EXE%" "%APP%"
