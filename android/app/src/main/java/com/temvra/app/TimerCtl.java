package com.temvra.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Estado del temporizador mientras la app no está delante. Permite pausar, reanudar, reiniciar y saltar
 * desde la pantalla de bloqueo sin abrir la app; al volver, la app recoge este estado.
 */
final class TimerCtl {
    private TimerCtl() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(Alerts.PREFS, Context.MODE_PRIVATE); }

    static JSONObject state(Context c) {
        try { return new JSONObject(sp(c).getString("timer", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static void save(Context c, JSONObject t) { sp(c).edit().putString("timer", t.toString()).apply(); }

    private static void put(JSONObject o, String k, Object v) { try { o.put(k, v); } catch (Exception ignored) {} }

    /** Avanza las vueltas que ya han pasado. */
    static void normalize(JSONObject t) {
        if (!t.optBoolean("running")) return;
        long now = System.currentTimeMillis(), endAt = t.optLong("endAt"), period = t.optLong("period", 90_000);
        if (endAt > now) return;
        if (t.optBoolean("loop") && period > 0) {
            int lap = t.optInt("lap", 1);
            while (endAt <= now) { endAt += period; lap++; }
            put(t, "endAt", endAt);
            put(t, "lap", lap);
        } else {
            put(t, "running", false);
            put(t, "remain", period);
            put(t, "lap", 1);
        }
    }

    /** Botón de la notificación o del widget. */
    static void act(Context c, String action) {
        if (TemvraPlugin.foreground != null) { TemvraPlugin.foreground.sendAction(action); return; }
        JSONObject t = state(c);
        if (!t.has("period")) return;
        normalize(t);
        long now = System.currentTimeMillis(), period = t.optLong("period", 90_000);
        boolean running = t.optBoolean("running");
        if ("pause".equals(action) && running) {
            put(t, "remain", Math.max(0, t.optLong("endAt") - now));
            put(t, "running", false);
        } else if ("resume".equals(action) && !running) {
            put(t, "endAt", now + Math.max(1000, t.optLong("remain", period)));
            put(t, "running", true);
        } else if ("reset".equals(action)) {
            put(t, "running", false);
            put(t, "remain", period);
            put(t, "lap", 1);
        } else if ("skip".equals(action)) {
            put(t, "lap", t.optInt("lap", 1) + 1);
            put(t, "endAt", now + period);
            put(t, "running", true);
        }
        put(t, "active", true);
        save(c, t);
        sync(c);
    }

    /** Deja el aviso programado, la notificación y el widget de acuerdo con el estado. */
    static void sync(Context c) {
        JSONObject t = state(c);
        normalize(t);
        save(c, t);
        JSONArray cur = Alerts.list(c), out = new JSONArray();
        for (int i = 0; i < cur.length(); i++) {
            JSONObject o = cur.optJSONObject(i);
            if (o != null && !"timer".equals(o.optString("channel"))) out.put(o);
        }
        if (t.optBoolean("running")) {
            JSONObject a = new JSONObject();
            String name = t.optString("name", "Temporizador");
            put(a, "key", "tm");
            put(a, "at", t.optLong("endAt"));
            put(a, "title", "⏱ " + name);
            put(a, "body", t.optBoolean("loop") ? "Vuelta " + t.optInt("lap", 1) + " terminada" : "Tiempo terminado");
            put(a, "channel", "timer");
            put(a, "loop", t.optBoolean("loop"));
            JSONObject ex = new JSONObject();
            put(ex, "type", "timer");
            put(a, "extra", ex);
            out.put(a);
        }
        Alerts.store(c, out);
        Alerts.armNext(c);
        render(c, t);
        pushWidget(c, t);
    }

    static void render(Context c, JSONObject t) {
        boolean running = t.optBoolean("running"), show = t.optBoolean("show", true);
        long period = t.optLong("period", 90_000), remain = t.optLong("remain", period);
        if (!show || (!running && remain >= period)) { TimerMedia.clear(c); return; }
        TimerMedia.update(c, t.optString("name", "Temporizador"),
                "Vuelta " + t.optInt("lap", 1) + (running ? "" : " · En pausa " + Widgets.mmss(remain)), running, t.optLong("endAt"), period, remain);
    }

    private static void pushWidget(Context c, JSONObject t) {
        try {
            JSONObject d = Widgets.data(c);
            JSONObject w = new JSONObject();
            w.put("name", t.optString("name", "Temporizador"));
            w.put("running", t.optBoolean("running"));
            w.put("endAt", t.optLong("endAt"));
            w.put("remain", t.optLong("remain"));
            w.put("period", t.optLong("period"));
            w.put("lap", t.optInt("lap", 1));
            w.put("loop", t.optBoolean("loop"));
            d.put("timer", w);
            c.getSharedPreferences(Widgets.PREFS, Context.MODE_PRIVATE).edit().putString(Widgets.KEY, d.toString()).apply();
            Widgets.refreshAll(c);
        } catch (Exception ignored) {}
    }
}
