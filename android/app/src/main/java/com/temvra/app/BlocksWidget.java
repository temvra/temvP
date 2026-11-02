package com.temvra.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

public class BlocksWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) Widgets.updateList(context, manager, id, Widgets.BLOCKS);
        Widgets.scheduleTick(context);
    }
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        Widgets.updateList(context, manager, id, Widgets.BLOCKS);
    }
    @Override public void onEnabled(Context context) { Widgets.scheduleTick(context); }
    @Override public void onDisabled(Context context) { Widgets.scheduleTick(context); }
}
