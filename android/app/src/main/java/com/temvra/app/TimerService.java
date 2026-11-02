package com.temvra.app;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Mantiene el proceso vivo mientras el temporizador está en la pantalla de bloqueo. */
public class TimerService extends Service {
    static boolean running;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (TimerMedia.last == null) { stopSelf(); return START_NOT_STICKY; }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(TemvraPlugin.NOTIF_ID, TimerMedia.last, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(TemvraPlugin.NOTIF_ID, TimerMedia.last);
            running = true;
        } catch (Exception e) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { running = false; super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
