package com.temvra.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Plugin propio de Temvra:
 *  - audioFocus: baja (duck) o pausa la música de otras apps mientras suena un aviso.
 *  - timerNotify / timerClear: notificación fija con la cuenta atrás del temporizador.
 *  - widgets: guarda los datos que pintan los widgets de la pantalla de inicio.
 */
@CapacitorPlugin(name = "Temvra")
public class TemvraPlugin extends Plugin {

    static final String CH_LIVE = "timer_live";
    static final int NOTIF_ID = 7001;
    static final String EXTRA_ACTION = "vt_action";

    private AudioFocusRequest focusReq;
    private AudioManager.OnAudioFocusChangeListener focusListener = f -> {};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable abandon = this::abandonFocus;

    @Override
    public void load() {
        Intent it = getActivity() != null ? getActivity().getIntent() : null;
        if (it != null) deliverAction(it);
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        deliverAction(intent);
    }

    private void deliverAction(Intent intent) {
        String a = intent.getStringExtra(EXTRA_ACTION);
        if (a == null) return;
        intent.removeExtra(EXTRA_ACTION);
        JSObject d = new JSObject();
        d.put("action", a);
        notifyListeners("action", d, true);
    }

    /* ---------------- Música de otras apps ---------------- */

    @PluginMethod
    public void audioFocus(PluginCall call) {
        String mode = call.getString("mode", "duck");
        int ms = call.getInt("ms", 3000);
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (am == null) { call.resolve(); return; }
        abandonFocus();
        int gain = "pause".equals(mode) ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
        int res;
        if (Build.VERSION.SDK_INT >= 26) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            focusReq = new AudioFocusRequest.Builder(gain).setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false).setOnAudioFocusChangeListener(focusListener).build();
            res = am.requestAudioFocus(focusReq);
        } else {
            res = am.requestAudioFocus(focusListener, AudioManager.STREAM_ALARM, gain);
        }
        handler.removeCallbacks(abandon);
        handler.postDelayed(abandon, Math.max(500, ms));
        JSObject out = new JSObject();
        out.put("granted", res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        call.resolve(out);
    }

    private void abandonFocus() {
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26) { if (focusReq != null) { am.abandonAudioFocusRequest(focusReq); focusReq = null; } }
            else am.abandonAudioFocus(focusListener);
        } catch (Exception ignored) {}
    }

    /* ---------------- Notificación del temporizador ---------------- */

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CH_LIVE) != null) return;
        NotificationChannel ch = new NotificationChannel(CH_LIVE, "Temporizador en marcha", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Cuenta atrás del temporizador en la barra y en la pantalla de bloqueo");
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private PendingIntent actionIntent(String action, int code) {
        Context c = getContext();
        Intent it = new Intent(c, MainActivity.class);
        it.setAction("com.temvra.app." + action);
        it.putExtra(EXTRA_ACTION, action);
        it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, code, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @PluginMethod
    public void timerNotify(PluginCall call) {
        try {
            ensureChannel();
            String title = call.getString("title", "Temporizador");
            String body = call.getString("body", "");
            boolean running = Boolean.TRUE.equals(call.getBoolean("running", false));
            long endAt = call.getLong("endAt") != null ? call.getLong("endAt") : 0L;
            boolean pub = Boolean.TRUE.equals(call.getBoolean("lockscreen", true));
            NotificationCompat.Builder b = new NotificationCompat.Builder(getContext(), CH_LIVE)
                    .setSmallIcon(R.drawable.ic_stat_temvra)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                    .setVisibility(pub ? NotificationCompat.VISIBILITY_PUBLIC : NotificationCompat.VISIBILITY_PRIVATE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(actionIntent("open", 10));
            if (running && endAt > 0) {
                b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(endAt).setShowWhen(true);
                b.addAction(0, "Pausar", actionIntent("pause", 11));
            } else {
                b.setShowWhen(false);
                b.addAction(0, "Reanudar", actionIntent("resume", 12));
            }
            b.addAction(0, "Reiniciar", actionIntent("reset", 13));
            b.addAction(0, "Saltar vuelta", actionIntent("skip", 14));
            NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, b.build());
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void timerClear(PluginCall call) {
        NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
        call.resolve();
    }

    /* ---------------- Widgets ---------------- */

    @PluginMethod
    public void widgets(PluginCall call) {
        JSObject data = call.getObject("data");
        if (data != null) {
            SharedPreferences sp = getContext().getSharedPreferences(Widgets.PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(Widgets.KEY, data.toString()).apply();
            Widgets.refreshAll(getContext());
        }
        JSObject out = new JSObject();
        out.put("installed", Widgets.count(getContext()));
        call.resolve(out);
    }
}
