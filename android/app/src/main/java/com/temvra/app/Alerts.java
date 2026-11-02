package com.temvra.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Avisos programados (alarmas, temporizador, eventos y recordatorios).
 * Guarda la lista y mantiene armado solo el siguiente: al sonar se arma el otro, y sobrevive a reinicios.
 */
public final class Alerts {
    static final String PREFS = "temvra_alerts";
    static final String CH_ALARM = "t_alarm", CH_TIMER = "t_timer", CH_REMINDER = "t_reminder_v2";
    static final String ACTION_FIRE = "com.temvra.app.ALERT_FIRE";
    static final String ACTION_DISMISS = "com.temvra.app.ALERT_DISMISS";
    static final String ACTION_SNOOZE = "com.temvra.app.ALERT_SNOOZE";

    private Alerts() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static JSONArray list(Context c) {
        try { return new JSONArray(sp(c).getString("list", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static JSONObject cfg(Context c) {
        try { return new JSONObject(sp(c).getString("cfg", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static void store(Context c, JSONArray l) { sp(c).edit().putString("list", l.toString()).apply(); }

    /** Sustituye los avisos programados. Los pospuestos (keep) se conservan: solo existen aquí. */
    static void setAll(Context c, JSONArray incoming, JSONObject cfg) {
        JSONArray out = keepOnly(c);
        for (int i = 0; i < incoming.length(); i++) out.put(incoming.optJSONObject(i));
        sp(c).edit().putString("list", out.toString()).putString("cfg", cfg != null ? cfg.toString() : "{}").apply();
        JSONObject t = cfg != null ? cfg.optJSONObject("timer") : null;
        if (t != null) {
            try { t.put("active", true); } catch (Exception ignored) {}
            TimerCtl.save(c, t);
            TimerCtl.sync(c);
        } else armNext(c);
    }

    static void clear(Context c) {
        store(c, keepOnly(c));
        armNext(c);
    }

    private static JSONArray keepOnly(Context c) {
        JSONArray cur = list(c), out = new JSONArray();
        for (int i = 0; i < cur.length(); i++) {
            JSONObject o = cur.optJSONObject(i);
            if (o != null && o.optBoolean("keep")) out.put(o);
        }
        return out;
    }

    static void add(Context c, JSONObject a) {
        JSONArray l = list(c);
        l.put(a);
        store(c, l);
        armNext(c);
    }

    private static PendingIntent fireIntent(Context c) {
        Intent it = new Intent(c, AlertReceiver.class).setAction(ACTION_FIRE);
        return PendingIntent.getBroadcast(c, 50, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void armNext(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = fireIntent(c);
        JSONArray l = list(c);
        JSONObject next = null;
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            if (o != null && (next == null || o.optLong("at") < next.optLong("at"))) next = o;
        }
        if (next == null) { am.cancel(pi); return; }
        long at = Math.max(next.optLong("at"), System.currentTimeMillis() + 500);
        String ch = next.optString("channel");
        try {
            if ("alarms".equals(ch) || "timer".equals(ch)) {
                // setAlarmClock no se retrasa nunca, ni con el móvil en reposo profundo
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, openIntent(c, "open", 60, null)), pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** Lanza lo que ya toque y arma el siguiente. */
    static void fireDue(Context c) {
        long now = System.currentTimeMillis();
        JSONArray l = list(c), rest = new JSONArray();
        JSONObject alarm = null, timer = null;
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            if (o == null) continue;
            if (o.optLong("at") > now + 1500) {
                rest.put(o);
                continue;
            }
            String ch = o.optString("channel");
            if ("alarms".equals(ch)) alarm = o;
            else if ("timer".equals(ch)) { if (timer == null || o.optLong("at") > timer.optLong("at")) timer = o; }
            else notifyReminder(c, o);
        }
        store(c, rest);
        armNext(c);
        if (alarm != null) {
            markFired(c, alarm);
            AlarmService.start(c, alarm, true);
        }
        if (timer != null) {
            // En bucle basta la cuenta de la notificación fija; solo se avisa aparte cuando termina
            if (!timer.optBoolean("loop")) notifyTimer(c, timer);
            if (alarm == null) AlarmService.start(c, timer, false);
            TimerCtl.sync(c);
        }
    }

    private static void markFired(Context c, JSONObject a) {
        try {
            JSONArray f = new JSONArray(sp(c).getString("fired", "[]"));
            JSONObject ex = a.optJSONObject("extra");
            f.put(new JSONObject().put("id", ex != null ? ex.opt("id") : JSONObject.NULL).put("at", a.optLong("at")));
            sp(c).edit().putString("fired", f.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Alarmas que sonaron mientras la app estaba cerrada (la app las marca como hechas). */
    static JSONArray consumeFired(Context c) {
        JSONArray f;
        try { f = new JSONArray(sp(c).getString("fired", "[]")); } catch (Exception e) { f = new JSONArray(); }
        sp(c).edit().remove("fired").apply();
        return f;
    }

    /* ---------------- Notificaciones ---------------- */

    static NotificationManager nm(Context c) { return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE); }

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = nm(c);
        if (nm == null) return;
        ensureLive(c);
        // Los canales antiguos los creaba el plugin de notificaciones; ya no se usan
        for (String old : new String[]{"alarms", "reminders", "timer", "t_reminder"}) nm.deleteNotificationChannel(old);
        if (nm.getNotificationChannel(CH_ALARM) == null) {
            NotificationChannel a = new NotificationChannel(CH_ALARM, "Alarmas", NotificationManager.IMPORTANCE_HIGH);
            a.setDescription("Alarmas: suenan y se muestran con el móvil bloqueado");
            a.setSound(null, null); // El sonido lo pone AlarmService, con el volumen elegido en la app
            a.setBypassDnd(true);
            a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(a);
        }
        if (nm.getNotificationChannel(CH_TIMER) == null) {
            NotificationChannel t = new NotificationChannel(CH_TIMER, "Fin del temporizador", NotificationManager.IMPORTANCE_HIGH);
            t.setDescription("Fin de cada vuelta del temporizador");
            t.setSound(null, null);
            t.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(t);
        }
        if (nm.getNotificationChannel(CH_REMINDER) == null) {
            NotificationChannel r = new NotificationChannel(CH_REMINDER, "Eventos y recordatorios", NotificationManager.IMPORTANCE_HIGH);
            r.setDescription("Avisos de eventos, tareas y bloques que se acercan");
            r.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            // La vibración la decide la app, para poder elegir solo sonido, solo vibración o ambos
            r.enableVibration(false);
            r.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(r);
        }
    }

    static int idOf(JSONObject a) { return (a.optString("key", "x").hashCode() & 0x3fffffff) + 1000; }

    static PendingIntent openIntent(Context c, String action, int code, String payload) {
        Intent it = new Intent(c, MainActivity.class);
        it.setAction("com.temvra.app." + action + (payload != null ? "." + code : ""));
        it.putExtra(TemvraPlugin.EXTRA_ACTION, payload != null ? action + ":" + payload : action);
        it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, code, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent receiverIntent(Context c, String action, JSONObject a) {
        Intent it = new Intent(c, AlertReceiver.class).setAction(action);
        it.putExtra("alert", a.toString());
        return PendingIntent.getBroadcast(c, idOf(a) ^ action.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent tapIntent(Context c, JSONObject a) {
        JSONObject ex = a.optJSONObject("extra");
        return openIntent(c, "alert", idOf(a), ex != null ? ex.toString() : "{}");
    }

    static void notifyReminder(Context c, JSONObject a) {
        ensureChannels(c);
        JSONObject cfg = cfg(c);
        if (cfg.optBoolean("vib", true)) {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 250, 120, 250}, -1));
                else v.vibrate(new long[]{0, 250, 120, 250}, -1);
            }
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_REMINDER)
                .setSmallIcon(R.drawable.ic_stat_temvra)
                .setContentTitle(a.optString("title"))
                .setContentText(a.optString("body"))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(a.optString("body")))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(tapIntent(c, a))
                .addAction(0, "Posponer", receiverIntent(c, ACTION_SNOOZE, a))
                .addAction(0, "Ignorar", receiverIntent(c, ACTION_DISMISS, a));
        if (!cfg.optBoolean("soundOn", true)) b.setSilent(true);
        post(c, idOf(a), b.build());
    }

    static void notifyTimer(Context c, JSONObject a) {
        ensureChannels(c);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_TIMER)
                .setSmallIcon(R.drawable.ic_stat_temvra)
                .setContentTitle(a.optString("title"))
                .setContentText(a.optString("body"))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
                .setContentIntent(tapIntent(c, a));
        post(c, 7002, b.build());
    }

    static PendingIntent timerAction(Context c, String action, int code) {
        Intent it = new Intent(c, AlertReceiver.class).setAction(AlertReceiver.ACTION_TIMER).putExtra("act", action);
        return PendingIntent.getBroadcast(c, code, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void ensureLive(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = nm(c);
        if (nm == null) return;
        // El canal de importancia baja no se ve en la pantalla de bloqueo: se sustituye
        nm.deleteNotificationChannel("timer_live");
        if (nm.getNotificationChannel(TemvraPlugin.CH_LIVE) != null) return;
        NotificationChannel ch = new NotificationChannel(TemvraPlugin.CH_LIVE, "Temporizador en marcha", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setDescription("Cuenta atrás del temporizador en la barra y en la pantalla de bloqueo");
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private static void post(Context c, int id, Notification n) {
        try { NotificationManager nm = nm(c); if (nm != null) nm.notify(id, n); } catch (SecurityException ignored) {}
    }
}
