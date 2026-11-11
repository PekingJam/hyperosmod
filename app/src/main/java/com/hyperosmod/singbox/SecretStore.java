package com.hyperosmod.singbox;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 云盘密码的本地加密存储。
 *
 * <p>SharedPreferences 是明文的，WebDAV 密码直接写进去等于裸奔。这里用 Android Keystore
 * 里的 AES-GCM 密钥加密后再存，密钥不出安全硬件，应用被反编译也读不到明文。</p>
 */
public final class SecretStore {

    private static final String KEY_ALIAS = "hyperosmod_webdav";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private SecretStore() {
    }

    public static String encrypt(Context context, String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] data = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + data.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(data, 0, packed, iv.length, data.length);
            return Base64.encodeToString(packed, Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    public static String decrypt(Context context, String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        try {
            byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
            int ivLength = 12;
            byte[] iv = new byte[ivLength];
            byte[] data = new byte[packed.length - ivLength];
            System.arraycopy(packed, 0, iv, 0, ivLength);
            System.arraycopy(packed, ivLength, data, 0, data.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
