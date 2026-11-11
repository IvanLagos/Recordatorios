// Aviso de respaldo: si un recordatorio venció y ningún aparato confirmó haberlo mostrado,
// el servidor te lo avisa por correo y/o por ntfy (según lo que configures en Render).
//
// Variables de entorno (todas opcionales; sin ellas el respaldo queda desactivado):
//   Correo:  SMTP_HOST, SMTP_PORT (587), SMTP_USER, SMTP_PASS, SMTP_FROM, AVISO_EMAIL (por defecto, el correo de la cuenta)
//   ntfy:    NTFY_TOPIC (un nombre difícil de adivinar), NTFY_URL (por defecto https://ntfy.sh)
//   Ajustes: AVISO_GRACE_MS (espera antes de avisar, 180000 = 3 min), AVISO_ZONA (America/Santiago)

const GRACE = Number(process.env.AVISO_GRACE_MS) || 180000;
const VENTANA = 6 * 3600000; // no avisa de cosas vencidas hace más de 6 horas
const ZONA = process.env.AVISO_ZONA || 'America/Santiago';

const hayCorreo = () => !!(process.env.SMTP_HOST && process.env.SMTP_USER && process.env.SMTP_PASS);
const hayNtfy = () => !!process.env.NTFY_TOPIC;
const activo = () => hayCorreo() || hayNtfy();

let transporte = null;
async function enviarCorreo(para, asunto, texto) {
  if (!transporte) {
    transporte = require('nodemailer').createTransport({
      host: process.env.SMTP_HOST,
      port: Number(process.env.SMTP_PORT) || 587,
      secure: Number(process.env.SMTP_PORT) === 465,
      auth: { user: process.env.SMTP_USER, pass: process.env.SMTP_PASS },
    });
  }
  await transporte.sendMail({ from: process.env.SMTP_FROM || process.env.SMTP_USER, to: process.env.AVISO_EMAIL || para, subject: asunto, text: texto });
}

async function enviarNtfy(texto) {
  const base = (process.env.NTFY_URL || 'https://ntfy.sh').replace(/\/$/, '');
  const r = await fetch(base + '/' + encodeURIComponent(process.env.NTFY_TOPIC), {
    method: 'POST',
    headers: { Title: 'Recordatorio sin atender', Priority: 'high', Tags: 'alarm_clock' },
    body: texto,
  });
  if (!r.ok) throw new Error('ntfy ' + r.status);
}

/** Devuelve true si salió por al menos un canal. */
async function enviar(email, item, t) {
  const hora = new Date(t).toLocaleString('es-CL', { timeZone: ZONA, weekday: 'long', day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' });
  const texto = `Recordatorio sin atender: ${item.title}${item.note ? '\n' + item.note : ''}\nEra para: ${hora}\nNingún aparato confirmó haberlo mostrado.`;
  let ok = false;
  if (hayCorreo()) {
    try { await enviarCorreo(email, 'Recordatorio sin atender: ' + item.title, texto); ok = true; }
    catch (e) { console.error('[aviso] correo falló:', e.message); }
  }
  if (hayNtfy()) {
    try { await enviarNtfy(texto); ok = true; }
    catch (e) { console.error('[aviso] ntfy falló:', e.message); }
  }
  console.log(`[aviso] ${ok ? 'enviado' : 'NO enviado'}: "${item.title}"`);
  return ok;
}

/** Revisa todos los recordatorios pendientes y avisa de los que nadie confirmó. */
async function revisar(store) {
  if (!activo()) return;
  const ahora = Date.now();
  for (const { uid, item } of await store.pendientes()) {
    const t = Math.max(item.due || 0, item.snooze || 0);
    if (t > ahora - GRACE || t < ahora - VENTANA) continue;
    if ((item.ack || 0) >= t || (item.notif || 0) >= t) continue;
    const email = await store.emailDe(uid);
    if (!(await enviar(email, item, t))) continue;
    // Se vuelve a leer justo antes de guardar, para no pisar un cambio hecho mientras se enviaba
    const fresco = await store.obtener(uid, item.id);
    if (fresco) { fresco.notif = t; await store.guardar(uid, fresco); }
  }
}

function iniciar(store) {
  console.log('Aviso de respaldo: ' + (activo()
    ? 'activo (' + [hayCorreo() && 'correo', hayNtfy() && 'ntfy'].filter(Boolean).join(' + ') + ')'
    : 'desactivado (configura SMTP_* o NTFY_TOPIC para activarlo)'));
  setInterval(() => revisar(store).catch(e => console.error('[aviso]', e.message)), 30000);
}

module.exports = { iniciar, revisar, activo };
