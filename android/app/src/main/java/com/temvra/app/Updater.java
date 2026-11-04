package com.temvra.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Actualizaciones desde las Releases de GitHub: una vez al día mira si hay una versión más nueva que la instalada.
 * Según lo elegido en la app, avisa con una notificación o descarga el APK y deja la instalación a un toque
 * (Android siempre pide confirmar la instalación).
 */
final class Updater {
    private Updater() {}

    static final String PREFS = "temvra_update";
    static final String REPO = "temvra/temvP";
    static final String CH = "updates";
    static final int NOTIF_ID = 7301;

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static String installed(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "0"; }
    }

    /** Compara «1.0.27» con «v1.0.30» número a número. */
    static int compare(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\."), y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0, q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static HttpURLConnection open(String url, String token, String accept) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setInstanceFollowRedirects(false);
        h.setRequestProperty("User-Agent", "Temvra");
        h.setRequestProperty("Accept", accept);
        if (token != null && !token.isEmpty()) h.setRequestProperty("Authorization", "Bearer " + token);
        return h;
    }

    /** Última versión publicada: {tag, name, body, apkUrl, apkName, size}. */
    static JSONObject latest(String token) throws Exception {
        HttpURLConnection h = open("https://api.github.com/repos/" + REPO + "/releases/latest", token, "application/vnd.github+json");
        int code = h.getResponseCode();
        if (code != 200) throw new Exception(code == 404 || code == 401 ? "auth" : "http " + code);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = h.getInputStream()) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) buf.write(b, 0, n); }
        JSONObject r = new JSONObject(buf.toString("UTF-8")), out = new JSONObject();
        out.put("tag", r.optString("tag_name"));
        out.put("name", r.optString("name"));
        out.put("body", r.optString("body"));
        out.put("url", r.optString("html_url"));
        JSONArray as = r.optJSONArray("assets");
        for (int i = 0; as != null && i < as.length(); i++) {
            JSONObject a = as.optJSONObject(i);
            if (a != null && a.optString("name").endsWith(".apk")) {
                out.put("apkUrl", a.optString("url"));
                out.put("apkName", a.optString("name"));
                out.put("size", a.optLong("size"));
            }
        }
        return out;
    }

    /** Descarga un archivo de la release. El enlace firmado al que redirige GitHub no admite el token: se quita. */
    static File download(Context c, String assetUrl, String token, String name) throws Exception {
        HttpURLConnection h = open(assetUrl, token, "application/octet-stream");
        int hops = 0;
        while ((h.getResponseCode() / 100) == 3 && hops++ < 5) {
            String loc = h.getHeaderField("Location");
            h.disconnect();
            h = open(loc, null, "application/octet-stream");
        }
        if (h.getResponseCode() != 200) throw new Exception("http " + h.getResponseCode());
        File dir = new File(c.getCacheDir(), "updates");
        if (!dir.exists()) dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (!f.getName().equals(name)) f.delete();
        File tmp = new File(dir, name + ".part"), out = new File(dir, name);
        try (InputStream in = h.getInputStream(); OutputStream o = new FileOutputStream(tmp)) {
            byte[] b = new byte[64 * 1024]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) throw new Exception("rename");
        return out;
    }

    static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    static Intent installIntent(Context c, File apk) {
        Uri u = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", apk);
        return new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Comprobación diaria, aunque la app esté cerrada. */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, UpdateReceiver.class).setAction(UpdateReceiver.ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (!sp(c).getBoolean("on", true)) { am.cancel(pi); return; }
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR * 3, AlarmManager.INTERVAL_DAY, pi);
    }

    private static void channel(Context c, NotificationManager nm) {
        if (Build.VERSION.SDK_INT < 26 || nm.getNotificationChannel(CH) != null) return;
        NotificationChannel ch = new NotificationChannel(CH, "Actualizaciones", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Aviso cuando hay una versión nueva de Temvra");
        nm.createNotificationChannel(ch);
    }

    /** Notificación de versión nueva: con el APK ya descargado abre el instalador; si no, abre la app. */
    static void notifyNew(Context c, String tag, File apk) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        channel(c, nm);
        Intent open = apk != null && canInstall(c) ? installIntent(c, apk)
                : new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(TemvraPlugin.EXTRA_ACTION, "update");
        PendingIntent pi = PendingIntent.getActivity(c, NOTIF_ID, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String v = tag.replaceFirst("^v", "");
        Notification n = new NotificationCompat.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat_temvra)
                .setContentTitle("Temvra " + v + " disponible")
                .setContentText(apk != null ? "Ya está descargada. Toca para instalarla." : "Toca para descargarla e instalarla.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIF_ID, n);
    }
}
