package com.temvra.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

/** Selector de bóveda o de registro para un widget. */
public class WidgetPick extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int id = getIntent().getIntExtra("id", 0);
        String kind = getIntent().getStringExtra("kind");
        JSONObject d = Widgets.data(this);
        JSONArray list = d.optJSONArray("vaults".equals(kind) ? "vaults" : "regs");
        if (list == null || list.length() == 0) { finish(); return; }
        String[] names = new String[list.length()];
        for (int i = 0; i < names.length; i++) { JSONObject o = list.optJSONObject(i); names[i] = o != null ? o.optString("name") : ""; }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("vaults".equals(kind) ? "Bóveda" : "Registro")
                .setItems(names, (dlg, which) -> {
                    JSONObject o = list.optJSONObject(which);
                    if (o != null) Widgets.pick(this, id, kind, o.optString("id"));
                    finish();
                })
                .setOnCancelListener(x -> finish())
                .show();
    }
}
