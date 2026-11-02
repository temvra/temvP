package com.temvra.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/** Pinta los tres widgets a partir del JSON que guarda la app (SharedPreferences). */
public class Widgets {
    static final String PREFS = "temvra_widgets";
    static final String KEY = "json";
    static final String ACTION_TICK = "com.temvra.app.WIDGET_TICK";

    static JSONObject data(Context c) {
        try {
            String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) { return new JSONObject(); }
    }

    static int count(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        return m.getAppWidgetIds(new ComponentName(c, TimerWidget.class)).length
             + m.getAppWidgetIds(new ComponentName(c, AlarmWidget.class)).length
             + m.getAppWidgetIds(new ComponentName(c, DayWidget.class)).length;
    }

    static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        JSONObject d = data(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, TimerWidget.class))) m.updateAppWidget(id, timer(c, d));
        for (int id : m.getAppWidgetIds(new ComponentName(c, AlarmWidget.class))) m.updateAppWidget(id, alarm(c, d));
        for (int id : m.getAppWidgetIds(new ComponentName(c, DayWidget.class))) m.updateAppWidget(id, day(c, d));
        scheduleTick(c);
    }

    /* Un «tic» cada minuto para que el widget del día avance sin abrir la app */
    static void scheduleTick(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = tickIntent(c);
        if (count(c) == 0) { am.cancel(pi); return; }
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 60_000L, 60_000L, pi);
    }

    static PendingIntent tickIntent(Context c) {
        Intent it = new Intent(c, WidgetTick.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(c, 40, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent open(Context c, String action, int code) {
        Intent it = new Intent(c, MainActivity.class);
        it.setAction("com.temvra.app." + action);
        it.putExtra(TemvraPlugin.EXTRA_ACTION, action);
        it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, code, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static String mmss(long ms) {
        long s = Math.max(0, (ms + 500) / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    /* ---------- Temporizador ---------- */
    static RemoteViews timer(Context c, JSONObject d) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_timer);
        JSONObject t = d.optJSONObject("timer");
        long now = System.currentTimeMillis();
        String name = t != null ? t.optString("name", "Temporizador") : "Temporizador";
        boolean running = t != null && t.optBoolean("running", false);
        long endAt = t != null ? t.optLong("endAt", 0) : 0;
        long period = t != null ? t.optLong("period", 90_000) : 90_000;
        long remain = t != null ? t.optLong("remain", period) : period;
        int lap = t != null ? t.optInt("lap", 1) : 1;
        boolean loop = t != null && t.optBoolean("loop", true);
        if (running && endAt > 0 && endAt <= now) {
            // La app no está abierta: avanzamos las vueltas que ya han pasado
            if (loop && period > 0) { while (endAt <= now) { endAt += period; lap++; } }
            else { running = false; remain = period; }
        }
        v.setTextViewText(R.id.w_name, name);
        v.setTextViewText(R.id.w_lap, "Vuelta " + lap);
        if (running) {
            v.setViewVisibility(R.id.w_chrono, View.VISIBLE);
            v.setViewVisibility(R.id.w_static, View.GONE);
            v.setChronometerCountDown(R.id.w_chrono, true);
            v.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime() + (endAt - now), null, true);
            v.setImageViewResource(R.id.w_play, R.drawable.ic_w_pause);
            v.setOnClickPendingIntent(R.id.w_play, open(c, "pause", 21));
        } else {
            v.setViewVisibility(R.id.w_chrono, View.GONE);
            v.setViewVisibility(R.id.w_static, View.VISIBLE);
            v.setTextViewText(R.id.w_static, mmss(remain));
            v.setImageViewResource(R.id.w_play, R.drawable.ic_w_play);
            v.setOnClickPendingIntent(R.id.w_play, open(c, "resume", 22));
        }
        int prog = period > 0 ? (int) Math.max(0, Math.min(100, 100 - (running ? (endAt - now) : remain) * 100 / period)) : 0;
        v.setProgressBar(R.id.w_ring, 100, prog, false);
        v.setOnClickPendingIntent(R.id.w_root, open(c, "open", 20));
        return v;
    }

    /* ---------- Próxima alarma ---------- */
    static RemoteViews alarm(Context c, JSONObject d) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_alarm);
        JSONObject a = d.optJSONObject("alarm");
        if (a != null && a.optLong("at", 0) > System.currentTimeMillis()) {
            v.setTextViewText(R.id.w_alarm_time, a.optString("time", "--:--"));
            v.setTextViewText(R.id.w_alarm_sub, a.optString("sub", ""));
        } else {
            v.setTextViewText(R.id.w_alarm_time, "--:--");
            v.setTextViewText(R.id.w_alarm_sub, "Sin alarmas activas");
        }
        v.setOnClickPendingIntent(R.id.w_alarm_root, open(c, "alarms", 30));
        return v;
    }

    /* ---------- Hoy ---------- */
    static RemoteViews day(Context c, JSONObject d) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_day);
        Calendar cal = Calendar.getInstance();
        int minOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        String[] days = {"domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"};
        String[] months = {"ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"};
        String dateTxt = "Hoy · " + days[cal.get(Calendar.DAY_OF_WEEK) - 1] + " " + cal.get(Calendar.DAY_OF_MONTH) + " " + months[cal.get(Calendar.MONTH)];
        JSONObject dd = d.optJSONObject("day");
        if (dd != null && dd.has("dateTxt")) dateTxt = dd.optString("dateTxt", dateTxt);
        v.setTextViewText(R.id.w_day_date, dateTxt);
        v.setProgressBar(R.id.w_day_bar, 1440, minOfDay, false);
        int left = 1440 - minOfDay;
        v.setTextViewText(R.id.w_day_left, "Quedan " + (left / 60) + " h " + (left % 60) + " min del día");
        String next = "Nada más por hoy", sub = "";
        if (dd != null) {
            JSONArray items = dd.optJSONArray("items");
            String todayKey = String.format(Locale.ROOT, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
            boolean sameDay = todayKey.equals(dd.optString("date", todayKey));
            if (items != null && sameDay) {
                String nowHm = String.format(Locale.ROOT, "%02d:%02d", minOfDay / 60, minOfDay % 60);
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    String tm = it.optString("time", "");
                    if (tm.compareTo(nowHm) > 0) { next = it.optString("title", ""); sub = "A las " + tm + (it.has("sub") ? " · " + it.optString("sub") : ""); break; }
                }
            }
        }
        v.setTextViewText(R.id.w_day_next, next);
        v.setTextViewText(R.id.w_day_next2, sub);
        v.setViewVisibility(R.id.w_day_next2, sub.isEmpty() ? View.GONE : View.VISIBLE);
        v.setOnClickPendingIntent(R.id.w_day_root, open(c, "agenda", 31));
        return v;
    }
}
