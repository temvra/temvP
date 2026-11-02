package com.temvra.app;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.SystemClock;

/**
 * El temporizador como tarjeta multimedia, igual que un reproductor de música: es lo único que ColorOS
 * muestra siempre en la pantalla de bloqueo. Los botones son solo iconos y funcionan sin desbloquear.
 */
final class TimerMedia {
    private TimerMedia() {}

    private static MediaSession session;
    private static Bitmap art;
    static Notification last;

    private static MediaSession session(Context c) {
        if (session != null) return session;
        Context app = c.getApplicationContext();
        session = new MediaSession(app, "TemvraTimer");
        session.setPlaybackToLocal(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { TimerCtl.act(app, "resume"); }
            @Override public void onPause() { TimerCtl.act(app, "pause"); }
            @Override public void onSkipToNext() { TimerCtl.act(app, "skip"); }
            @Override public void onCustomAction(String action, android.os.Bundle extras) { TimerCtl.act(app, action); }
        });
        return session;
    }

    private static Bitmap art(Context c) {
        if (art != null) return art;
        Drawable d = c.getDrawable(R.mipmap.ic_launcher);
        if (d == null) return null;
        art = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, 256, 256);
        d.draw(new Canvas(art));
        return art;
    }

    static void update(Context c, String title, String sub, boolean running, long endAt, long period, long remain) {
        MediaSession s = session(c);
        long left = running ? Math.max(0, endAt - System.currentTimeMillis()) : remain;
        long pos = Math.max(0, Math.min(period, period - left));
        s.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, sub)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, period)
                .putBitmap(MediaMetadata.METADATA_KEY_ART, art(c))
                .build());
        s.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT)
                .addCustomAction("reset", "Reiniciar", R.drawable.ic_n_reset)
                .setState(running ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, running ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());
        s.setActive(true);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, TemvraPlugin.CH_LIVE) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat_temvra)
                .setContentTitle(title)
                .setContentText(sub)
                .setLargeIcon(art(c))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setContentIntent(Alerts.openIntent(c, "open", 10, null))
                .setStyle(new Notification.MediaStyle().setMediaSession(s.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        b.addAction(action(c, running ? R.drawable.ic_n_pause : R.drawable.ic_n_play, running ? "Pausar" : "Reanudar", running ? "pause" : "resume", running ? 11 : 12));
        b.addAction(action(c, R.drawable.ic_n_reset, "Reiniciar", "reset", 13));
        b.addAction(action(c, R.drawable.ic_n_skip, "Saltar vuelta", "skip", 14));
        last = b.build();

        android.app.NotificationManager nm = Alerts.nm(c);
        try { if (nm != null) nm.notify(TemvraPlugin.NOTIF_ID, last); } catch (SecurityException ignored) {}
        // Un servicio en primer plano mantiene viva la tarjeta y sus botones con la app cerrada
        if (!TimerService.running) {
            try {
                Intent it = new Intent(c, TimerService.class);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(it); else c.startService(it);
            } catch (Exception ignored) {}
        }
    }

    private static Notification.Action action(Context c, int icon, String title, String act, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(c, icon), title, Alerts.timerAction(c, act, code)).build();
    }

    static void clear(Context c) {
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        c.stopService(new Intent(c, TimerService.class));
        android.app.NotificationManager nm = Alerts.nm(c);
        if (nm != null) nm.cancel(TemvraPlugin.NOTIF_ID);
    }
}
