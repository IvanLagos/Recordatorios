const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  // Desde la app web: pedir que se muestre la alerta al frente
  alerta: a => ipcRenderer.send('alerta', a),
  // Desde la app web: cerrar una alerta porque ya se atendió en otro aparato
  cancelarAlerta: id => ipcRenderer.send('alerta-cancelar', id),
  // Desde la app web: recibir lo que el usuario eligió en la alerta
  onAccion: cb => ipcRenderer.on('accion', (_e, a) => cb(a)),
  // Desde la ventana de alerta
  onLista: cb => ipcRenderer.on('lista', (_e, l) => cb(l)),
  enviarAccion: a => ipcRenderer.send('accion', a),
  alertaLista: () => ipcRenderer.send('alerta-lista'),
});
