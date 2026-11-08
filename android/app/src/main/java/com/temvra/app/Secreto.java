package com.temvra.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Secretos (el token de GitHub) cifrados con una clave del Android Keystore.
 * Se expone a la web como window.VTSecret, síncrono, porque la app los necesita al arrancar y entonces los plugins aún no están disponibles.
 */
final class Secreto {
    private static final String ALIAS = "temvra_secreto";
    private static final String PREFS = "temvra_secreto";
    private static final int IV = 12;
    private final SharedPreferences prefs;

    Secreto(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return gen.generateKey();
    }

    @JavascriptInterface
    public boolean ok() {
        try {
            key();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Con el valor vacío lo borra. Devuelve false si no se ha podido guardar: la web lo deja entonces con sus datos */
    @JavascriptInterface
    public boolean set(String name, String value) {
        try {
            if (value == null || value.isEmpty()) return prefs.edit().remove(name).commit();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] data = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + data.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(data, 0, all, iv.length, data.length);
            return prefs.edit().putString(name, Base64.encodeToString(all, Base64.NO_WRAP)).commit();
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public String get(String name) {
        try {
            String s = prefs.getString(name, null);
            if (s == null) return "";
            byte[] all = Base64.decode(s, Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, IV));
            return new String(c.doFinal(all, IV, all.length - IV), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
