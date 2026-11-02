package com.temvra.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

public class NotesWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) Widgets.updateNotes(context, manager, id);
        Widgets.scheduleTick(context);
    }
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        Widgets.updateNotes(context, manager, id);
    }
    @Override public void onEnabled(Context context) { Widgets.scheduleTick(context); }
    @Override public void onDisabled(Context context) { Widgets.scheduleTick(context); }
}
