package cl.recordatorios.app;

import android.app.Activity;
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

    private void hecho() {
        Alarmas.agregarAccion(this, id, "hecho", 0);
        finish();
    }

    private void posponer() {
        long nueva = System.currentTimeMillis() + 10 * 60000L;
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("note", note);
            o.put("due", nueva);
            if ("none".equals(repeat)) {
                // Recordatorio único: la misma alarma se mueve 10 minutos
                o.put("repeat", "none");
                Alarmas.programar(this, o, id);
                Alarmas.actualizarDue(this, id, nueva);
            } else {
                // Repetitivo: una alarma aparte, sin tocar su calendario normal
                o.put("repeat", "none");
                Alarmas.programar(this, o, id + "#p");
            }
        } catch (Exception ignored) {}
        Alarmas.agregarAccion(this, id, "posponer", nueva);
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
        h.removeCallbacksAndMessages(null);
        silenciar();
        super.onDestroy();
    }
}
