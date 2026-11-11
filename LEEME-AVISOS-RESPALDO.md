# Aviso de respaldo del servidor

Si un recordatorio vence y ningún aparato (PC o celular) confirma haber mostrado su alerta
en 3 minutos, el servidor te avisa por correo y/o por ntfy. Avisa una sola vez por cada vencimiento.

Se activa con variables de entorno en Render (servicio `recordatorios` > Environment).
Sin ellas, el respaldo queda desactivado.

## Opción A: ntfy (la más simple, sin contraseñas)
1. Instala la app **ntfy** en el celular (gratis) y suscríbete a un tema con un nombre largo y difícil de adivinar,
   por ejemplo `recordatorios-ivan-x7k29qpz`.
2. En Render agrega: `NTFY_TOPIC` = ese mismo nombre.

## Opción B: correo (SMTP)
En Render agrega: `SMTP_HOST`, `SMTP_PORT` (587), `SMTP_USER`, `SMTP_PASS`.
Con Gmail: `smtp.gmail.com` y una "contraseña de aplicación" (no la contraseña normal).
Opcionales: `SMTP_FROM` y `AVISO_EMAIL` (por defecto llega al correo de tu cuenta).

## Ajustes opcionales
`AVISO_GRACE_MS` (espera antes de avisar, por defecto 180000 = 3 min) · `AVISO_ZONA` (por defecto America/Santiago).

Las contraseñas se escriben solo en Render, nunca en el código ni en el chat.
