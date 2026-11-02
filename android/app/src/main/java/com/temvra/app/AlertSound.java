package com.temvra.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import java.io.File;
import java.util.Locale;

/**
 * Sonido de avisos por el canal de ALARMA: no depende del volumen multimedia y suena con la pantalla bloqueada.
 * El volumen elegido en la app se aplica al canal de alarma mientras suena y después se restaura.
 */
public final class AlertSound {
    private static final int RATE = 44100;
    private static AlertSound inst;

    static synchronized AlertSound get(Context c) {
        if (inst == null) inst = new AlertSound(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final AudioManager am;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioAttributes alarmAttrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    private final AudioAttributes mediaAttrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    private AudioAttributes attrs = alarmAttrs;
    private float gain = 1f;
    private int boostMb = 0;
    private LoudnessEnhancer enhancer;
    private AudioTrack track;
    private MediaPlayer player;
    private TextToSpeech tts;
    private boolean ttsReady;
    private int savedVol = -1;
    private boolean looping;

    private final Runnable restore = this::restoreVolume;

    private AlertSound(Context c) {
        ctx = c;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Reproduce un aviso. kind: pitido, campana, gong, voz o propio. vol: 0-100. Con loop repite hasta stop(). */
    synchronized void play(String kind, int vol, String text, String lang, float pitch, float rate, boolean loop) {
        looping = loop;
        // Android saca el canal de alarma también por el altavoz: con auriculares o Bluetooth se usa el multimedia,
        // que solo suena por ellos, y el volumen elegido se aplica sobre el que ya tengan
        if (headphones()) {
            restoreVolume();
            attrs = mediaAttrs;
            gain = Math.max(.05f, Math.min(1f, vol / 100f));
            boostMb = 0;
        } else {
            attrs = alarmAttrs;
            gain = 1f;
            applyVolume(Math.min(100, vol));
            // Por encima del 100 % el canal ya está al máximo: se refuerza la señal (hasta +15 dB, con limitador)
            boostMb = Math.max(0, (Math.min(200, vol) - 100) * 15);
        }
        startOnce(kind, text, lang, pitch, rate);
    }

    private synchronized void startOnce(String kind, String text, String lang, float pitch, float rate) {
        stopPlayback();
        long ms;
        if ("voz".equals(kind)) ms = speak(text, lang, pitch, rate);
        else if ("propio".equals(kind) && ownFile(ctx).exists()) ms = playOwn();
        else ms = playTone(kind);
        if (looping) handler.postDelayed(() -> startOnce(kind, text, lang, pitch, rate), ms + 500);
        else handler.postDelayed(restore, ms + 400);
    }

    synchronized void stop() {
        looping = false;
        stopPlayback();
        restoreVolume();
    }

    private void boost(int sessionId) {
        releaseBoost();
        if (boostMb <= 0 || sessionId == 0) return;
        try {
            enhancer = new LoudnessEnhancer(sessionId);
            enhancer.setTargetGain(boostMb);
            enhancer.setEnabled(true);
        } catch (Exception ignored) {
            enhancer = null;
        }
    }

    private void releaseBoost() {
        if (enhancer != null) {
            try { enhancer.release(); } catch (Exception ignored) {}
            enhancer = null;
        }
    }

    private void stopPlayback() {
        handler.removeCallbacksAndMessages(null);
        releaseBoost();
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) {}
            try { track.release(); } catch (Exception ignored) {}
            track = null;
        }
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        if (tts != null) try { tts.stop(); } catch (Exception ignored) {}
    }

    private boolean headphones() {
        if (am == null || Build.VERSION.SDK_INT < 23) return false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_USB_HEADSET) return true;
            if (Build.VERSION.SDK_INT >= 31 && (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER)) return true;
        }
        return false;
    }

    /* ---------- Volumen ---------- */

    private void applyVolume(int vol) {
        if (am == null) return;
        try {
            if (savedVol < 0) savedVol = am.getStreamVolume(AudioManager.STREAM_ALARM);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            int idx = Math.max(1, Math.round(max * Math.max(0, Math.min(100, vol)) / 100f));
            am.setStreamVolume(AudioManager.STREAM_ALARM, idx, 0);
        } catch (Exception ignored) {
            // No Molestar puede impedir tocar el volumen: se reproduce con el que haya
        }
    }

    private void restoreVolume() {
        if (am == null || savedVol < 0) return;
        try { am.setStreamVolume(AudioManager.STREAM_ALARM, savedVol, 0); } catch (Exception ignored) {}
        savedVol = -1;
    }

    /* ---------- Tonos ---------- */

    private long playTone(String kind) {
        short[] pcm = synth(kind);
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2).build();
            track.write(pcm, 0, pcm.length);
            track.setVolume(gain);
            boost(track.getAudioSessionId());
            track.play();
        } catch (Exception ignored) {}
        return pcm.length * 1000L / RATE;
    }

    /** Sonidos pensados para oírse: tonos en la zona donde el altavoz y el oído rinden más, y saturados para ganar sonoridad. */
    private static short[] synth(String kind) {
        float[] b;
        float drive;
        if ("campana".equals(kind)) {
            b = new float[(int) (2.6f * RATE)];
            bell(b, 0f, 1320, 1f, 2.0f);
            bell(b, 0f, 2640, .7f, 1.6f);
            bell(b, 0f, 3960, .35f, 1.2f);
            bell(b, .9f, 1320, .9f, 1.6f);
            bell(b, .9f, 2640, .5f, 1.2f);
            drive = 2.5f;
        } else if ("gong".equals(kind)) {
            b = new float[(int) (2.8f * RATE)];
            // Los graves puros apenas salen por el altavoz: se refuerzan los armónicos
            bell(b, 0f, 392, 1f, 2.6f);
            bell(b, 0f, 784, .9f, 2.2f);
            bell(b, 0f, 1176, .7f, 1.8f);
            bell(b, 0f, 1568, .45f, 1.4f);
            drive = 3f;
        } else {
            b = new float[(int) (1.25f * RATE)];
            for (float t : new float[]{0f, .3f, .6f, .9f}) beep(b, t, .24f);
            drive = 3.5f;
        }
        float peak = .0001f;
        for (float v : b) peak = Math.max(peak, Math.abs(v));
        short[] out = new short[b.length];
        float norm = (float) Math.tanh(drive);
        for (int i = 0; i < b.length; i++) out[i] = (short) (32000f * (float) Math.tanh(drive * b[i] / peak) / norm);
        return out;
    }

    private static void bell(float[] b, float start, float f, float amp, float decay) {
        int s = (int) (start * RATE);
        for (int i = s; i < b.length; i++) {
            float t = (i - s) / (float) RATE;
            float attack = Math.min(1f, t / .004f);
            b[i] += attack * amp * (float) (Math.sin(2 * Math.PI * f * t) * Math.exp(-3.2 * t / decay));
        }
    }

    private static void beep(float[] b, float start, float dur) {
        int s = (int) (start * RATE), n = (int) (dur * RATE);
        for (int i = 0; i < n && s + i < b.length; i++) {
            float t = i / (float) RATE;
            float env = Math.min(1f, Math.min(t / .004f, (dur - t) / .012f));
            double v = Math.sin(2 * Math.PI * 2093 * t) + .7 * Math.sin(2 * Math.PI * 3136 * t) + .4 * Math.sin(2 * Math.PI * 4186 * t);
            b[s + i] += env * (float) v;
        }
    }

    /* ---------- Audio propio ---------- */

    static File ownFile(Context c) { return new File(c.getFilesDir(), "alert_own.bin"); }

    private long playOwn() {
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(attrs);
            player.setDataSource(ownFile(ctx).getAbsolutePath());
            player.prepare();
            player.setVolume(gain, gain);
            boost(player.getAudioSessionId());
            player.start();
            return Math.max(500, player.getDuration());
        } catch (Exception e) {
            player = null;
            return playTone("pitido");
        }
    }

    /* ---------- Voz ---------- */

    private long speak(String text, String lang, float pitch, float rate) {
        String say = text == null || text.isEmpty() ? "Temvra" : text;
        Runnable go = () -> {
            try {
                tts.setAudioAttributes(attrs);
                if (lang != null) tts.setLanguage(Locale.forLanguageTag(lang));
                tts.setPitch(pitch > 0 ? pitch : 1f);
                tts.setSpeechRate(rate > 0 ? rate : 1f);
                Bundle params = new Bundle();
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, gain);
                tts.speak(say, TextToSpeech.QUEUE_FLUSH, params, "temvra");
            } catch (Exception ignored) {}
        };
        if (tts != null && ttsReady) go.run();
        else if (tts == null) tts = new TextToSpeech(ctx, st -> { ttsReady = st == TextToSpeech.SUCCESS; if (ttsReady) go.run(); });
        return 1200 + (long) (say.length() * 85 / Math.max(.5f, rate));
    }
}
