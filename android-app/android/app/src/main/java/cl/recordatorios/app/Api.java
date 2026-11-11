package cl.recordatorios.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Llamadas mínimas al servidor, para atender la alerta aunque la app esté cerrada. */
public class Api {
    static class Resultado {
        boolean ok;          // false si no hay sesión o falló la conexión
        JSONObject item;     // null si el servidor no lo tiene
    }

    private static HttpURLConnection abrir(Context c, String ruta, String metodo) throws Exception {
        String base = Alarmas.prefs(c).getString("base", "");
        String token = Alarmas.prefs(c).getString("token", "");
        if (base.isEmpty() || token.isEmpty()) throw new Exception("sin sesion");
        HttpURLConnection h = (HttpURLConnection) new URL(base + ruta).openConnection();
        h.setRequestMethod(metodo);
        h.setConnectTimeout(8000);
        h.setReadTimeout(8000);
        h.setRequestProperty("Authorization", "Bearer " + token);
        h.setRequestProperty("x-cliente", "android");
        return h;
    }

    private static String leer(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toString("UTF-8");
    }

    /** Busca un recordatorio en el servidor. */
    static Resultado buscar(Context c, String id) {
        Resultado r = new Resultado();
        try {
            HttpURLConnection h = abrir(c, "/api/recordatorios?desde=0", "GET");
            if (h.getResponseCode() != 200) return r;
            JSONArray items = new JSONObject(leer(h.getInputStream())).getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject x = items.getJSONObject(i);
                if (id.equals(x.optString("id"))) r.item = x;
            }
            r.ok = true;
        } catch (Exception ignored) {}
        return r;
    }

    /** Guarda el recordatorio modificado. */
    static boolean guardar(Context c, JSONObject item) {
        try {
            HttpURLConnection h = abrir(c, "/api/recordatorios/" + item.optString("id"), "PUT");
            h.setRequestProperty("Content-Type", "application/json");
            h.setDoOutput(true);
            OutputStream os = h.getOutputStream();
            os.write(item.toString().getBytes("UTF-8"));
            os.close();
            return h.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
