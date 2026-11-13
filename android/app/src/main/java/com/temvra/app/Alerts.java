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
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

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
    static final String ACTION_REFRESH = "com.temvra.app.ALERT_REFRESH";

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
                armExact(am, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /*
     * El móvil de pruebas (ColorOS 17) trata las alarmas exactas como si no lo fueran: les añade una ventana del 75 % de lo que falta
     * y las entrega al final (una actualización pedida para las 08:49 llegó a las 08:50:58). Se pide antes, para que esa ventana acabe
     * justo a la hora buena; si llega antes, quien la recibe ve que aún no toca y la vuelve a pedir con lo que queda.
     * Con menos de 10 s de margen el sistema ya no añade ventana.
     */
    static void armExact(AlarmManager am, long at, PendingIntent pi) {
        long now = System.currentTimeMillis(), d = at - now, t = d > 10_000 ? now + (long) (d / 1.75) : at;
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi); }
        catch (SecurityException e) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi); }
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
        if (a.optLong("start") > 0 && System.currentTimeMillis() >= expiry(a)) return;
        JSONObject cfg = cfg(c);
        if (cfg.optBoolean("vib", true)) {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 250, 120, 250}, -1));
                else v.vibrate(new long[]{0, 250, 120, 250}, -1);
            }
        }
        postReminder(c, a, !cfg.optBoolean("soundOn", true));
        if (a.optLong("start") > 0) { remember(c, a); armRefresh(c); }
    }

    /*
     * El texto dice lo que falta en el momento de mostrarse («En 15 min», «Ahora»). Antes se escribía al programarlo y la notificación
     * seguía diciendo «En 15 min» cuando ya había empezado. Se rehace cada pocos minutos (ajuste de la app), al empezar, y se quita sola al acabar.
     */
    private static void postReminder(Context c, JSONObject a, boolean silent) {
        ensureChannels(c);
        long now = System.currentTimeMillis(), start = a.optLong("start");
        String rel = relText(start, a.optBoolean("allDay"), now), txt = rel.isEmpty() ? a.optString("body") : rel + " · " + a.optString("body");
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_REMINDER)
                .setSmallIcon(R.drawable.ic_stat_temvra)
                .setContentTitle(a.optString("title"))
                .setContentText(txt)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(txt))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(tapIntent(c, a))
                .addAction(0, "Posponer", receiverIntent(c, ACTION_SNOOZE, a))
                .addAction(0, "Ignorar", receiverIntent(c, ACTION_DISMISS, a));
        if (start > 0) {
            b.setWhen(start).setShowWhen(true).setTimeoutAfter(Math.max(1000, expiry(a) - now));
            // Cuenta atrás en la cabecera: siempre exacta, aunque el sistema retrase la siguiente actualización
            if (!a.optBoolean("allDay") && start > now) b.setUsesChronometer(true).setChronometerCountDown(true);
        }
        if (silent) b.setSilent(true);
        post(c, idOf(a), b.build());
    }

    static String relText(long start, boolean allDay, long now) {
        if (start <= 0) return "";
        int days = dayDiff(now, start);
        if (allDay) return days <= 0 ? "Hoy" : days == 1 ? "Mañana" : "En " + days + " días";
        long mins = Math.round((start - now) / 60000.0);
        if (mins <= 0) return "Ahora";
        if (mins >= 1440) return days == 1 ? "Mañana" : "En " + days + " días";
        if (mins >= 60) { long h = mins / 60, m = mins % 60; return "En " + h + " h" + (h < 3 && m > 0 ? " " + m + " min" : ""); }
        return "En " + mins + " min";
    }

    private static int dayDiff(long a, long b) {
        Calendar x = Calendar.getInstance(), y = Calendar.getInstance();
        x.setTimeInMillis(a); y.setTimeInMillis(b);
        for (Calendar k : new Calendar[]{x, y}) { k.set(Calendar.HOUR_OF_DAY, 12); k.set(Calendar.MINUTE, 0); k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0); }
        return (int) Math.round((y.getTimeInMillis() - x.getTimeInMillis()) / 86_400_000.0);
    }

    /** Cuándo deja de tener sentido: al acabar el evento, a la media hora de empezar si no tiene fin, o al terminar el día si es de todo el día. */
    static long expiry(JSONObject a) {
        long start = a.optLong("start"), end = a.optLong("end");
        if (a.optBoolean("allDay")) {
            Calendar k = Calendar.getInstance();
            k.setTimeInMillis(start);
            k.set(Calendar.HOUR_OF_DAY, 23); k.set(Calendar.MINUTE, 59); k.set(Calendar.SECOND, 59);
            return k.getTimeInMillis();
        }
        return end > start ? end : start + 30 * 60_000L;
    }

    private static JSONArray shown(Context c) {
        try { return new JSONArray(sp(c).getString("shown", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static void remember(Context c, JSONObject a) {
        JSONArray l = shown(c), out = new JSONArray();
        String k = a.optString("key");
        for (int i = 0; i < l.length(); i++) { JSONObject o = l.optJSONObject(i); if (o != null && !k.equals(o.optString("key"))) out.put(o); }
        out.put(a);
        sp(c).edit().putString("shown", out.toString()).apply();
    }

    static void forget(Context c, JSONObject a) {
        JSONArray l = shown(c), out = new JSONArray();
        int id = idOf(a);
        for (int i = 0; i < l.length(); i++) { JSONObject o = l.optJSONObject(i); if (o != null && idOf(o) != id) out.put(o); }
        sp(c).edit().putString("shown", out.toString()).apply();
        armRefresh(c);
    }

    /** Rehace las notificaciones de eventos que siguen en la barra y quita las que ya no tienen sentido. */
    static void refreshShown(Context c) {
        JSONArray l = shown(c), keep = new JSONArray();
        Set<Integer> active = activeIds(c);
        long now = System.currentTimeMillis();
        NotificationManager nm = nm(c);
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            if (o == null) continue;
            int id = idOf(o);
            // Si ya no está en la barra (la abrió o la quitó) no se vuelve a poner
            if (active != null && !active.contains(id)) continue;
            if (now >= expiry(o)) { if (nm != null) nm.cancel(id); continue; }
            keep.put(o);
        }
        sp(c).edit().putString("shown", keep.toString()).apply();
        for (int i = 0; i < keep.length(); i++) postReminder(c, keep.optJSONObject(i), true);
        armRefresh(c);
    }

    private static Set<Integer> activeIds(Context c) {
        NotificationManager nm = nm(c);
        if (nm == null || Build.VERSION.SDK_INT < 23) return null;
        Set<Integer> s = new HashSet<>();
        try { for (StatusBarNotification n : nm.getActiveNotifications()) s.add(n.getId()); } catch (Exception e) { return null; }
        return s;
    }

    private static PendingIntent refreshIntent(Context c) {
        Intent it = new Intent(c, AlertReceiver.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(c, 51, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Próxima actualización: cada N minutos mientras falta tiempo, justo al empezar y al caducar. */
    static void armRefresh(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = refreshIntent(c);
        JSONArray l = shown(c);
        long now = System.currentTimeMillis(), next = Long.MAX_VALUE;
        int every = cfg(c).optInt("refresh", 10);
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            if (o == null) continue;
            long st = o.optLong("start");
            if (st > now) { next = Math.min(next, st); if (every > 0) next = Math.min(next, now + every * 60_000L); }
            next = Math.min(next, expiry(o));
        }
        if (next == Long.MAX_VALUE) { am.cancel(pi); sp(c).edit().remove("rfAt").apply(); return; }
        next = Math.max(next, now + 1000);
        sp(c).edit().putLong("rfAt", next).apply();
        armExact(am, next, pi);
    }

    /** La alarma de actualización puede llegar antes de tiempo a propósito (ver armExact): si aún no toca, solo se vuelve a pedir. */
    static void refreshDue(Context c) {
        long at = sp(c).getLong("rfAt", 0);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null && at > System.currentTimeMillis() + 1500) { armExact(am, at, refreshIntent(c)); return; }
        refreshShown(c);
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
