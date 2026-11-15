package com.example.lockdowndpc.guard;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public final class GuardPolicyVerifierTest {
    @Test
    public void acceptsValidFreshEnvelope() throws Exception {
        KeyPair pair = keyPair();
        String payload = payload(4);
        GuardPolicyVerifier.Result result = GuardPolicyVerifier.verify(
                envelope(payload, sign(pair, payload), keyId(pair), 4),
                keyId(pair), publicKey(pair), 3);
        assertNotNull(result.policy);
        assertEquals(4, result.revision);
        assertEquals("Asia/Shanghai", result.policy.timezone());
    }

    @Test
    public void rejectsRollbackAndEqualRevision() throws Exception {
        KeyPair pair = keyPair();
        String payload = payload(5);
        String envelope = envelope(payload, sign(pair, payload), keyId(pair), 5);
        expectInvalid(envelope, keyId(pair), publicKey(pair), 5);
        expectInvalid(envelope, keyId(pair), publicKey(pair), 9);
    }

    @Test
    public void rejectsTamperedPayloadAndWrongKeyId() throws Exception {
        KeyPair pair = keyPair();
        String payload = payload(6);
        String signed = envelope(payload, sign(pair, payload), keyId(pair), 6);
        JSONObject tampered = new JSONObject(signed);
        tampered.put("payload", tampered.getString("payload")
                .replace("\"reward_minutes\":10", "\"reward_minutes\":11"));
        expectInvalid(tampered.toString(), keyId(pair), publicKey(pair), 0);
        expectInvalid(signed, keyId(keyPair()), publicKey(pair), 0);
    }

    @Test
    public void rejectsUnexpectedEnvelopeFields() throws Exception {
        KeyPair pair = keyPair();
        String payload = payload(7);
        JSONObject envelope = new JSONObject(envelope(payload, sign(pair, payload), keyId(pair), 7));
        envelope.put("unsigned_note", "ignore me");
        expectInvalid(envelope.toString(), keyId(pair), publicKey(pair), 0);
    }

    private static void expectInvalid(String envelope, String keyId, String publicKey, long after) {
        try {
            GuardPolicyVerifier.verify(envelope, keyId, publicKey, after);
            fail("Expected policy rejection");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static String payload(long revision) {
        Map<String, Integer> budgets = new LinkedHashMap<>();
        budgets.put("com.example.game", 60);
        return new GuardPolicy(revision, "Asia/Shanghai", true, false, true,
                10, 8, 60, budgets, Collections.singleton("com.example.blocked"))
                .toJson().toString();
    }

    private static String envelope(String payload, String signature, String keyId, long revision)
            throws Exception {
        return new JSONObject().put("schema", 1).put("key_id", keyId)
                .put("revision", revision).put("payload", payload)
                .put("signature", signature).toString();
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        return generator.generateKeyPair();
    }

    private static String publicKey(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private static String keyId(KeyPair pair) {
        String value = GuardPolicyVerifier.keyIdForPublicKey(publicKey(pair));
        assertNotNull(value);
        return value;
    }

    private static String sign(KeyPair pair, String payload) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }
}
