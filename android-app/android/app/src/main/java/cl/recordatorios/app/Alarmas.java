package cl.recordatorios.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;

/** Utilidades compartidas: guardado de alarmas, programación y notificación. */
public class Alarmas {
    static final String PREFS = "recordatorios";
    static final String K_ALARMAS = "alarmas";   // alarmas programadas (copia para reiniciar tras apagar el celular)
    static final String K_ACCIONES = "acciones"; // lo que el usuario eligió en la alerta (Hecho/Posponer), pendiente de pasar a la app
    static final String CANAL = "alarmas_v1";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static JSONArray leer(Context c, String clave) {
        try { return new JSONArray(prefs(c).getString(clave, "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    static void guardar(Context c, String clave, JSONArray a) {
        prefs(c).edit().putString(clave, a.toString()).apply();
    }

    /** Intent de la alarma. "clave" distingue la alarma normal de una pospuesta. */
    static PendingIntent pi(Context c, JSONObject a, String clave) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction("cl.recordatorios.ALARMA");
        i.putExtra("id", a.optString("id"));
        i.putExtra("title", a.optString("title"));
        i.putExtra("note", a.optString("note"));
        i.putExtra("due", a.optLong("due"));
        i.putExtra("repeat", a.optString("repeat", "none"));
        i.putExtra("clave", clave);
        return PendingIntent.getBroadcast(c, clave.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void programar(Context c, JSONObject a, String clave) {
        long due = a.optLong("due");
        if (due <= System.currentTimeMillis()) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent abrir = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        // setAlarmClock: suena a la hora exacta aunque el celular esté en reposo profundo
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(due, abrir), pi(c, a, clave));
    }

    static void cancelar(Context c, JSONObject a, String clave) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi(c, a, clave));
    }

    /** Siguiente fecha de un recordatorio repetitivo, siempre en el futuro. */
    static long siguiente(long due, String repeat) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(due);
        do {
            if ("daily".equals(repeat)) cal.add(Calendar.DAY_OF_YEAR, 1);
            else if ("weekly".equals(repeat)) cal.add(Calendar.DAY_OF_YEAR, 7);
            else cal.add(Calendar.MONTH, 1);
        } while (cal.getTimeInMillis() <= System.currentTimeMillis());
        return cal.getTimeInMillis();
    }

    static void marcarSync(Context c) {
        prefs(c).edit().putLong("ultimaSync", System.currentTimeMillis()).apply();
    }

    /**
     * Si algo puede hacer que los avisos fallen (permisos, batería, sin conexión hace horas),
     * lo avisa con una notificación. Como mucho una vez cada 12 horas.
     */
    static void avisarProblemas(Context c) {
        StringBuilder sb = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            sb.append("Faltan permisos de notificaciones. ");
        android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(c.getPackageName()))
            sb.append("La batería puede cerrar la app: quita su restricción. ");
        if (Build.VERSION.SDK_INT >= 34 && !c.getSystemService(NotificationManager.class).canUseFullScreenIntent())
            sb.append("Falta el permiso de pantalla completa. ");
        long ult = prefs(c).getLong("ultimaSync", 0);
        if (ult > 0 && System.currentTimeMillis() - ult > 2 * 3600000L)
            sb.append("Sin conexión con el servidor desde hace " + ((System.currentTimeMillis() - ult) / 3600000L)
                    + " h: los recordatorios nuevos no llegarán. ");
        if (sb.length() == 0) return;
        if (System.currentTimeMillis() - prefs(c).getLong("ultimoAviso", 0) < 12 * 3600000L) return;
        prefs(c).edit().putLong("ultimoAviso", System.currentTimeMillis()).apply();

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel("problemas_v1") == null)
                nm.createNotificationChannel(new NotificationChannel("problemas_v1", "Problemas con los avisos", NotificationManager.IMPORTANCE_HIGH));
        }
        PendingIntent abrir = PendingIntent.getActivity(c, 7, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, "problemas_v1")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Recordatorios: tus avisos pueden fallar")
                .setContentText(sb.toString().trim())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(sb.toString().trim()))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(abrir)
                .setAutoCancel(true);
        try { NotificationManagerCompat.from(c).notify(9001, b.build()); } catch (SecurityException ignored) {}
    }

    /** Cancela todas las alarmas guardadas y programa las nuevas (la lista completa manda). */
    static synchronized void reemplazarTodas(Context c, JSONArray nuevas) {
        try {
            JSONArray viejas = leer(c, K_ALARMAS);
            for (int i = 0; i < viejas.length(); i++) {
                JSONObject o = viejas.getJSONObject(i);
                cancelar(c, o, claveDe(o));
            }
            for (int i = 0; i < nuevas.length(); i++) {
                JSONObject o = nuevas.getJSONObject(i);
                programar(c, o, claveDe(o));
            }
            guardar(c, K_ALARMAS, nuevas);
        } catch (JSONException ignored) {}
    }

    /** Convierte los recordatorios del servidor en la lista de alarmas que hay que programar. */
    static JSONArray alarmasDesdeItems(JSONArray items) {
        long ahora = System.currentTimeMillis();
        java.util.List<JSONObject> lista = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                if (it.optBoolean("deleted") || it.optBoolean("done")) continue;
                String id = it.optString("id"), rep = it.optString("repeat", "none");
                long due = it.optLong("due"), snz = it.optLong("snooze");
                long d = (!"none".equals(rep) && due <= ahora) ? siguiente(due, rep) : due;
                if (d > ahora) {
                    JSONObject o = new JSONObject();
                    o.put("id", id); o.put("title", it.optString("title")); o.put("note", it.optString("note"));
                    o.put("repeat", rep); o.put("due", d); o.put("clave", id);
                    lista.add(o);
                }
                if (snz > ahora) {
                    JSONObject o = new JSONObject();
                    o.put("id", id); o.put("title", it.optString("title")); o.put("note", it.optString("note"));
                    o.put("repeat", "none"); o.put("due", snz); o.put("clave", id + "#p");
                    lista.add(o);
                }
            }
        } catch (JSONException ignored) {}
        java.util.Collections.sort(lista, (a, b) -> Long.compare(a.optLong("due"), b.optLong("due")));
        JSONArray r = new JSONArray();
        for (int i = 0; i < lista.size() && i < 80; i++) r.put(lista.get(i));
        return r;
    }

    /** Nombre único de la alarma: el id del recordatorio, o id#p si es una pospuesta. */
    static String claveDe(JSONObject o) {
        return o.optString("clave", o.optString("id"));
    }

    /** Guarda (o reemplaza) la alarma en la copia local, para reprogramarla tras reiniciar el celular. */
    static void ponerEnPrefs(Context c, JSONObject o, String clave) {
        try {
            o.put("clave", clave);
            JSONArray viejas = leer(c, K_ALARMAS), nuevas = new JSONArray();
            for (int i = 0; i < viejas.length(); i++) {
                JSONObject x = viejas.getJSONObject(i);
                if (!claveDe(x).equals(clave)) nuevas.put(x);
            }
            nuevas.put(o);
            guardar(c, K_ALARMAS, nuevas);
        } catch (JSONException ignored) {}
    }

    /** Cancela una alarma y la saca de la copia local. */
    static void cancelarClave(Context c, String clave) {
        cancelar(c, new JSONObject(), clave);
        try {
            JSONArray viejas = leer(c, K_ALARMAS), nuevas = new JSONArray();
            for (int i = 0; i < viejas.length(); i++) {
                JSONObject x = viejas.getJSONObject(i);
                if (!claveDe(x).equals(clave)) nuevas.put(x);
            }
            guardar(c, K_ALARMAS, nuevas);
        } catch (JSONException ignored) {}
    }

    static void agregarAccion(Context c, String id, String tipo, long due) {
        try {
            JSONArray lista = leer(c, K_ACCIONES);
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("tipo", tipo);
            o.put("due", due);
            lista.put(o);
            guardar(c, K_ACCIONES, lista);
        } catch (JSONException ignored) {}
    }

    /** Cambia la fecha de una alarma guardada (por ejemplo al posponer). */
    static void actualizarDue(Context c, String id, long due) {
        try {
            JSONArray lista = leer(c, K_ALARMAS);
            for (int i = 0; i < lista.length(); i++) {
                JSONObject o = lista.getJSONObject(i);
                if (id.equals(o.optString("id")) && id.equals(claveDe(o))) { o.put("due", due); }
            }
            guardar(c, K_ALARMAS, lista);
        } catch (JSONException ignored) {}
    }

    static void crearCanal(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CANAL) != null) return;
        NotificationChannel ch = new NotificationChannel(CANAL, "Recordatorios", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Avisos de tus recordatorios a la hora programada");
        Uri sonido = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        ch.setSound(sonido, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 600, 300, 600});
        ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    /** Muestra el aviso: pantalla completa si el celular está bloqueado, o notificación destacada si está en uso. */
    static void mostrar(Context c, String id, String title, String note, long due, String repeat) {
        crearCanal(c);
        Intent full = new Intent(c, AlarmaActivity.class);
        full.putExtra("id", id);
        full.putExtra("title", title);
        full.putExtra("note", note);
        full.putExtra("due", due);
        full.putExtra("repeat", repeat);
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent fp = PendingIntent.getActivity(c, id.hashCode(), full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CANAL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(note == null || note.isEmpty() ? "Es la hora de tu recordatorio" : note)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fp, true)
                .setContentIntent(fp)
                .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(c).notify(id.hashCode(), b.build());
        } catch (SecurityException ignored) { /* sin permiso de notificaciones */ }
        // Intento directo: si el sistema lo permite, la pantalla aparece encima de lo que estés haciendo
        try { c.startActivity(full); } catch (Exception ignored) {}
    }
}
