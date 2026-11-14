package it.indicimeteo.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.security.MessageDigest;

/*
 * Indici Meteo — © Biagio Paparella. Tutti i diritti riservati.
 * Il marchio di copyright viene inserito dal codice nativo dell'app in ogni pagina,
 * quindi non dipende dai file dell'interfaccia e non sparisce con gli aggiornamenti.
 * Se l'app viene modificata e ri-firmata da altri, compare l'avviso "copia non originale".
 */
final class Firma {
    private static final int[] A = {66,105,97,103,105,111,32,80,97,112,97,114,101,108,108,97};
    private static final String S = "64FDD2259724CC1D1FE9FB921248D80A0AA2BD969B85DE6FDD6F7DC0E9C1C94F";

    static String autore() {
        StringBuilder b = new StringBuilder();
        for (int c : A) b.append((char) c);
        return b.toString();
    }

    static String testo() {
        return "\u00A9 " + java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + " " + autore() + " \u00B7 Indici Meteo \u00B7 Tutti i diritti riservati";
    }

    @SuppressWarnings("deprecation")
    static boolean originale(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            for (Signature sg : pi.signatures) {
                byte[] h = MessageDigest.getInstance("SHA-256").digest(sg.toByteArray());
                StringBuilder x = new StringBuilder();
                for (byte v : h) x.append(String.format("%02X", v));
                if (S.equals(x.toString())) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** Script iniettato a ogni caricamento: rimette la firma ogni volta che la pagina cambia. */
    static String script(Context ctx) {
        String t = testo().replace("'", "\\'");
        String warn = originale(ctx) ? "" :
                "Copia non originale: questa app \u00E8 stata modificata senza autorizzazione. Autore: " + autore();
        return "(function(){var T='" + t + "',W='" + warn.replace("'", "\\'") + "';" +
                "function put(){var a=document.getElementById('app');if(!a)return;" +
                "var e=document.getElementById('__cr');" +
                "if(!e||e.parentNode!==a||a.lastElementChild!==e||e.textContent!==T){if(e)e.remove();" +
                "e=document.createElement('div');e.id='__cr';e.textContent=T;" +
                "e.style.cssText='display:block!important;visibility:visible!important;opacity:1!important;text-align:center;font-size:11px;color:var(--mut,#8a93a6);margin:18px 0 6px;padding:4px';" +
                "a.appendChild(e);}" +
                "if(W&&!document.getElementById('__cw')){var w=document.createElement('div');w.id='__cw';w.textContent=W;" +
                "w.style.cssText='position:fixed;top:0;left:0;right:0;z-index:99;background:#c92a2a;color:#fff;font-size:12px;padding:6px;text-align:center';document.body.appendChild(w);}}" +
                "put();if(!window.__crObs){window.__crObs=new MutationObserver(put);" +
                "window.__crObs.observe(document.body,{childList:true,subtree:true,characterData:true});}" +
                "setInterval(put,3000);})();";
    }

    private Firma() { }
}
