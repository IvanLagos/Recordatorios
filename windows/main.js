const { app, BrowserWindow, Tray, Menu, ipcMain, nativeImage, screen } = require('electron');
const path = require('path');
const fs = require('fs');

if (!app.requestSingleInstanceLock()) { app.quit(); return; }

let cfg = { url: 'http://localhost:8123' };
try { cfg = { ...cfg, ...JSON.parse(fs.readFileSync(path.join(__dirname, 'config.json'), 'utf8')) }; } catch (e) {}

const icon = nativeImage.createFromPath(path.join(__dirname, 'icon.png'));
let win = null, tray = null, ventanaAlerta = null, saliendo = false;
const cola = []; // alertas pendientes de atender

function crearVentana(oculta) {
  win = new BrowserWindow({
    width: 520, height: 840, icon, show: !oculta, autoHideMenuBar: true, title: 'Recordatorios',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, backgroundThrottling: false },
  });
  const cargar = () => win && !win.isDestroyed() && win.loadURL(cfg.url);
  win.webContents.on('did-fail-load', () => setTimeout(cargar, 5000)); // reintenta si el servidor no responde
  cargar();
  // Al cerrar, se queda en la bandeja para seguir avisando
  win.on('close', e => { if (!saliendo) { e.preventDefault(); win.hide(); } });
}

function mostrarPrincipal() { if (!win) return; win.show(); win.focus(); }

function iniciaConWindows() { return app.getLoginItemSettings({ args: [__dirname, '--oculto'] }).openAtLogin; }
function fijarInicio(v) { app.setLoginItemSettings({ openAtLogin: v, path: process.execPath, args: [__dirname, '--oculto'] }); }

function crearBandeja() {
  tray = new Tray(icon.resize({ width: 24, height: 24 }));
  tray.setToolTip('Recordatorios');
  const menu = () => Menu.buildFromTemplate([
    { label: 'Abrir Recordatorios', click: mostrarPrincipal },
    { label: 'Iniciar con Windows', type: 'checkbox', checked: iniciaConWindows(), click: m => fijarInicio(m.checked) },
    { type: 'separator' },
    { label: 'Salir (dejar de avisar)', click: () => { saliendo = true; app.quit(); } },
  ]);
  tray.on('click', mostrarPrincipal);
  tray.on('right-click', () => tray.popUpContextMenu(menu()));
}

// ---------- Alerta siempre al frente ----------
function abrirAlerta() {
  if (ventanaAlerta && !ventanaAlerta.isDestroyed()) { ventanaAlerta.webContents.send('lista', cola); traerAlFrente(); return; }
  const { width, height } = screen.getPrimaryDisplay().workAreaSize;
  const w = 600, h = 400;
  ventanaAlerta = new BrowserWindow({
    width: w, height: h, x: Math.round((width - w) / 2), y: Math.round((height - h) / 2),
    frame: false, resizable: false, minimizable: false, maximizable: false, alwaysOnTop: true,
    skipTaskbar: false, show: false, icon, title: 'Recordatorio',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, backgroundThrottling: false },
  });
  ventanaAlerta.loadFile(path.join(__dirname, 'alerta.html'));
  ventanaAlerta.once('ready-to-show', () => { traerAlFrente(); });
  ventanaAlerta.on('closed', () => { ventanaAlerta = null; });
}
function traerAlFrente() {
  if (!ventanaAlerta || ventanaAlerta.isDestroyed()) return;
  ventanaAlerta.setAlwaysOnTop(true, 'screen-saver'); // por encima incluso de pantalla completa
  ventanaAlerta.show(); ventanaAlerta.focus(); ventanaAlerta.moveTop();
}

ipcMain.on('alerta', (_e, a) => {
  if (!a || !a.id) return;
  const i = cola.findIndex(x => x.id === a.id);
  if (i >= 0) cola[i] = a; else cola.push(a);
  abrirAlerta();
});
ipcMain.on('alerta-lista', e => e.sender.send('lista', cola));
ipcMain.on('alerta-cancelar', (_e, id) => {
  const i = cola.findIndex(x => x.id === id);
  if (i < 0) return;
  cola.splice(i, 1);
  if (!ventanaAlerta || ventanaAlerta.isDestroyed()) return;
  if (!cola.length) ventanaAlerta.close(); else ventanaAlerta.webContents.send('lista', cola);
});
ipcMain.on('accion', (_e, a) => {
  if (!a || !a.id) return;
  const i = cola.findIndex(x => x.id === a.id);
  if (i >= 0) cola.splice(i, 1);
  if (a.tipo !== 'cerrar' && win && !win.isDestroyed()) win.webContents.send('accion', a);
  if (!cola.length) { if (ventanaAlerta && !ventanaAlerta.isDestroyed()) ventanaAlerta.close(); }
  else if (ventanaAlerta && !ventanaAlerta.isDestroyed()) ventanaAlerta.webContents.send('lista', cola);
});

app.on('second-instance', mostrarPrincipal);
app.whenReady().then(() => {
  // La primera vez se activa "Iniciar con Windows" para que siga avisando tras reiniciar el PC
  // (se puede desactivar con clic derecho en el ícono de la bandeja)
  const marca = path.join(app.getPath('userData'), 'inicio-automatico.txt');
  if (!fs.existsSync(marca)) { fijarInicio(true); try { fs.writeFileSync(marca, '1'); } catch (e) {} }
  crearVentana(process.argv.includes('--oculto'));
  crearBandeja();
});
app.on('window-all-closed', e => e.preventDefault()); // sigue viva en la bandeja
