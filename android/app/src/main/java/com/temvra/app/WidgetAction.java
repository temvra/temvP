package com.temvra.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Recibe los toques en las listas de los widgets: plegar una carpeta se resuelve aquí; lo demás abre la app. */
public class WidgetAction extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String a = getIntent().getStringExtra(TemvraPlugin.EXTRA_ACTION);
        if (a != null && a.startsWith("fold:")) {
            int bar = a.indexOf('|');
            Widgets.toggleFolder(this, Integer.parseInt(a.substring(5, bar)), a.substring(bar + 1));
        } else if (a != null) {
            Intent it = new Intent(this, MainActivity.class).putExtra(TemvraPlugin.EXTRA_ACTION, a);
            it.setAction("com.temvra.app.open." + a);
            it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        }
        finish();
    }
}
