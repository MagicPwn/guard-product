package com.example.lockdowndpc.guard;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/** Hardware-backed identity for device registration and token renewal. */
public final class GuardDeviceIdentity {
    private static final String ALIAS = "guard.device.identity.v1";
    private static final String PROVIDER = "AndroidKeyStore";

    private GuardDeviceIdentity() {}

    public static synchronized String publicKeyBase64(Context context) {
        try {
            return Base64.encodeToString(publicKey().getEncoded(), Base64.NO_WRAP);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create device identity", exception);
        }
    }

    public static String signBase64(String message) {
        try {
            PrivateKey privateKey = (PrivateKey) keyStore().getKey(ALIAS, null);
            if (privateKey == null) throw new IllegalStateException("Device key is missing");
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(signer.sign(), Base64.NO_WRAP);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign device proof", exception);
        }
    }

    private static PublicKey publicKey() throws Exception {
        KeyStore store = keyStore();
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, PROVIDER);
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build());
            generator.generateKeyPair();
        }
        return store.getCertificate(ALIAS).getPublicKey();
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance(PROVIDER);
        store.load(null);
        return store;
    }
}
