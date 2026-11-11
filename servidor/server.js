const express = require('express');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const path = require('path');
const abrirStore = require('./store');

const PORT = process.env.PORT || 8123;
const app = express();
app.set('trust proxy', 1);
app.use(express.json({ limit: '100kb' }));

(async () => {
  const store = await abrirStore();
  const SECRETO = process.env.JWT_SECRET || (await store.secreto());

  // La app de Android carga la página desde el propio celular y llama a esta API:
  // se permite porque la autenticación va por token (Bearer), no por cookies.
  app.use('/api', (req, res, next) => {
    res.set({
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': 'Authorization, Content-Type, x-cliente',
      'Access-Control-Allow-Methods': 'GET, PUT, POST, OPTIONS',
    });
    if (req.method === 'OPTIONS') return res.sendStatus(204);
    next();
  });

  // --- Límite simple de intentos de login por IP ---
  const intentos = new Map();
  const bloqueado = ip => (intentos.get(ip) || []).filter(t => Date.now() - t < 15 * 60000).length >= 10;
  const falla = ip => intentos.set(ip, [...(intentos.get(ip) || []), Date.now()]);

  const firmar = u => jwt.sign({ uid: u.id, email: u.email }, SECRETO, { expiresIn: '180d' });
  const emailOk = e => typeof e === 'string' && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(e) && e.length < 200;

  // Solo se puede crear una cuenta si no existe ninguna (uso personal),
  // o si se define PERMITIR_REGISTRO=1.
  app.post('/api/registro', async (req, res) => {
    const { email, password } = req.body || {};
    if (!emailOk(email) || typeof password !== 'string' || password.length < 8)
      return res.status(400).json({ error: 'Correo válido y contraseña de al menos 8 caracteres.' });
    if ((await store.contarUsuarios()) > 0 && process.env.PERMITIR_REGISTRO !== '1')
      return res.status(403).json({ error: 'El registro está cerrado.' });
    const e = email.toLowerCase();
    if (await store.buscarUsuario(e)) return res.status(409).json({ error: 'Ese correo ya existe.' });
    const u = await store.crearUsuario(e, await bcrypt.hash(password, 10));
    res.json({ token: firmar(u), email: u.email });
  });

  app.post('/api/login', async (req, res) => {
    if (bloqueado(req.ip)) return res.status(429).json({ error: 'Demasiados intentos. Espera 15 minutos.' });
    const { email, password } = req.body || {};
    const u = typeof email === 'string' && typeof password === 'string' && (await store.buscarUsuario(email.toLowerCase()));
    if (!u || !(await bcrypt.compare(password, u.hash))) {
      falla(req.ip);
      return res.status(401).json({ error: 'Correo o contraseña incorrectos.' });
    }
    res.json({ token: firmar(u), email: u.email });
  });

  const auth = (req, res, next) => {
    const t = (req.headers.authorization || '').replace(/^Bearer /, '') || req.query.token;
    try { req.user = jwt.verify(t, SECRETO); next(); }
    catch (e) { res.status(401).json({ error: 'Sesión no válida.' }); }
  };

  // --- Sincronización en tiempo real (Server-Sent Events) ---
  const oyentes = new Map(); // uid -> Set(res)
  const avisar = (uid, origen) => (oyentes.get(uid) || new Set()).forEach(r => r.write(`data: ${origen || ''}\n\n`));

  app.get('/api/eventos', auth, (req, res) => {
    res.set({ 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
    res.flushHeaders();
    res.write('retry: 3000\n\n');
    const set = oyentes.get(req.user.uid) || new Set();
    set.add(res); oyentes.set(req.user.uid, set);
    const latido = setInterval(() => res.write(': ping\n\n'), 25000);
    req.on('close', () => { clearInterval(latido); set.delete(res); });
  });

  // Devuelve los recordatorios modificados después de "desde" (ms del servidor).
  app.get('/api/recordatorios', auth, async (req, res) => {
    const desde = Number(req.query.desde) || 0;
    res.json({ ahora: Date.now(), items: await store.listar(req.user.uid, desde) });
  });

  // Crea o actualiza (gana la última escritura). Los borrados se guardan con deleted:true.
  app.put('/api/recordatorios/:id', auth, async (req, res) => {
    const b = req.body || {};
    const id = String(req.params.id);
    if (!/^[\w-]{1,40}$/.test(id)) return res.status(400).json({ error: 'id inválido' });
    const item = {
      id,
      title: String(b.title || '').slice(0, 120),
      note: String(b.note || '').slice(0, 300),
      due: Number(b.due) || 0,
      repeat: ['none', 'daily', 'weekly', 'monthly'].includes(b.repeat) ? b.repeat : 'none',
      done: !!b.done,
      fired: !!b.fired,
      snooze: Number(b.snooze) || 0, // hora hasta la que está pospuesto (0 = no pospuesto)
      deleted: !!b.deleted,
      updatedAt: Date.now(),
    };
    if (!item.deleted && (!item.title || !item.due)) return res.status(400).json({ error: 'Falta título o fecha.' });
    await store.guardar(req.user.uid, item);
    avisar(req.user.uid, req.get('x-cliente'));
    res.json(item);
  });

  // La app web (se sirve desde la carpeta public)
  app.use(express.static(path.join(__dirname, 'public')));

  app.listen(PORT, () => console.log('Recordatorios en http://localhost:' + PORT));
})().catch(e => { console.error(e); process.exit(1); });
