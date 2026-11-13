package com.temvra.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/** Recibe el aviso programado, los botones de las notificaciones y el reinicio del móvil. */
public class AlertReceiver extends BroadcastReceiver {
    static final String ACTION_TIMER = "com.temvra.app.TIMER_ACT";
    static final String ACTION_LINE = "com.temvra.app.WIDGET_LINE";

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Alerts.ACTION_FIRE.equals(a)) {
            Alerts.fireDue(c);
        } else if (Alerts.ACTION_REFRESH.equals(a)) {
            Alerts.refreshDue(c);
        } else if (ACTION_TIMER.equals(a)) {
            TimerCtl.act(c, intent.getStringExtra("act"));
        } else if (ACTION_LINE.equals(a)) {
            Widgets.lineOp(c, intent.getIntExtra("id", 0), intent.getStringExtra("op"));
        } else if (Alerts.ACTION_SNOOZE.equals(a) || Alerts.ACTION_DISMISS.equals(a)) {
            JSONObject o = parse(intent.getStringExtra("alert"));
            if (o == null) return;
            if ("alarms".equals(o.optString("channel"))) AlarmService.stop(c);
            if (Alerts.nm(c) != null) Alerts.nm(c).cancel(Alerts.idOf(o));
            Alerts.forget(c, o);
            if (Alerts.ACTION_SNOOZE.equals(a)) snooze(c, o);
        } else {
            // Reinicio, actualización de la app o cambio de hora: los textos de los avisos a la vista se rehacen con la hora nueva
            TimerCtl.sync(c);
            Widgets.refreshAll(c);
            Alerts.refreshShown(c);
        }
    }

    static void snooze(Context c, JSONObject o) {
        try {
            int min = Alerts.cfg(c).optInt("snooze", 5);
            JSONObject n = new JSONObject(o.toString());
            n.put("at", System.currentTimeMillis() + min * 60_000L);
            n.put("key", "snz|" + o.optString("key") + "|" + System.currentTimeMillis());
            n.put("keep", true);
            Alerts.add(c, n);
        } catch (Exception ignored) {}
    }

    private static JSONObject parse(String s) {
        try { return s == null ? null : new JSONObject(s); } catch (Exception e) { return null; }
    }
}
