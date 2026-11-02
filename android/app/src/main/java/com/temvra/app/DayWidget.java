package com.temvra.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** Calendario: línea de tiempo de hoy con la hora actual. */
public class DayWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) Widgets.updateList(context, manager, id, Widgets.DAY);
        Widgets.scheduleTick(context);
    }
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        Widgets.updateList(context, manager, id, Widgets.DAY);
    }
    @Override public void onEnabled(Context context) { Widgets.scheduleTick(context); }
    @Override public void onDisabled(Context context) { Widgets.scheduleTick(context); }
}
