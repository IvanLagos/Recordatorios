package cl.recordatorios.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Al encender el celular (o actualizar la app) vuelve a programar todas las alarmas. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            JSONArray lista = Alarmas.leer(c, Alarmas.K_ALARMAS);
            for (int k = 0; k < lista.length(); k++) {
                JSONObject o = lista.getJSONObject(k);
                String repeat = o.optString("repeat", "none");
                if (o.optLong("due") <= System.currentTimeMillis() && !"none".equals(repeat)) {
                    o.put("due", Alarmas.siguiente(o.optLong("due"), repeat));
                }
                Alarmas.programar(c, o, Alarmas.claveDe(o));
            }
            Alarmas.guardar(c, Alarmas.K_ALARMAS, lista);
        } catch (Exception ignored) {}
        // Vuelve a levantar el servicio de sincronización si hay sesión iniciada
        if (!Alarmas.prefs(c).getString("token", "").isEmpty()) SyncService.iniciar(c);
    }
}
