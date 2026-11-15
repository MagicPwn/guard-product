package com.example.lockdowndpc.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Durable, local state for the optional parent-service connection. */
public final class GuardRemoteStore {
    private static final String PREFS = "guard_remote";
    private static final String KEY_SERVER = "server_url";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_TOKEN = "device_token_ciphertext";
    private static final String KEY_TOKEN_EXPIRES = "device_token_expires";
    private static final String KEY_PAIRING_CODE = "pairing_code";
    private static final String KEY_PAIRING_EXPIRES = "pairing_expires";
    private static final String KEY_PAIRED = "paired";
    private static final String KEY_CONFIRMED = "confirmed";
    private static final String KEY_PARENT_EMAIL = "parent_email";
    private static final String KEY_PINNED_KEY_ID = "pinned_policy_key_id";
    private static final String KEY_PINNED_PUBLIC_KEY = "pinned_policy_public_key";
    private static final String KEY_CLOUD_REVISION = "accepted_cloud_revision";
    private static final String KEY_LAST_SYNC = "last_sync_at";
    private static final String KEY_LAST_AUDIT = "last_audit_upload_at";
    private static final String KEY_LAST_ERROR = "last_sync_error";
    private static final String KEY_SECRET_ALIAS = "guard.remote.token.v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private GuardRemoteStore() {}

    public static synchronized String serverUrl(Context context) {
        String stored = prefs(context).getString(KEY_SERVER, "");
        if (stored == null || stored.isEmpty()) {
            String buildDefault = com.example.lockdowndpc.BuildConfig.GUARD_SERVER_URL;
            if (buildDefault == null || buildDefault.trim().isEmpty()) return "";
            try {
                return normalizeServerUrl(buildDefault);
            } catch (IllegalArgumentException exception) {
                return "";
            }
        }
        try {
            return normalizeServerUrl(stored);
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    public static synchronized boolean setServerUrl(Context context, String value) {
        String normalized = normalizeServerUrl(value);
        boolean changed = !normalized.equals(serverUrl(context));
        SharedPreferences.Editor editor = prefs(context).edit().putString(KEY_SERVER, normalized);
        if (changed) {
            editor.remove(KEY_TOKEN).remove(KEY_TOKEN_EXPIRES).remove(KEY_PAIRING_CODE)
                    .remove(KEY_PAIRING_EXPIRES).remove(KEY_PAIRED).remove(KEY_CONFIRMED)
                    .remove(KEY_PARENT_EMAIL).remove(KEY_CLOUD_REVISION).remove(KEY_LAST_SYNC)
                    .remove(KEY_LAST_ERROR);
        }
        return editor.commit();
    }

    public static synchronized String normalizeServerUrl(String value) {
        String input = value == null ? "" : value.trim();
        if (input.isEmpty()) throw new IllegalArgumentException("Server URL is required");
        while (input.endsWith("/")) input = input.substring(0, input.length() - 1);
        URI uri;
        try {
            uri = URI.create(input);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Server URL is invalid", exception);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isEmpty() || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Server URL must be a plain HTTPS origin");
        }
        boolean loopback = host.equals("localhost") || host.equals("10.0.2.2")
                || host.equals("127.0.0.1");
        if (!scheme.equalsIgnoreCase("https") && !(scheme.equalsIgnoreCase("http") && loopback)) {
            throw new IllegalArgumentException("Use HTTPS; HTTP is allowed only for local development");
        }
        if (input.length() > 500) throw new IllegalArgumentException("Server URL is too long");
        return input;
    }

    public static synchronized String deviceId(Context context) {
        String existing = prefs(context).getString(KEY_DEVICE_ID, null);
        if (existing != null && existing.matches("^[A-Za-z0-9._:-]{8,128}$")) return existing;
        String generated = "android-" + UUID.randomUUID();
        prefs(context).edit().putString(KEY_DEVICE_ID, generated).commit();
        return generated;
    }

    public static synchronized boolean isPaired(Context context) {
        return prefs(context).getBoolean(KEY_PAIRED, false);
    }

    public static synchronized boolean isConfirmed(Context context) {
        return prefs(context).getBoolean(KEY_CONFIRMED, false);
    }

    public static synchronized void setPairingState(Context context, boolean paired,
            boolean confirmed, String parentEmail) {
        prefs(context).edit().putBoolean(KEY_PAIRED, paired).putBoolean(KEY_CONFIRMED, confirmed)
                .putString(KEY_PARENT_EMAIL, parentEmail == null ? "" : parentEmail).commit();
    }

    public static synchronized String pairingCode(Context context) {
        return prefs(context).getString(KEY_PAIRING_CODE, "");
    }

    public static synchronized long pairingExpiresAt(Context context) {
        return prefs(context).getLong(KEY_PAIRING_EXPIRES, 0L);
    }

    public static synchronized void setPairingCode(Context context, String code, long expiresAt) {
        prefs(context).edit().putString(KEY_PAIRING_CODE, code == null ? "" : code)
                .putLong(KEY_PAIRING_EXPIRES, expiresAt).putBoolean(KEY_PAIRED, false)
                .putBoolean(KEY_CONFIRMED, false).commit();
    }

    public static synchronized String deviceToken(Context context) {
        String encoded = prefs(context).getString(KEY_TOKEN, null);
        if (encoded == null) return null;
        try {
            return decrypt(encoded);
        } catch (Exception exception) {
            prefs(context).edit().remove(KEY_TOKEN).remove(KEY_TOKEN_EXPIRES).commit();
            return null;
        }
    }

    public static synchronized boolean setDeviceToken(Context context, String token, long expiresAt) {
        try {
            return prefs(context).edit().putString(KEY_TOKEN, encrypt(token))
                    .putLong(KEY_TOKEN_EXPIRES, expiresAt).commit();
        } catch (Exception exception) {
            return false;
        }
    }

    public static synchronized long deviceTokenExpiresAt(Context context) {
        return prefs(context).getLong(KEY_TOKEN_EXPIRES, 0L);
    }

    public static synchronized void clearDeviceToken(Context context) {
        prefs(context).edit().remove(KEY_TOKEN).remove(KEY_TOKEN_EXPIRES).commit();
    }

    public static synchronized String pinnedKeyId(Context context) {
        return prefs(context).getString(KEY_PINNED_KEY_ID, "");
    }

    public static synchronized String pinnedPublicKey(Context context) {
        return prefs(context).getString(KEY_PINNED_PUBLIC_KEY, "");
    }

    public static synchronized boolean pinPolicyKey(Context context, String keyId, String publicKey) {
        if (keyId == null || publicKey == null) return false;
        String expected = GuardPolicyVerifier.keyIdForPublicKey(publicKey);
        if (expected == null || !expected.equals(keyId)) return false;
        return prefs(context).edit().putString(KEY_PINNED_KEY_ID, keyId)
                .putString(KEY_PINNED_PUBLIC_KEY, publicKey).commit();
    }

    public static synchronized long acceptedCloudRevision(Context context) {
        return prefs(context).getLong(KEY_CLOUD_REVISION, 0L);
    }

    public static synchronized boolean setAcceptedCloudRevision(Context context, long revision) {
        if (revision < acceptedCloudRevision(context)) return false;
        return prefs(context).edit().putLong(KEY_CLOUD_REVISION, revision).commit();
    }

    public static synchronized long lastSyncAt(Context context) {
        return prefs(context).getLong(KEY_LAST_SYNC, 0L);
    }

    public static synchronized void markSynced(Context context) {
        prefs(context).edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .remove(KEY_LAST_ERROR).commit();
    }

    public static synchronized void recordError(Context context, String message) {
        prefs(context).edit().putString(KEY_LAST_ERROR,
                message == null ? "unknown" : message.substring(0, Math.min(300, message.length()))).commit();
    }

    public static synchronized String lastError(Context context) {
        return prefs(context).getString(KEY_LAST_ERROR, "");
    }

    public static synchronized long lastAuditUploadAt(Context context) {
        return prefs(context).getLong(KEY_LAST_AUDIT, 0L);
    }

    public static synchronized void markAuditUploaded(Context context, long timestamp) {
        prefs(context).edit().putLong(KEY_LAST_AUDIT, timestamp).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_SECRET_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_SECRET_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_SECRET_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private static String encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private static String decrypt(String encoded) throws Exception {
        int separator = encoded.indexOf(':');
        if (separator <= 0) throw new IllegalArgumentException("Invalid encrypted state");
        byte[] iv = Base64.decode(encoded.substring(0, separator), Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(encoded.substring(separator + 1), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
