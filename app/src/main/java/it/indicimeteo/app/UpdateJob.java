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
        // controllo frequente (default ogni 30 minuti) così la notifica arriva poco dopo l'uscita del dato;
        // si scaricano solo pochi KB per fonte
        long interval = Math.max(15, p.optInt("intervalMin", 30)) * 60_000L;
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

        if (on.optBoolean("tele", false)) {
            try { checkTele(ctx, sp); } catch (Exception ignored) { }
        }

        for (String[] s : SOURCES) {
            nid++;
            if (!on.optBoolean(s[0], false)) continue;
            try {
                // scarico solo la parte finale del file e ne ricavo la data dell'ultimo dato:
                // si notifica solo quando questa data è PIÙ RECENTE di quella già vista
                Net.Result r = Net.get(s[2], "bytes=-6000");
                if (r.code == 416) r = Net.get(s[2], null);
                if (r.code >= 400) continue;
                String text = new String(r.body, StandardCharsets.UTF_8);
                String[] kd = dataKey(s[0], s[2], text);
                if (kd == null || kd[0] == null || kd[0].isEmpty()) continue;
                String key = "last:" + s[2];
                String old = sp.getString(key, null);
                if (old == null || kd[0].compareTo(old) > 0) {
                    sp.edit().putString(key, kd[0]).apply();
                    if (old != null) {
                        Notif.show(ctx, nid, "Aggiornato: " + s[1],
                                kd[1] != null ? kd[1] : "È disponibile un nuovo dato. Tocca per vederlo.", s[4]);
                    }
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

    private static final String[][] TELE = {
            {"NAO", CPC + "norm.daily.nao.cdas.z500.19500101_current.csv"},
            {"AO", CPC + "norm.daily.ao.cdas.z1000.19500101_current.csv"},
            {"PNA", CPC + "norm.daily.pna.cdas.z500.19500101_current.csv"}
    };

    /** Un'unica notifica quando NAO, AO e PNA hanno TUTTI un nuovo valore giornaliero. */
    static void checkTele(Context ctx, SharedPreferences sp) throws Exception {
        String minKey = null;
        StringBuilder txt = new StringBuilder();
        for (String[] t : TELE) {
            Net.Result r = Net.get(t[1], "bytes=-3000");
            if (r.code == 416) r = Net.get(t[1], null);
            if (r.code >= 400) return;
            String[] lines = new String(r.body, StandardCharsets.UTF_8).split("\\r?\\n");
            String key = null; double v = 0;
            for (int i = lines.length - 1; i >= 0 && key == null; i--) {
                String[] p = lines[i].trim().split(",");
                if (p.length >= 4 && p[0].trim().matches("\\d{4}")) {
                    try {
                        v = Double.parseDouble(p[3].trim());
                        key = p[0].trim() + "-" + p2(Integer.parseInt(p[1].trim())) + "-" + p2(Integer.parseInt(p[2].trim()));
                    } catch (Exception ignored) { }
                }
            }
            if (key == null) return;
            if (minKey == null || key.compareTo(minKey) < 0) minKey = key;
            if (txt.length() > 0) txt.append(" · ");
            txt.append(t[0]).append(' ').append(String.format(Locale.ITALY, "%+.2f", v));
        }
        String old = sp.getString("last:teleAll", null);
        if (old == null || minKey.compareTo(old) > 0) {
            sp.edit().putString("last:teleAll", minKey).apply();
            if (old != null) {
                String d = minKey.substring(8) + "/" + minKey.substring(5, 7) + "/" + minKey.substring(0, 4);
                Notif.show(ctx, 150, "Aggiornati NAO, AO e PNA", "Valori del " + d + ": " + txt, "#/d/nao");
            }
        }
    }

    private static final String[] MON = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
    private static final String[] SEAS = {"DJF", "JFM", "FMA", "MAM", "AMJ", "MJJ", "JJA", "JAS", "ASO", "SON", "OND", "NDJ"};

    private static String p2(int v) { return v < 10 ? "0" + v : String.valueOf(v); }

    /** Restituisce {chiave ordinabile dell'ultimo dato, testo della notifica} oppure null. */
    static String[] dataKey(String id, String url, String text) {
        String[] lines = text.split("\\r?\\n");
        try {
            if (url.endsWith(".json")) { // ciclo solare: ultimo "time-tag":"YYYY-MM"
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"time-tag\"\\s*:\\s*\"(\\d{4}-\\d{2})").matcher(text);
                String last = null;
                while (m.find()) last = m.group(1);
                return last == null ? null : new String[]{last, "Nuovo dato mensile: " + last.substring(5) + "/" + last.substring(0, 4)};
            }
            for (int i = lines.length - 1; i >= 0; i--) {
                String l = lines[i].trim();
                if (l.isEmpty()) continue;
                if (url.contains(".cdas.")) { // year,month,day,valore
                    String[] p = l.split(",");
                    if (p.length >= 4 && p[0].trim().matches("\\d{4}")) {
                        String k = p[0].trim() + "-" + p2(Integer.parseInt(p[1].trim())) + "-" + p2(Integer.parseInt(p[2].trim()));
                        return new String[]{k, String.format(Locale.ITALY, "NAO del %s/%s/%s: %+.2f — tocca per vedere anche AO e PNA",
                                p2(Integer.parseInt(p[2].trim())), p2(Integer.parseInt(p[1].trim())), p[0].trim(), Double.parseDouble(p[3].trim()))};
                    }
                } else if (url.contains(".gefs.")) { // lead,member,time,index,valid_time
                    String[] p = l.split(",");
                    if (p.length >= 5 && p[2].trim().matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                        String init = p[2].trim().substring(0, 10);
                        return new String[]{init, "Nuova corsa GEFS del " + init.substring(8) + "/" + init.substring(5, 7) + ": previsioni a 15 giorni aggiornate"};
                    }
                } else if (url.contains("rmm.")) { // year month day rmm1 rmm2 phase amp
                    String[] p = l.split("\\s+");
                    if (p.length >= 7 && p[0].matches("\\d{4}")) {
                        double amp = Double.parseDouble(p[6]);
                        if (amp > 900) continue;
                        String k = p[0] + "-" + p2(Integer.parseInt(p[1])) + "-" + p2(Integer.parseInt(p[2]));
                        return new String[]{k, String.format(Locale.ITALY, "Dato del %s/%s: fase %s, ampiezza %.2f", p2(Integer.parseInt(p[2])), p2(Integer.parseInt(p[1])), p[5], amp)};
                    }
                } else if (url.contains("wksst")) { // 03JAN1990 ...
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{2})([A-Za-z]{3})(\\d{4})").matcher(l);
                    if (m.find()) {
                        int mo = java.util.Arrays.asList(MON).indexOf(m.group(2).toUpperCase()) + 1;
                        return new String[]{m.group(3) + "-" + p2(mo) + "-" + m.group(1), "Nuovo dato settimanale Niño 3.4 (settimana del " + m.group(1) + "/" + p2(mo) + ")"};
                    }
                } else if (url.contains("oni.")) { // SEAS YR TOTAL ANOM
                    String[] p = l.split("\\s+");
                    int si = p.length >= 4 ? java.util.Arrays.asList(SEAS).indexOf(p[0].toUpperCase()) : -1;
                    if (si >= 0) return new String[]{p[1] + "-" + p2(si), String.format(Locale.ITALY, "ONI %s %s: %+.2f °C", p[0], p[1], Double.parseDouble(p[3]))};
                } else if (url.contains("tele_index")) { // yyyy mm valori
                    String[] p = l.split("\\s+");
                    if (p.length >= 11 && p[0].matches("\\d{4}") && p[1].matches("\\d{1,2}")) {
                        return new String[]{p[0] + "-" + p2(Integer.parseInt(p[1])), "Nuovi valori mensili di " + p2(Integer.parseInt(p[1])) + "/" + p[0]};
                    }
                } else if (url.contains("psl.noaa.gov")) { // anno + 12 valori, -999 = mancante
                    String[] p = l.split("\\s+");
                    if (p.length == 13 && p[0].matches("\\d{4}")) {
                        int n = 0;
                        for (int j = 1; j < 13; j++) if (Double.parseDouble(p[j]) > -99) n++;
                        if (n == 0) continue;
                        return new String[]{p[0] + "-" + p2(n), "Nuovo dato mensile: " + p2(n) + "/" + p[0]};
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
