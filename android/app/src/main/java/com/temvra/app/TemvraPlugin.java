package com.temvra.app;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Plugin propio de Temvra:
 *  - audioFocus: baja (duck) o pausa la música de otras apps mientras suena un aviso.
 *  - timerNotify / timerClear: notificación fija con la cuenta atrás del temporizador.
 *  - widgets: guarda los datos que pintan los widgets de la pantalla de inicio.
 */
@CapacitorPlugin(name = "Temvra")
public class TemvraPlugin extends Plugin {

    static final String CH_LIVE = "timer_live_v2";
    /** La app está delante: los botones del temporizador se los pasa a ella. */
    static TemvraPlugin foreground;
    static final int NOTIF_ID = 7001;
    static final String EXTRA_ACTION = "vt_action";

    private AudioFocusRequest focusReq;
    private AudioManager.OnAudioFocusChangeListener focusListener = f -> {};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable abandon = this::abandonFocus;

    private int[] bars = {0, 0, 0, 0};

    /**
     * La app ocupa toda la pantalla, también detrás de las barras: el fondo llega al borde y solo el contenido se aparta.
     * Se hace aquí porque la WebView anterior a la 140 no da los márgenes seguros y Capacitor rellenaría esas franjas.
     */
    private void edgeToEdge() {
        android.view.View decor = getActivity().getWindow().getDecorView();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(decor, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
            boolean kb = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(0, 0, 0, kb ? ime.bottom : 0);
            bars = new int[]{sys.top, kb ? 0 : sys.bottom, sys.left, sys.right};
            sendBars();
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(decor);
    }

    private void sendBars() {
        float d = getContext().getResources().getDisplayMetrics().density;
        String js = String.format(java.util.Locale.ROOT, "(function(s){s.setProperty('--sat','%.1fpx');s.setProperty('--sab','%.1fpx');s.setProperty('--sal','%.1fpx');s.setProperty('--sar','%.1fpx')})(document.documentElement.style)",
                bars[0] / d, bars[1] / d, bars[2] / d, bars[3] / d);
        if (getBridge() != null && getBridge().getWebView() != null) getBridge().getWebView().post(() -> getBridge().getWebView().evaluateJavascript(js, null));
    }

    @PluginMethod
    public void applyBars(PluginCall call) {
        sendBars();
        call.resolve();
    }

    @Override
    public void load() {
        getActivity().runOnUiThread(this::edgeToEdge);
        Intent it = getActivity() != null ? getActivity().getIntent() : null;
        if (it != null) deliverAction(it);
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        deliverAction(intent);
    }

    @Override protected void handleOnResume() { super.handleOnResume(); foreground = this; }
    @Override protected void handleOnPause() { super.handleOnPause(); if (foreground == this) foreground = null; }

    void sendAction(String action) {
        JSObject d = new JSObject();
        d.put("action", action);
        notifyListeners("action", d, true);
    }

    private void deliverAction(Intent intent) {
        if (deliverFile(intent)) return;
        String a = intent.getStringExtra(EXTRA_ACTION);
        if (a == null) return;
        intent.removeExtra(EXTRA_ACTION);
        JSObject d = new JSObject();
        d.put("action", a);
        notifyListeners("action", d, true);
    }

    /** Un archivo abierto desde WhatsApp u otra app (.ics o una bóveda en ZIP): se lo pasa a la app para importarlo. */
    private boolean deliverFile(Intent intent) {
        String act = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(act)) uri = intent.getData();
        else if (Intent.ACTION_SEND.equals(act)) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return false;
        intent.setAction(Intent.ACTION_MAIN);
        intent.setData(null);
        String name = "archivo";
        try (Cursor cur = getContext().getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = cur.getString(i);
            }
        } catch (Exception ignored) {}
        try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while (in != null && (n = in.read(b)) > 0) buf.write(b, 0, n);
            JSObject d = new JSObject();
            d.put("name", name);
            d.put("mime", getContext().getContentResolver().getType(uri));
            d.put("data", Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP));
            notifyListeners("file", d, true);
        } catch (Exception ignored) {}
        return true;
    }

    /* ---------------- Música de otras apps ---------------- */

    @PluginMethod
    public void audioFocus(PluginCall call) {
        String mode = call.getString("mode", "duck");
        int ms = call.getInt("ms", 3000);
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (am == null) { call.resolve(); return; }
        abandonFocus();
        int gain = "pause".equals(mode) ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
        int res;
        if (Build.VERSION.SDK_INT >= 26) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            focusReq = new AudioFocusRequest.Builder(gain).setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false).setOnAudioFocusChangeListener(focusListener).build();
            res = am.requestAudioFocus(focusReq);
        } else {
            res = am.requestAudioFocus(focusListener, AudioManager.STREAM_ALARM, gain);
        }
        handler.removeCallbacks(abandon);
        handler.postDelayed(abandon, Math.max(500, ms));
        JSObject out = new JSObject();
        out.put("granted", res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        call.resolve(out);
    }

    private void abandonFocus() {
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26) { if (focusReq != null) { am.abandonAudioFocusRequest(focusReq); focusReq = null; } }
            else am.abandonAudioFocus(focusListener);
        } catch (Exception ignored) {}
    }

    /* ---------------- Notificación del temporizador ---------------- */

    @PluginMethod
    public void timerNotify(PluginCall call) {
        try {
            boolean running = Boolean.TRUE.equals(call.getBoolean("running", false));
            Long endAt = call.getLong("endAt");
            Long period = call.getLong("period"), remain = call.getLong("remain");
            Alerts.ensureChannels(getContext());
            TimerMedia.update(getContext(), call.getString("title", "Temporizador"), call.getString("body", ""), running, endAt != null ? endAt : 0L,
                    period != null ? period : 90_000L, remain != null ? remain : 0L);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void timerClear(PluginCall call) {
        TimerMedia.clear(getContext());
        call.resolve();
    }

    /* ---------------- Sonido y avisos programados ---------------- */

    @PluginMethod
    public void playAlert(PluginCall call) {
        JSObject tts = call.getObject("tts");
        AlertSound.get(getContext()).play(call.getString("kind", "pitido"), call.getInt("vol", 150),
                tts != null ? tts.optString("text") : null, tts != null ? tts.optString("lang", "es-ES") : "es-ES",
                tts != null ? (float) tts.optDouble("pitch", 1) : 1f, tts != null ? (float) tts.optDouble("rate", 1) : 1f,
                Boolean.TRUE.equals(call.getBoolean("loop", false)));
        call.resolve();
    }

    @PluginMethod
    public void stopAlert(PluginCall call) {
        AlertSound.get(getContext()).stop();
        AlarmService.stop(getContext());
        call.resolve();
    }

    /** Guarda en un archivo el audio importado para poder sonar sin la app abierta. */
    @PluginMethod
    public void saveOwnAudio(PluginCall call) {
        String data = call.getString("data", "");
        try (FileOutputStream out = new FileOutputStream(AlertSound.ownFile(getContext()))) {
            int comma = data.indexOf(',');
            out.write(Base64.decode(comma >= 0 ? data.substring(comma + 1) : data, Base64.DEFAULT));
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void scheduleAlerts(PluginCall call) {
        try {
            Alerts.ensureChannels(getContext());
            JSONArray list = call.getArray("list");
            JSONObject cfg = call.getObject("cfg");
            Alerts.setAll(getContext(), list != null ? list : new JSONArray(), cfg);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void clearAlerts(PluginCall call) {
        Alerts.clear(getContext());
        call.resolve();
    }

    /** Estado del temporizador tras estar en segundo plano; deja de ser válido al recogerlo. */
    @PluginMethod
    public void timerState(PluginCall call) {
        JSONObject t = TimerCtl.state(getContext());
        TimerCtl.normalize(t);
        JSObject out = new JSObject();
        if (t.optBoolean("active")) {
            out.put("active", true);
            out.put("running", t.optBoolean("running"));
            out.put("endAt", t.optLong("endAt"));
            out.put("remain", t.optLong("remain"));
            out.put("lap", t.optInt("lap", 1));
            out.put("held", t.optBoolean("held"));
            try { t.put("active", false); } catch (Exception ignored) {}
            TimerCtl.save(getContext(), t);
        }
        call.resolve(out);
    }

    @PluginMethod
    public void consumeFired(PluginCall call) {
        JSObject out = new JSObject();
        out.put("fired", Alerts.consumeFired(getContext()));
        call.resolve(out);
    }

    /* ---------------- Permisos para la pantalla bloqueada ---------------- */

    @PluginMethod
    public void permStatus(PluginCall call) {
        Context c = getContext();
        NotificationManager nm = Alerts.nm(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        JSObject out = new JSObject();
        out.put("notifications", nm == null || nm.areNotificationsEnabled());
        out.put("fullScreen", Build.VERSION.SDK_INT < 34 || nm == null || nm.canUseFullScreenIntent());
        out.put("exact", Build.VERSION.SDK_INT < 31 || am == null || am.canScheduleExactAlarms());
        out.put("battery", pm == null || pm.isIgnoringBatteryOptimizations(c.getPackageName()));
        call.resolve(out);
    }

    @PluginMethod
    public void openPerm(PluginCall call) {
        Context c = getContext();
        String which = call.getString("which", "notifications");
        Uri pkg = Uri.parse("package:" + c.getPackageName());
        Intent it;
        if ("fullScreen".equals(which) && Build.VERSION.SDK_INT >= 34) it = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg);
        else if ("battery".equals(which)) it = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg);
        else if ("channels".equals(which) || "notifications".equals(which)) it = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        else it = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
        try {
            getActivity().startActivity(it);
        } catch (Exception e) {
            getActivity().startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg));
        }
        call.resolve();
    }

    /** Color de la zona de las barras del sistema: el del tema, para que los iconos no se fundan con el fondo. */
    @PluginMethod
    public void setBars(PluginCall call) {
        try {
            int color = android.graphics.Color.parseColor(call.getString("color", "#000000"));
            boolean dark = Boolean.TRUE.equals(call.getBoolean("dark", true));
            getActivity().runOnUiThread(() -> {
                android.view.Window w = getActivity().getWindow();
                android.view.View decor = w.getDecorView();
                decor.setBackgroundColor(color);
                // La app se pinta debajo de las barras: fondo o foto llegan hasta el borde de la pantalla
                w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
                w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
                if (android.os.Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
                androidx.core.view.WindowInsetsControllerCompat c = androidx.core.view.WindowCompat.getInsetsController(w, decor);
                c.setAppearanceLightStatusBars(!dark);
                c.setAppearanceLightNavigationBars(!dark);
            });
        } catch (Exception ignored) {}
        call.resolve();
    }

    /* ---------------- Widgets ---------------- */

    @PluginMethod
    public void widgets(PluginCall call) {
        JSObject data = call.getObject("data");
        if (data != null) {
            SharedPreferences sp = getContext().getSharedPreferences(Widgets.PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(Widgets.KEY, data.toString()).apply();
            Widgets.refreshAll(getContext());
        }
        JSObject out = new JSObject();
        out.put("installed", Widgets.count(getContext()));
        call.resolve(out);
    }
}
