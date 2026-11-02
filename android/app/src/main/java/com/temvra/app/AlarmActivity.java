package com.temvra.app;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONObject;

import java.lang.ref.WeakReference;

/** Pantalla de la alarma: aparece encima de la pantalla de bloqueo. */
public class AlarmActivity extends Activity {
    private static WeakReference<AlarmActivity> current = new WeakReference<>(null);

    static void closeIfOpen() {
        AlarmActivity a = current.get();
        if (a != null) a.finish();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = new WeakReference<>(this);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_alarm);

        JSONObject a;
        try { a = new JSONObject(getIntent().getStringExtra("alert")); }
        catch (Exception e) { a = new JSONObject(); }
        final JSONObject alert = a;
        ((TextView) findViewById(R.id.alarm_title)).setText(a.optString("title", "Alarma").replace("⏰ ", ""));
        String body = a.optString("body", "");
        int sep = body.indexOf(" · ");
        ((TextView) findViewById(R.id.alarm_time)).setText(sep > 0 ? body.substring(0, sep) : body);
        ((TextView) findViewById(R.id.alarm_snooze)).setText("Posponer " + Alerts.cfg(this).optInt("snooze", 5) + " min");
        findViewById(R.id.alarm_stop).setOnClickListener(v -> close());
        findViewById(R.id.alarm_snooze).setOnClickListener(v -> {
            AlertReceiver.snooze(this, alert);
            close();
        });
    }

    private void close() {
        AlarmService.stop(this);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (current.get() == this) current = new WeakReference<>(null);
        super.onDestroy();
    }
}
