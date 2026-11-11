// Copia la app web (servidor/public) a la carpeta www que empaqueta Android
const fs = require('fs'), path = require('path');
const origen = path.join(__dirname, '..', 'servidor', 'public'), destino = path.join(__dirname, 'www');
fs.rmSync(destino, { recursive: true, force: true });
fs.cpSync(origen, destino, { recursive: true });
console.log('Web copiada a www');
