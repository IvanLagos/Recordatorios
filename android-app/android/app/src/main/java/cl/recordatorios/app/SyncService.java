package cl.recordatorios.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;

/**
 * Servicio en segundo plano: mantiene una conexión con el servidor y programa las alarmas
 * apenas se crea o cambia un recordatorio en otro aparato, aunque la app esté cerrada.
 */
public class SyncService extends Service {
    private static final int NOTIF_ID = 4242;
    private static final String CANAL = "sync_v1";
    private volatile boolean activo = false;
    private Thread hilo;
    private volatile HttpURLConnection conexion;

    static void iniciar(Context c) {
        Intent i = new Intent(c, SyncService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) {}
    }

    static void detener(Context c) {
        c.stopService(new Intent(c, SyncService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = notificacion();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, n);
        if (hilo == null || !hilo.isAlive()) {
            activo = true;
            hilo = new Thread(this::bucle, "recordatorios-sync");
            hilo.start();
        }
        return START_STICKY; // si el sistema lo cierra, lo vuelve a levantar
    }

    private Notification notificacion() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CANAL) == null) {
                NotificationChannel ch = new NotificationChannel(CANAL, "Recordatorios activo", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Mantiene tus recordatorios al día para avisarte a tiempo");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        return new NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Recordatorios activo")
                .setContentText("Sincronizado para avisarte a tiempo")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void bucle() {
        long espera = 5000;
        while (activo) {
            String base = Alarmas.prefs(this).getString("base", "");
            String token = Alarmas.prefs(this).getString("token", "");
            if (base.isEmpty() || token.isEmpty()) { dormir(20000); continue; }
            try {
                sincronizar();
                escuchar();
                espera = 5000;
            } catch (Exception e) {
                espera = Math.min(espera * 2, 60000); // reintenta con pausa creciente si no hay internet
            }
            dormir(espera);
        }
    }

    private void sincronizar() {
        JSONArray items = Api.listar(this);
        if (items != null) Alarmas.reemplazarTodas(this, Alarmas.alarmasDesdeItems(items));
    }

    /** Se queda leyendo los avisos del servidor; cada cambio dispara una sincronización. */
    private void escuchar() throws Exception {
        long ultima = System.currentTimeMillis();
        conexion = Api.eventos(this);
        if (conexion.getResponseCode() != 200) throw new Exception("http");
        BufferedReader r = new BufferedReader(new InputStreamReader(conexion.getInputStream(), "UTF-8"));
        String linea;
        while (activo && (linea = r.readLine()) != null) {
            boolean cambio = linea.startsWith("data:");
            boolean latido = linea.startsWith(":");
            // Además, cada 10 minutos se revisa por si se perdió algún aviso
            if (cambio || (latido && System.currentTimeMillis() - ultima > 600000)) {
                sincronizar();
                ultima = System.currentTimeMillis();
            }
        }
    }

    private void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    @Override
    public void onDestroy() {
        activo = false;
        try { if (conexion != null) conexion.disconnect(); } catch (Exception ignored) {}
        if (hilo != null) hilo.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
