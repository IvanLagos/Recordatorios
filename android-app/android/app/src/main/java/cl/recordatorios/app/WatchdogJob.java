package cl.recordatorios.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import org.json.JSONArray;

/**
 * Chequeo de respaldo: cada ~15 minutos el sistema despierta la app aunque esté cerrada.
 * Revisa el servidor, reprograma las alarmas, reactiva el servicio y avisa si algo puede fallar.
 */
public class WatchdogJob extends JobService {
    static final int ID = 1001;

    static void programar(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js.getPendingJob(ID) != null) return;
            js.schedule(new JobInfo.Builder(ID, new ComponentName(c, WatchdogJob.class))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true) // sobrevive al reinicio del celular
                    .build());
        } catch (Exception ignored) {}
    }

    static void cancelar(Context c) {
        try { ((JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(ID); } catch (Exception ignored) {}
    }

    /** Todo el trabajo del chequeo (también se usa desde las pruebas). */
    static void revisar(Context c) {
        if (Alarmas.prefs(c).getString("token", "").isEmpty()) return;
        // 1) Que el servicio de sincronización siga vivo (puede que el sistema no lo permita desde aquí)
        SyncService.iniciar(c);
        // 2) Revisar el servidor y reprogramar las alarmas por si se perdió alguna
        JSONArray items = Api.listar(c);
        if (items != null) {
            Alarmas.reemplazarTodas(c, Alarmas.alarmasDesdeItems(items));
            Alarmas.marcarSync(c);
        }
        // 3) Avisar si hay permisos faltantes o llevamos horas sin conexión
        Alarmas.avisarProblemas(c);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            try { revisar(app); } catch (Exception ignored) {}
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // si el sistema lo corta, se reintenta
    }
}
