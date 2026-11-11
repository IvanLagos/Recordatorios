package cl.recordatorios.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationManagerCompat;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Pantalla completa de la alerta: aparece sobre el bloqueo, suena y vibra hasta que la atiendas. */
public class AlarmaActivity extends Activity {
    private MediaPlayer mp;
    private Vibrator vib;
    private String id, title, note, repeat;
    private long due;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean cerrando = false;
    private Runnable sondeo;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        leerDatos();
        NotificationManagerCompat.from(this).cancel(id.hashCode()); // el sonido pasa a esta pantalla
        construirPantalla();
        sonar();
        // Deja de sonar a los 2 minutos para no gastar batería si no estás cerca
        h.postDelayed(this::silenciar, 120000);
        iniciarSondeo();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        leerDatos();
        construirPantalla();
    }

    private void leerDatos() {
        id = getIntent().getStringExtra("id");
        if (id == null) id = "x";
        title = getIntent().getStringExtra("title");
        note = getIntent().getStringExtra("note");
        repeat = getIntent().getStringExtra("repeat");
        if (repeat == null) repeat = "none";
        due = getIntent().getLongExtra("due", 0);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private Button boton(String texto, int fondo, int color) {
        Button bt = new Button(this);
        bt.setText(texto);
        bt.setAllCaps(false);
        bt.setTextSize(18);
        bt.setTypeface(Typeface.DEFAULT_BOLD);
        bt.setTextColor(color);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fondo);
        g.setCornerRadius(dp(14));
        if (fondo == Color.TRANSPARENT) g.setStroke(dp(2), Color.parseColor("#88FFFFFF"));
        bt.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        lp.topMargin = dp(12);
        bt.setLayoutParams(lp);
        return bt;
    }

    private void construirPantalla() {
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(Color.parseColor("#2563EB"));
        raiz.setPadding(dp(28), dp(48), dp(28), dp(36));

        LinearLayout arriba = new LinearLayout(this);
        arriba.setOrientation(LinearLayout.VERTICAL);
        arriba.setGravity(Gravity.CENTER_VERTICAL);
        arriba.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView etiqueta = new TextView(this);
        etiqueta.setText("⏰  RECORDATORIO");
        etiqueta.setTextColor(Color.parseColor("#CCFFFFFF"));
        etiqueta.setTextSize(14);
        etiqueta.setLetterSpacing(0.12f);

        TextView t = new TextView(this);
        t.setText(title == null ? "Recordatorio" : title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(34);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(12), 0, dp(8));

        TextView n = new TextView(this);
        n.setText(note == null ? "" : note);
        n.setTextColor(Color.WHITE);
        n.setTextSize(20);

        TextView hora = new TextView(this);
        hora.setText(new SimpleDateFormat("EEEE d 'de' MMMM, HH:mm", new Locale("es", "CL")).format(new Date(due)));
        hora.setTextColor(Color.parseColor("#CCFFFFFF"));
        hora.setTextSize(16);
        hora.setPadding(0, dp(16), 0, 0);

        arriba.addView(etiqueta);
        arriba.addView(t);
        arriba.addView(n);
        arriba.addView(hora);

        Button hecho = boton("✓  Hecho", Color.WHITE, Color.parseColor("#1D4ED8"));
        Button pos = boton("Posponer 10 minutos", Color.parseColor("#1D4ED8"), Color.WHITE);
        Button cerrar = boton("Cerrar", Color.TRANSPARENT, Color.WHITE);
        hecho.setOnClickListener(v -> hecho());
        pos.setOnClickListener(v -> posponer());
        cerrar.setOnClickListener(v -> finish());

        raiz.addView(arriba);
        raiz.addView(hecho);
        raiz.addView(pos);
        raiz.addView(cerrar);
        setContentView(raiz);
    }

    /** Hecho: se guarda en el servidor al instante, así los demás aparatos también lo ven y cierran su alerta. */
    private void hecho() {
        cerrando = true;
        final Context app = getApplicationContext();
        final String fid = id;
        Alarmas.cancelarClave(app, fid + "#p");
        if ("none".equals(repeat)) Alarmas.cancelarClave(app, fid);
        new Thread(() -> {
            boolean ok = false;
            Api.Resultado r = Api.buscar(app, fid);
            if (r.ok && r.item != null) {
                try {
                    JSONObject it = r.item;
                    String rep = it.optString("repeat", "none");
                    if ("none".equals(rep)) it.put("done", true);
                    else it.put("due", Alarmas.siguiente(it.optLong("due"), rep));
                    it.put("snooze", 0);
                    ok = Api.guardar(app, it);
                } catch (Exception ignored) {}
            }
            // Sin conexión: queda guardado y se aplica cuando abras la app
            if (!ok) Alarmas.agregarAccion(app, fid, "hecho", 0);
        }).start();
        finish();
    }

    /** Posponer: vuelve a sonar en 10 minutos en este y en los demás aparatos. */
    private void posponer() {
        cerrando = true;
        final Context app = getApplicationContext();
        final String fid = id;
        final long nueva = System.currentTimeMillis() + 10 * 60000L;
        try {
            JSONObject o = new JSONObject();
            o.put("id", fid);
            o.put("title", title);
            o.put("note", note);
            o.put("due", nueva);
            o.put("repeat", "none");
            Alarmas.programar(app, o, fid + "#p");
            Alarmas.ponerEnPrefs(app, o, fid + "#p");
        } catch (Exception ignored) {}
        new Thread(() -> {
            boolean ok = false;
            Api.Resultado r = Api.buscar(app, fid);
            if (r.ok && r.item != null) {
                try {
                    r.item.put("snooze", nueva);
                    ok = Api.guardar(app, r.item);
                } catch (Exception ignored) {}
            }
            if (!ok) Alarmas.agregarAccion(app, fid, "posponer", nueva);
        }).start();
        finish();
    }

    /** Mientras suena, revisa cada pocos segundos si en otro aparato ya la atendieron. */
    private void iniciarSondeo() {
        if ("prueba".equals(id)) return;
        final Context app = getApplicationContext();
        sondeo = new Runnable() {
            @Override
            public void run() {
                if (cerrando) return;
                final String fid = id;
                new Thread(() -> {
                    final Api.Resultado r = Api.buscar(app, fid);
                    h.post(() -> { if (r.ok) evaluar(r.item); });
                }).start();
                h.postDelayed(this, 4000);
            }
        };
        h.postDelayed(sondeo, 1500);
    }

    private void evaluar(JSONObject it) {
        if (cerrando) return;
        final Context app = getApplicationContext();
        try {
            if (it == null || it.optBoolean("deleted") || it.optBoolean("done")) {
                // Ya lo marcaron como hecho (o lo borraron) en otro aparato
                Alarmas.cancelarClave(app, id + "#p");
                if (it == null || it.optBoolean("deleted") || "none".equals(it.optString("repeat", "none")))
                    Alarmas.cancelarClave(app, id);
                cerrarPorRemoto();
                return;
            }
            long due = it.optLong("due"), snz = it.optLong("snooze");
            long proxima = Math.max(due, snz);
            if (proxima > System.currentTimeMillis() + 2000) {
                // Lo pospusieron o lo cambiaron en otro aparato: aquí vuelve a sonar a esa hora
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("title", it.optString("title", title));
                o.put("note", it.optString("note", note));
                o.put("due", proxima);
                String clave;
                if (snz > due) { o.put("repeat", "none"); clave = id + "#p"; }
                else { o.put("repeat", it.optString("repeat", "none")); clave = id; }
                Alarmas.programar(app, o, clave);
                Alarmas.ponerEnPrefs(app, o, clave);
                cerrarPorRemoto();
            }
        } catch (Exception ignored) {}
    }

    private void cerrarPorRemoto() {
        cerrando = true;
        finish();
    }

    private void sonar() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            mp.setDataSource(this, uri);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
        } catch (Exception ignored) {}
        try {
            vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            long[] patron = {0, 700, 400};
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(patron, 0));
            else vib.vibrate(patron, 0);
        } catch (Exception ignored) {}
    }

    private void silenciar() {
        try { if (mp != null) { mp.stop(); mp.release(); mp = null; } } catch (Exception ignored) {}
        try { if (vib != null) vib.cancel(); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        cerrando = true;
        h.removeCallbacksAndMessages(null);
        silenciar();
        super.onDestroy();
    }
}
