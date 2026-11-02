package com.temvra.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** Línea de tiempo del día: se desplaza dentro del widget y tiene un botón para volver a la hora actual. */
public class LineWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) Widgets.updateLine(context, manager, id);
        Widgets.scheduleTick(context);
    }
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        Widgets.updateLine(context, manager, id);
    }
    @Override public void onEnabled(Context context) { Widgets.scheduleTick(context); }
    @Override public void onDisabled(Context context) { Widgets.scheduleTick(context); }
}
