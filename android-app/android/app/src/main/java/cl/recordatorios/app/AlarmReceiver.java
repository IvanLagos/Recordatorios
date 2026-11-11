package cl.recordatorios.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import org.json.JSONObject;

/** Se ejecuta a la hora exacta de cada recordatorio. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        // Mantiene el celular despierto el tiempo justo para mostrar la alerta
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "recordatorios:alarma");
        wl.acquire(15000);

        String id = i.getStringExtra("id");
        String title = i.getStringExtra("title");
        String note = i.getStringExtra("note");
        long due = i.getLongExtra("due", 0);
        String repeat = i.getStringExtra("repeat");
        if (id == null) return;
        if (repeat == null) repeat = "none";

        Alarmas.mostrar(c, id, title == null ? "Recordatorio" : title, note == null ? "" : note, due, repeat);

        // Los repetitivos programan solos su siguiente fecha
        if (!"none".equals(repeat)) {
            try {
                long next = Alarmas.siguiente(due, repeat);
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("title", title);
                o.put("note", note);
                o.put("due", next);
                o.put("repeat", repeat);
                Alarmas.programar(c, o, id);
                Alarmas.actualizarDue(c, id, next);
            } catch (Exception ignored) {}
        }
    }
}
