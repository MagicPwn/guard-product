package com.example.lockdowndpc.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

public final class QuizTrustSetTest {
    private static KeyPair first;
    private static KeyPair second;

    @BeforeClass
    public static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        first = generator.generateKeyPair();
        second = generator.generateKeyPair();
    }

    private static String publicKey(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private static String transition(KeyPair oldKey, KeyPair newKey, KeyPair signer,
            long notBefore) throws Exception {
        String oldKeyId = QuizPackVerifier.keyId(oldKey.getPublic());
        String newKeyId = QuizPackVerifier.keyId(newKey.getPublic());
        String normalizedNew = Base64.getEncoder().encodeToString(newKey.getPublic().getEncoded());
        String message = "guard-quiz-key-transition-v1\n" + oldKeyId + "\n" + newKeyId
                + "\n" + normalizedNew + "\n" + notBefore;
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(signer.getPrivate());
        signature.update(message.getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("schema", 1).put("type", "quiz-key-transition")
                .put("old_key_id", oldKeyId).put("new_key_id", newKeyId)
                .put("new_public_key", normalizedNew).put("not_before", notBefore)
                .put("signature", Base64.getEncoder().encodeToString(signature.sign()))
                .toString();
    }

    @Test
    public void multipleKeysCanCoexistAndDuplicatesAreIgnored() {
        String encoded = QuizTrustSet.add("", publicKey(first));
        encoded = QuizTrustSet.add(encoded, publicKey(second));
        encoded = QuizTrustSet.add(encoded, publicKey(second));
        List<String> keys = QuizTrustSet.parse(encoded);
        assertEquals(2, keys.size());
        assertTrue(QuizTrustSet.containsKeyId(encoded, QuizPackVerifier.keyId(first.getPublic())));
        assertTrue(QuizTrustSet.containsKeyId(encoded, QuizPackVerifier.keyId(second.getPublic())));
        assertFalse(QuizTrustSet.containsKeyId(encoded, "missing"));
    }

    @Test
    public void legacySingleKeyMigratesWithoutDroppingExistingTrust() {
        String current = QuizTrustSet.add("", publicKey(second));
        String migrated = QuizTrustSet.mergeLegacy(current, publicKey(first));
        List<String> keys = QuizTrustSet.parse(migrated);
        assertEquals(2, keys.size());
        assertTrue(QuizTrustSet.containsKeyId(migrated, QuizPackVerifier.keyId(first.getPublic())));
        assertTrue(QuizTrustSet.containsKeyId(migrated, QuizPackVerifier.keyId(second.getPublic())));
    }

    @Test
    public void trustSetLimitAndInvalidInputAreRejected() throws Exception {
        String encoded = "";
        for (int index = 0; index < QuizTrustSet.MAX_KEYS; index++) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            encoded = QuizTrustSet.add(encoded, publicKey(generator.generateKeyPair()));
        }
        String full = encoded;
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizTrustSet.add(full, publicKey(first)));
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizTrustSet.parse("not-json"));
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizTrustSet.add("", "not-a-key"));
    }

    @Test
    public void transitionAddsTheNewKeyAndIgnoresUnknownPredecessors() throws Exception {
        String signed = transition(first, second, first, System.currentTimeMillis());
        assertTrue(QuizTrustSet.parse(QuizTrustSet.applyTransition("", signed)).isEmpty());

        String trusted = QuizTrustSet.add("", publicKey(first));
        String migrated = QuizTrustSet.applyTransition(trusted, signed);
        assertTrue(QuizTrustSet.containsKeyId(migrated,
                QuizPackVerifier.keyId(first.getPublic())));
        assertTrue(QuizTrustSet.containsKeyId(migrated,
                QuizPackVerifier.keyId(second.getPublic())));
        assertEquals(migrated, QuizTrustSet.applyTransition(migrated, signed));
    }

    @Test
    public void transitionMustBeSignedByTheTrustedOldKey() throws Exception {
        String trusted = QuizTrustSet.add("", publicKey(first));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair attacker = generator.generateKeyPair();
        String signed = transition(first, second, attacker, System.currentTimeMillis());
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizTrustSet.applyTransition(trusted, signed));
    }

    @Test
    public void revocationNeverRemovesTheLastTrustedKey() {
        String trusted = QuizTrustSet.add("", publicKey(first));
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizTrustSet.revoke(trusted, QuizPackVerifier.keyId(first.getPublic())));
        String both = QuizTrustSet.add(trusted, publicKey(second));
        String remaining = QuizTrustSet.revoke(both,
                QuizPackVerifier.keyId(second.getPublic()));
        assertTrue(QuizTrustSet.containsKeyId(remaining,
                QuizPackVerifier.keyId(first.getPublic())));
        assertFalse(QuizTrustSet.containsKeyId(remaining,
                QuizPackVerifier.keyId(second.getPublic())));
    }
}
