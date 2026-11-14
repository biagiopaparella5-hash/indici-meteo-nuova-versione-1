package it.indicimeteo.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Aggiornamento dell'interfaccia senza reinstallare l'app.
 * Scarica i file HTML/JS dal repository pubblico su GitHub quando "version.json" ha un numero più alto
 * di quello installato, li salva nella memoria dell'app e li usa al posto di quelli inclusi nell'APK.
 */
final class WebUpdater {
    static final String BASE = "https://raw.githubusercontent.com/biagiopaparella5-hash/indici-meteo-web/main/";
    private static final String PREF = "web";

    interface Done { void onUpdated(int version); }

    static File dir(Context c) { return new File(c.getFilesDir(), "www"); }

    static int bundledVersion(Context c) {
        try (InputStream in = c.getAssets().open("www/version.json")) {
            return new JSONObject(readAll(in)).optInt("version", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    static int localVersion(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("version", 0);
    }

    /** true se va usata la copia scaricata invece di quella inclusa nell'APK */
    static boolean useLocal(Context c) {
        return localVersion(c) > bundledVersion(c) && new File(dir(c), "index.html").exists();
    }

    static int activeVersion(Context c) {
        return useLocal(c) ? localVersion(c) : bundledVersion(c);
    }

    static int appVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static void reset(Context c) {
        deleteTree(dir(c));
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("version", 0).apply();
    }

    /** Da chiamare in un thread separato. */
    static void check(Context c, Done done) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        // l'APK nuovo contiene già una versione più recente: elimino la copia vecchia
        if (localVersion(c) > 0 && localVersion(c) <= bundledVersion(c)) reset(c);
        try {
            Net.Result r = Net.get(BASE + "version.json?t=" + System.currentTimeMillis() / 60000, null);
            if (r.code != 200) return;
            JSONObject v = new JSONObject(new String(r.body, StandardCharsets.UTF_8));
            int remote = v.optInt("version", 0);
            if (remote <= activeVersion(c)) return;
            if (v.optInt("minApp", 0) > appVersionCode(c)) return; // serve un APK nuovo
            JSONArray files = v.getJSONArray("files");
            File tmp = new File(c.getFilesDir(), "www_tmp");
            deleteTree(tmp);
            for (int i = 0; i < files.length(); i++) {
                String f = files.getString(i);
                if (f.contains("..")) return;
                Net.Result fr = Net.get(BASE + f + "?v=" + remote, null);
                if (fr.code != 200 || fr.body.length == 0) { deleteTree(tmp); return; }
                File out = new File(tmp, f);
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (FileOutputStream fo = new FileOutputStream(out)) { fo.write(fr.body); }
            }
            // version.json locale, per coerenza
            try (FileOutputStream fo = new FileOutputStream(new File(tmp, "version.json"))) { fo.write(r.body); }
            if (!new File(tmp, "index.html").exists()) { deleteTree(tmp); return; }
            File d = dir(c);
            deleteTree(d);
            if (!tmp.renameTo(d)) { deleteTree(tmp); return; }
            sp.edit().putInt("version", remote).apply();
            if (done != null) done.onUpdated(remote);
        } catch (Exception ignored) {
            // senza rete o file non valido: si riprova alla prossima apertura
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] list = f.listFiles();
        if (list != null) for (File x : list) deleteTree(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private WebUpdater() { }
}
