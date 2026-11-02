package com.temvra.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Recibe el tic de cada minuto y repinta los widgets. */
public class WidgetTick extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Widgets.refreshAll(context);
    }
}
