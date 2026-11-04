package com.temvra.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

/** Mantiene la app viva mientras suena un aviso con la pantalla bloqueada. */
public class AlarmService extends Service {
    private static final int NOTIF_RING = 7003;
    private static final long MAX_RING_MS = 5 * 60_000L;

    /** La alarma que está sonando, para ofrecer Detener al abrir la app. Null si no suena ninguna. */
    private static volatile String ringing = null;

    static String ringingAlert() { return ringing; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Vibrator vibrator;
    private AudioFocusRequest focus;

    static void start(Context c, JSONObject alert, boolean ring) {
        Intent it = new Intent(c, AlarmService.class).putExtra("alert", alert.toString()).putExtra("ring", ring);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(it); else c.startService(it);
        } catch (Exception e) {
            // El sistema no dejó arrancar el servicio: al menos que se vea el aviso
            Alerts.notifyReminder(c, alert);
        }
    }

    static void stop(Context c) {
        // Ya, sin esperar a onDestroy: al cerrar la pantalla de alarma la app vuelve delante y no debe abrirla otra vez
        ringing = null;
        c.stopService(new Intent(c, AlarmService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        JSONObject alert;
        try { alert = new JSONObject(intent.getStringExtra("alert")); }
        catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        boolean ring = intent.getBooleanExtra("ring", true);
        Alerts.ensureChannels(this);
        Notification n = ring ? ringNotification(alert) : quietNotification(alert);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF_RING, n);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ringing = ring ? alert.toString() : null;
        sound(alert, ring);
        return START_NOT_STICKY;
    }

    private Notification ringNotification(JSONObject a) {
        Intent full = new Intent(this, AlarmActivity.class).putExtra("alert", a.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent pi = PendingIntent.getActivity(this, 70, full, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, Alerts.CH_ALARM)
                .setSmallIcon(R.drawable.ic_stat_temvra)
                .setContentTitle(a.optString("title"))
                .setContentText(a.optString("body"))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .addAction(0, "Detener", Alerts.receiverIntent(this, Alerts.ACTION_DISMISS, a))
                .addAction(0, "Posponer", Alerts.receiverIntent(this, Alerts.ACTION_SNOOZE, a))
                .build();
    }

    private Notification quietNotification(JSONObject a) {
        return new NotificationCompat.Builder(this, TemvraPlugin.CH_LIVE)
                .setSmallIcon(R.drawable.ic_stat_temvra)
                .setContentTitle(a.optString("title"))
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void sound(JSONObject alert, boolean ring) {
        JSONObject cfg = Alerts.cfg(this);
        String aviso = cfg.optString("aviso", "duck");
        // Solo vibración: nada de sonido. El aviso del temporizador respeta además «Nada» y «Solo bajar música»
        boolean silent = !cfg.optBoolean("soundOn", true) || (!ring && ("none".equals(aviso) || "duckOnly".equals(aviso)));
        if (cfg.optBoolean("vib", true)) vibrate(ring);
        handler.removeCallbacksAndMessages(null);
        if (silent) { handler.postDelayed(this::stopSelf, ring ? MAX_RING_MS : 1500); return; }
        JSONObject tts = cfg.optJSONObject("tts");
        focus("pause".equals(aviso));
        AlertSound.get(this).play(cfg.optString("sound", "pitido"), cfg.optInt("vol", 150),
                tts != null ? tts.optString("text") : null, tts != null ? tts.optString("lang", "es-ES") : "es-ES",
                tts != null ? (float) tts.optDouble("pitch", 1) : 1f, tts != null ? (float) tts.optDouble("rate", 1) : 1f, ring);
        handler.postDelayed(this::stopSelf, ring ? MAX_RING_MS : 6000);
    }

    private void vibrate(boolean ring) {
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null) return;
        long[] p = ring ? new long[]{0, 500, 300, 500, 900} : new long[]{0, 300, 120, 300, 120, 500};
        int rep = ring ? 0 : -1;
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(p, rep),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        } else vibrator.vibrate(p, rep);
    }

    private void focus(boolean pause) {
        if (Build.VERSION.SDK_INT < 26) return;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int gain = pause ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
        focus = new AudioFocusRequest.Builder(gain).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        am.requestAudioFocus(focus);
    }

    @Override
    public void onDestroy() {
        ringing = null;
        handler.removeCallbacksAndMessages(null);
        AlertSound.get(this).stop();
        if (vibrator != null) vibrator.cancel();
        if (focus != null && Build.VERSION.SDK_INT >= 26) {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.abandonAudioFocusRequest(focus);
        }
        AlarmActivity.closeIfOpen();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
