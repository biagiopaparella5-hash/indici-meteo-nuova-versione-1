package it.indicimeteo.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Controllo periodico in background: confronta l'ultima versione di ogni fonte
 * e invia una notifica quando esce un aggiornamento.
 */
public class UpdateJob extends JobService {
    static final String PREFS = "indici";
    static final int JOB_ID = 1;
    private static final String CPC = "https://ftp.cpc.ncep.noaa.gov/cwlinks/";

    // id, nome, url, modalità (tail = scarica e legge l'ultima riga, meta = solo intestazioni), pagina da aprire
    private static final String[][] SOURCES = {
            {"tele", "NAO giornaliera", CPC + "norm.daily.nao.cdas.z500.19500101_current.csv", "tail", "#/d/nao"},
            {"gefs", "Previsioni GEFS (NAO/AO/PNA)", CPC + "norm.daily.nao.gefs.z500.120days.csv", "meta", "#/d/nao"},
            {"mjo", "MJO", "https://www.bom.gov.au/clim_data/IDCKGEM000/rmm.74toRealtime.txt", "meta", "#/d/mjo"},
            {"enso", "ENSO – Niño 3.4 settimanale", "https://www.cpc.ncep.noaa.gov/data/indices/wksst9120.for", "meta", "#/d/enso"},
            {"enso", "ENSO – ONI", "https://www.cpc.ncep.noaa.gov/data/indices/oni.ascii.txt", "tail", "#/d/enso"},
            {"mensili", "Pattern mensili (EA, SCA, EA/WR…)", "https://ftp.cpc.ncep.noaa.gov/wd52dg/data/indices/tele_index.nh", "meta", "#/d/monthly"},
            {"qbo", "QBO", "https://psl.noaa.gov/data/correlation/qbo.data", "meta", "#/d/qbo"},
            {"sole", "Ciclo solare", "https://services.swpc.noaa.gov/json/solar-cycle/observed-solar-cycle-indices.json", "meta", "#/d/cycle"}
    };
    private static final String KP_URL = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json";

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try {
                runChecks(getApplicationContext());
            } catch (Exception ignored) {
            } finally {
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    static JSONObject prefs(Context ctx) {
        try {
            String s = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getString("prefs", null);
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        JSONObject p = prefs(ctx);
        if (!p.optBoolean("enabled", false)) {
            js.cancel(JOB_ID);
            return;
        }
        long interval = Math.max(1, p.optInt("intervalH", 3)) * 3600_000L;
        JobInfo pending = js.getPendingJob(JOB_ID);
        if (pending != null && pending.getIntervalMillis() == interval) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, UpdateJob.class))
                .setPeriodic(interval)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    static void runChecks(Context ctx) {
        JSONObject p = prefs(ctx);
        if (!p.optBoolean("enabled", false)) return;
        JSONObject on = p.optJSONObject("notif");
        if (on == null) on = new JSONObject();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        int nid = 100;

        for (String[] s : SOURCES) {
            nid++;
            if (!on.optBoolean(s[0], false)) continue;
            try {
                String sig;
                String detail = null;
                if ("tail".equals(s[3])) {
                    Net.Result r = Net.get(s[2], "bytes=-3000");
                    if (r.code == 416) r = Net.get(s[2], null);
                    if (r.code >= 400) continue;
                    String line = lastLine(new String(r.body, StandardCharsets.UTF_8));
                    sig = line;
                    detail = describe(s[0], line);
                } else {
                    sig = metaSignature(s[2]);
                }
                if (sig == null || sig.isEmpty()) continue;
                String key = "sig:" + s[2];
                String old = sp.getString(key, null);
                sp.edit().putString(key, sig).apply();
                if (old != null && !old.equals(sig)) {
                    Notif.show(ctx, nid, "Aggiornato: " + s[1],
                            detail != null ? detail : "È disponibile un nuovo aggiornamento. Tocca per vederlo.", s[4]);
                }
            } catch (Exception ignored) {
                // fonte non raggiungibile: riproverà al prossimo controllo
            }
        }

        if (on.optBoolean("kp", false)) {
            try {
                Net.Result r = Net.get(KP_URL, null);
                JSONArray a = new JSONArray(new String(r.body, StandardCharsets.UTF_8));
                if (a.length() > 1) {
                    double kp;
                    String time;
                    Object lastRow = a.get(a.length() - 1);
                    if (lastRow instanceof JSONArray) {
                        JSONArray row = (JSONArray) lastRow;
                        time = row.optString(0);
                        kp = Double.parseDouble(row.optString(1, "0"));
                    } else {
                        JSONObject row = (JSONObject) lastRow;
                        time = row.optString("time_tag");
                        kp = row.has("Kp") ? row.optDouble("Kp", 0) : row.optDouble("kp", 0);
                    }
                    String lastAlert = sp.getString("kpAlert", "");
                    if (kp >= 5 && !time.equals(lastAlert)) {
                        sp.edit().putString("kpAlert", time).apply();
                        int g = Math.min(5, (int) Math.floor(kp) - 4);
                        Notif.show(ctx, 200, "Tempesta geomagnetica G" + g,
                                String.format(Locale.ITALY, "Kp = %.2f (%s UTC)", kp, time.replace(".000", "")), "#/d/kp");
                    }
                }
            } catch (Exception ignored) { }
        }
    }

    private static String metaSignature(String url) throws Exception {
        Net.Result h = Net.head(url);
        if (h.code < 400) {
            String sig = (h.lastModified != null ? h.lastModified : "") + "|" + (h.etag != null ? h.etag : "")
                    + "|" + (h.length > 0 ? h.length : "");
            if (!sig.equals("||")) return sig;
        }
        Net.Result r = Net.get(url, "bytes=-400");
        if (r.code >= 400) return null;
        return lastLine(new String(r.body, StandardCharsets.UTF_8));
    }

    private static String lastLine(String text) {
        String[] lines = text.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) return lines[i].trim();
        }
        return "";
    }

    private static String describe(String id, String line) {
        try {
            if ("tele".equals(id)) {
                String[] p = line.split(",");
                return String.format(Locale.ITALY, "NAO del %s/%s/%s: %+.2f — tocca per vedere anche AO e PNA",
                        p[2].trim(), p[1].trim(), p[0].trim(), Double.parseDouble(p[3].trim()));
            }
            if ("enso".equals(id)) {
                String[] p = line.trim().split("\\s+");
                return String.format(Locale.ITALY, "ONI %s %s: %+.2f °C", p[0], p[1], Double.parseDouble(p[3]));
            }
        } catch (Exception ignored) { }
        return null;
    }
}
