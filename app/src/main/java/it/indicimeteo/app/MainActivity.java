package it.indicimeteo.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.res.Configuration;
import android.os.Environment;
import android.view.View;
import android.widget.Toast;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {

    static final String HOST = "appassets.androidplatform.net";
    static final String BASE = "https://" + HOST + "/";
    private WebView web;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0f1420"));
        getWindow().setNavigationBarColor(Color.parseColor("#121a29"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0f1420"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    geoCallback = callback;
                    geoOrigin = origin;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, 2);
                }
            }
        });

        String route = getIntent() != null ? getIntent().getStringExtra("route") : null;
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(BASE + "index.html" + (route != null ? route : ""));
        }
        UpdateJob.schedule(this);
        checkWebUpdate(false);
    }

    boolean isNightNow() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    void applyBars(boolean light) {
        int bg = Color.parseColor(light ? "#f3f5f9" : "#0f1420");
        int nav = Color.parseColor(light ? "#ffffff" : "#121a29");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(nav);
        web.setBackgroundColor(bg);
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (light) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (web != null) web.evaluateJavascript("window.__applyTheme && window.__applyTheme()", null);
    }

    /**
     * Scarica il grafico con le stesse credenziali della WebView (User-Agent, Referer, cookie),
     * controlla che sia davvero un'immagine e la salva in Immagini/IndiciMeteo.
     */
    void downloadImage(final String url, final String name) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 3);
            Toast.makeText(this, "Consenti l'accesso alla memoria e tocca di nuovo Scarica", Toast.LENGTH_LONG).show();
            return;
        }
        final String ua = web.getSettings().getUserAgentString();
        String ck = null;
        try { ck = android.webkit.CookieManager.getInstance().getCookie(url); } catch (Exception ignored) { }
        final String cookie = ck;
        Toast.makeText(this, "Scarico il grafico…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                Uri u = Uri.parse(url);
                Map<String, String> h = new HashMap<>();
                h.put("User-Agent", ua);
                h.put("Referer", u.getScheme() + "://" + u.getHost() + "/");
                h.put("Accept", "image/avif,image/webp,image/png,image/gif,image/jpeg,image/*,*/*;q=0.8");
                h.put("Cookie", cookie);
                Net.Result r = Net.getAsBrowser(url, h);
                String ext = imageExt(r.body);
                if ((r.code >= 400 || ext == null) && url.contains("?")) { // riprova senza parametri
                    r = Net.getAsBrowser(url.substring(0, url.indexOf('?')), h);
                    ext = imageExt(r.body);
                }
                if (r.code >= 400) throw new Exception("il sito risponde " + r.code);
                if (ext == null) throw new Exception("il sito non ha inviato un'immagine");
                String base = (name == null ? "grafico" : name).replaceAll("\\.(png|gif|jpe?g|webp)$", "").replaceAll("[^A-Za-z0-9_.-]", "_");
                saveToPictures(base + "." + ext, ext, r.body);
                msg = "Grafico salvato in Immagini/IndiciMeteo";
            } catch (Exception e) {
                msg = "Download non riuscito: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    static String imageExt(byte[] b) {
        if (b == null || b.length < 12) return null;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "gif";
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "jpg";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "webp";
        return null;
    }

    void saveToPictures(String file, String ext, byte[] data) throws Exception {
        String mime = "jpg".equals(ext) ? "image/jpeg" : "image/" + ext;
        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file);
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/IndiciMeteo");
            v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            android.content.ContentResolver cr = getContentResolver();
            Uri item = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (item == null) throw new Exception("memoria non disponibile");
            try (java.io.OutputStream o = cr.openOutputStream(item)) {
                if (o == null) throw new Exception("memoria non disponibile");
                o.write(data);
            }
            v.clear();
            v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, v, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "IndiciMeteo");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("impossibile creare la cartella");
            File f = new File(dir, file);
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(data); }
            android.media.MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String route = intent.getStringExtra("route");
        if (route != null) web.evaluateJavascript("location.hash='" + route.replace("'", "") + "'", null);
    }

    private long lastWebCheck = 0;

    /** Controlla in background se su GitHub c'è una versione più recente dell'interfaccia. */
    void checkWebUpdate(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastWebCheck < 20 * 60 * 1000L) return;
        lastWebCheck = now;
        new Thread(() -> {
            int before = WebUpdater.activeVersion(this);
            WebUpdater.check(this, v -> runOnUiThread(() ->
                    web.evaluateJavascript("window.__updateReady && window.__updateReady(" + v + ")", null)));
            if (force && WebUpdater.activeVersion(this) == before) {
                runOnUiThread(() -> web.evaluateJavascript("window.__updateNone && window.__updateNone()", null));
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkWebUpdate(false);
        if (web != null) web.evaluateJavascript("window.__androidResume && window.__androidResume()", null);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == 2 && geoCallback != null) {
            boolean ok = false;
            for (int r : results) if (r == PackageManager.PERMISSION_GRANTED) ok = true;
            geoCallback.invoke(geoOrigin, ok, false);
            geoCallback = null;
        }
    }

    /** Serve i file dell'interfaccia e fa da proxy verso le fonti dati (evita i blocchi CORS). */
    private class Client extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (url != null && url.startsWith(BASE)) view.evaluateJavascript(Firma.script(MainActivity.this), null);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath() == null ? "/" : u.getPath();
            if (path.equals("/proxy")) return proxy(u.getQueryParameter("u"), u.getQueryParameter("range"));
            if (path.equals("/")) path = "/index.html";
            try {
                InputStream in;
                File local = new File(WebUpdater.dir(MainActivity.this), path.substring(1));
                if (WebUpdater.useLocal(MainActivity.this) && local.exists() && !path.contains("..")) in = new FileInputStream(local);
                else in = getAssets().open("www" + path);
                return new WebResourceResponse(mime(path), "utf-8", in);
            } catch (Exception e) {
                return error(404, "Not found");
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (HOST.equals(u.getHost())) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) { }
            return true;
        }
    }

    private WebResourceResponse proxy(String target, String range) {
        if (target == null || !(target.startsWith("https://") || target.startsWith("http://")))
            return error(400, "Bad request");
        try {
            Net.Result r = Net.get(target, range);
            if (range != null && r.code == 416) r = Net.get(target, null);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-store");
            if (r.lastModified != null) h.put("Last-Modified", r.lastModified);
            String ct = r.contentType != null ? r.contentType : "text/plain";
            String mime = ct.split(";")[0].trim();
            if (r.code >= 300 && r.code < 400) r.code = 502;
            String reason = r.code >= 200 && r.code < 300 ? "OK" : "Upstream " + r.code;
            return new WebResourceResponse(mime, "utf-8", r.code, reason, h, new ByteArrayInputStream(r.body));
        } catch (Exception e) {
            return error(502, "Errore di rete: " + e.getClass().getSimpleName());
        }
    }

    private static WebResourceResponse error(int code, String msg) {
        Map<String, String> h = new HashMap<>();
        return new WebResourceResponse("text/plain", "utf-8", code, msg, h, new ByteArrayInputStream(msg.getBytes()));
    }

    private static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    /** Funzioni richiamabili dalla pagina (window.Android). */
    private class Bridge {
        @JavascriptInterface
        public String getPrefs() {
            return getSharedPreferences(UpdateJob.PREFS, MODE_PRIVATE).getString("prefs", null);
        }

        @JavascriptInterface
        public void setPrefs(String json) {
            SharedPreferences sp = getSharedPreferences(UpdateJob.PREFS, MODE_PRIVATE);
            sp.edit().putString("prefs", json).apply();
            if (json != null && json.contains("\"enabled\":true") && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1));
            }
            UpdateJob.schedule(MainActivity.this);
        }

        @JavascriptInterface
        public boolean isNight() {
            return isNightNow();
        }

        @JavascriptInterface
        public void setBars(final boolean light) {
            runOnUiThread(() -> applyBars(light));
        }

        @JavascriptInterface
        public void saveImage(final String url, final String name) {
            runOnUiThread(() -> downloadImage(url, name));
        }

        @JavascriptInterface
        public String webVersion() {
            return WebUpdater.activeVersion(MainActivity.this) + (WebUpdater.useLocal(MainActivity.this) ? " (scaricata)" : " (inclusa)");
        }

        @JavascriptInterface
        public void checkUpdate() {
            runOnUiThread(() -> checkWebUpdate(true));
        }

        @JavascriptInterface
        public void reload() {
            runOnUiThread(() -> web.reload());
        }

        @JavascriptInterface
        public void resetWeb() {
            WebUpdater.reset(MainActivity.this);
            runOnUiThread(() -> web.reload());
        }

        @JavascriptInterface
        public void testNotification() {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1));
                return;
            }
            Notif.show(MainActivity.this, 999, "Indici Meteo", "Le notifiche funzionano correttamente.", "#/set");
        }
    }
}
