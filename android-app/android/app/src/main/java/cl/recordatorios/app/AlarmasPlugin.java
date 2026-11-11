package cl.recordatorios.app;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

/** Puente entre la app web y las alarmas nativas del celular. */
@CapacitorPlugin(name = "Alarmas")
public class AlarmasPlugin extends Plugin {

    /** Recibe todos los recordatorios pendientes y reprograma las alarmas. */
    @PluginMethod
    public void programar(PluginCall call) {
        Context c = getContext();
        try {
            // Quita las anteriores
            JSONArray viejas = Alarmas.leer(c, Alarmas.K_ALARMAS);
            for (int i = 0; i < viejas.length(); i++) {
                JSONObject o = viejas.getJSONObject(i);
                Alarmas.cancelar(c, o, Alarmas.claveDe(o));
            }
            // Programa las nuevas
            JSArray nuevas = call.getArray("alarmas", new JSArray());
            JSONArray guardar = new JSONArray();
            for (int i = 0; i < nuevas.length(); i++) {
                JSONObject o = nuevas.getJSONObject(i);
                guardar.put(o);
                Alarmas.programar(c, o, Alarmas.claveDe(o));
            }
            Alarmas.guardar(c, Alarmas.K_ALARMAS, guardar);
            JSObject r = new JSObject();
            r.put("programadas", guardar.length());
            call.resolve(r);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    /** Guarda la sesión para que la pantalla de alerta pueda hablar con el servidor aunque la app esté cerrada. */
    @PluginMethod
    public void sesion(PluginCall call) {
        Alarmas.prefs(getContext()).edit()
                .putString("token", call.getString("token", ""))
                .putString("base", call.getString("base", ""))
                .apply();
        call.resolve();
    }

    /** Lo que el usuario eligió en la pantalla de alerta mientras la app estaba cerrada. */
    @PluginMethod
    public void acciones(PluginCall call) {
        JSObject r = new JSObject();
        try { r.put("acciones", new JSArray(Alarmas.leer(getContext(), Alarmas.K_ACCIONES).toString())); }
        catch (Exception e) { r.put("acciones", new JSArray()); }
        call.resolve(r);
    }

    @PluginMethod
    public void limpiarAcciones(PluginCall call) {
        Alarmas.guardar(getContext(), Alarmas.K_ACCIONES, new JSONArray());
        call.resolve();
    }

    @PluginMethod
    public void estado(PluginCall call) {
        Context c = getContext();
        JSObject r = new JSObject();
        boolean notif = Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        r.put("notificaciones", notif);
        r.put("bateria", pm.isIgnoringBatteryOptimizations(c.getPackageName()));
        boolean pantalla = true;
        if (Build.VERSION.SDK_INT >= 34) pantalla = c.getSystemService(NotificationManager.class).canUseFullScreenIntent();
        r.put("pantallaCompleta", pantalla);
        call.resolve(r);
    }

    /** Pide los permisos necesarios. "paso": notificaciones | bateria | pantalla | ajustes */
    @PluginMethod
    public void configurar(PluginCall call) {
        Context c = getContext();
        String paso = call.getString("paso", "notificaciones");
        try {
            if ("notificaciones".equals(paso)) {
                if (Build.VERSION.SDK_INT >= 33)
                    ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            } else if ("bateria".equals(paso)) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } else if ("pantalla".equals(paso)) {
                if (Build.VERSION.SDK_INT >= 34) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + c.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(i);
                }
            } else {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    /** Dispara una alarma de prueba dentro de unos segundos. */
    @PluginMethod
    public void probar(PluginCall call) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", "prueba");
            o.put("title", "Alarma de prueba");
            o.put("note", "Si ves esta pantalla, los avisos funcionan");
            o.put("due", System.currentTimeMillis() + call.getInt("segundos", 8) * 1000L);
            o.put("repeat", "none");
            Alarmas.programar(getContext(), o, "prueba");
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }
}
