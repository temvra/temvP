package com.temvra.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;

/** Comprobación diaria de versión nueva (y reprogramarla al encender el móvil o al actualizar la app). */
public class UpdateReceiver extends BroadcastReceiver {
    static final String ACTION = "com.temvra.app.UPDATE_CHECK";

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!ACTION.equals(intent.getAction())) { Updater.schedule(c); return; }
        SharedPreferences sp = Updater.sp(c);
        if (!sp.getBoolean("on", true)) return;
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                String token = sp.getString("token", "");
                JSONObject r = Updater.latest(token);
                String tag = r.optString("tag");
                sp.edit().putLong("last", System.currentTimeMillis()).apply();
                if (tag.isEmpty() || Updater.compare(Updater.installed(c), tag) >= 0) return;
                if (tag.equals(sp.getString("skip", "")) || tag.equals(sp.getString("notified", ""))) return;
                File apk = null;
                if ("auto".equals(sp.getString("mode", "ask")) && !r.optString("apkUrl").isEmpty())
                    apk = Updater.download(c, r.optString("apkUrl"), token, r.optString("apkName", "Temvra.apk"));
                Updater.notifyNew(c, tag, apk);
                sp.edit().putString("notified", tag).putString("apkPath", apk != null ? apk.getAbsolutePath() : "").apply();
            } catch (Exception ignored) {
            } finally {
                pr.finish();
            }
        }).start();
    }
}
