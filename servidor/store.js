// Almacenamiento: PostgreSQL si existe DATABASE_URL (producción en Render),
// o un archivo JSON local (pruebas en el PC).
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

async function crearPg() {
  const { Pool } = require('pg');
  const pool = new Pool({
    connectionString: process.env.DATABASE_URL,
    ssl: process.env.DATABASE_SSL === '0' ? false : { rejectUnauthorized: false },
  });
  await pool.query(`CREATE TABLE IF NOT EXISTS usuarios (
    id SERIAL PRIMARY KEY, email TEXT UNIQUE NOT NULL, hash TEXT NOT NULL)`);
  await pool.query(`CREATE TABLE IF NOT EXISTS recordatorios (
    id TEXT NOT NULL, usuario_id INT NOT NULL, datos JSONB NOT NULL,
    actualizado BIGINT NOT NULL, PRIMARY KEY (id, usuario_id))`);
  await pool.query(`CREATE TABLE IF NOT EXISTS config (clave TEXT PRIMARY KEY, valor TEXT NOT NULL)`);
  return {
    async secreto() {
      const r = await pool.query("SELECT valor FROM config WHERE clave='jwt'");
      if (r.rows[0]) return r.rows[0].valor;
      const s = crypto.randomBytes(32).toString('hex');
      await pool.query("INSERT INTO config VALUES ('jwt',$1)", [s]);
      return s;
    },
    async contarUsuarios() { return +(await pool.query('SELECT COUNT(*) FROM usuarios')).rows[0].count; },
    async buscarUsuario(email) { return (await pool.query('SELECT * FROM usuarios WHERE email=$1', [email])).rows[0]; },
    async crearUsuario(email, hash) {
      return (await pool.query('INSERT INTO usuarios(email,hash) VALUES($1,$2) RETURNING *', [email, hash])).rows[0];
    },
    async guardar(uid, item) {
      await pool.query(`INSERT INTO recordatorios(id,usuario_id,datos,actualizado) VALUES($1,$2,$3,$4)
        ON CONFLICT (id,usuario_id) DO UPDATE SET datos=$3, actualizado=$4`, [item.id, uid, item, item.updatedAt]);
    },
    async listar(uid, desde) {
      const r = await pool.query('SELECT datos FROM recordatorios WHERE usuario_id=$1 AND actualizado>$2', [uid, desde]);
      return r.rows.map(x => x.datos);
    },
    async obtener(uid, id) {
      const r = await pool.query('SELECT datos FROM recordatorios WHERE id=$1 AND usuario_id=$2', [id, uid]);
      return r.rows[0] ? r.rows[0].datos : null;
    },
    async pendientes() {
      const r = await pool.query(`SELECT usuario_id, datos FROM recordatorios
        WHERE datos->>'done' <> 'true' AND datos->>'deleted' <> 'true'`);
      return r.rows.map(x => ({ uid: x.usuario_id, item: x.datos }));
    },
    async emailDe(uid) {
      const r = await pool.query('SELECT email FROM usuarios WHERE id=$1', [uid]);
      return r.rows[0] ? r.rows[0].email : null;
    },
  };
}

function crearArchivo() {
  // En Render, DATA_DIR apunta al disco permanente (/var/data)
  const dir = process.env.DATA_DIR || __dirname;
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, 'datos.json');
  let db = { secreto: '', usuarios: [], items: {} };
  try { db = { ...db, ...JSON.parse(fs.readFileSync(file, 'utf8')) }; } catch (e) {}
  if (!db.secreto) db.secreto = crypto.randomBytes(32).toString('hex');
  const volcar = () => fs.writeFileSync(file, JSON.stringify(db));
  volcar();
  return {
    async secreto() { return db.secreto; },
    async contarUsuarios() { return db.usuarios.length; },
    async buscarUsuario(email) { return db.usuarios.find(u => u.email === email); },
    async crearUsuario(email, hash) {
      const u = { id: db.usuarios.length + 1, email, hash }; db.usuarios.push(u); volcar(); return u;
    },
    async guardar(uid, item) { (db.items[uid] ??= {})[item.id] = item; volcar(); },
    async listar(uid, desde) { return Object.values(db.items[uid] || {}).filter(i => i.updatedAt > desde); },
    async obtener(uid, id) { return (db.items[uid] || {})[id] || null; },
    async pendientes() {
      const r = [];
      for (const uid of Object.keys(db.items))
        for (const item of Object.values(db.items[uid])) if (!item.done && !item.deleted) r.push({ uid: Number(uid), item });
      return r;
    },
    async emailDe(uid) { const u = db.usuarios.find(x => x.id === Number(uid)); return u ? u.email : null; },
  };
}

module.exports = () => (process.env.DATABASE_URL ? crearPg() : crearArchivo());
