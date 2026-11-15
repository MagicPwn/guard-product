package com.example.lockdowndpc.guard;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/** Verifies the authenticated policy envelope issued by the parent service. */
public final class GuardPolicyVerifier {
    private static final Set<String> ENVELOPE_KEYS = new HashSet<>(
            Arrays.asList("schema", "key_id", "revision", "payload", "signature"));
    private static final int MAX_PAYLOAD_BYTES = 262_144;

    private GuardPolicyVerifier() {}

    public static final class Result {
        public final GuardPolicy policy;
        public final long revision;
        public final String keyId;

        Result(GuardPolicy policy, long revision, String keyId) {
            this.policy = policy;
            this.revision = revision;
            this.keyId = keyId;
        }
    }

    public static Result verify(String envelopeJson, String pinnedKeyId,
            String pinnedPublicKey, long lastAcceptedRevision) {
        if (envelopeJson == null || envelopeJson.length() > 350_000) {
            throw new IllegalArgumentException("Policy envelope is missing or too large");
        }
        try {
            JSONObject envelope = new JSONObject(envelopeJson);
            Set<String> keys = new HashSet<>();
            java.util.Iterator<String> iterator = envelope.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            if (!keys.equals(ENVELOPE_KEYS)) {
                throw new IllegalArgumentException("Policy envelope has unexpected fields");
            }
            if (envelope.getInt("schema") != 1) {
                throw new IllegalArgumentException("Unsupported policy envelope schema");
            }
            String keyId = envelope.getString("key_id");
            long revision = envelope.getLong("revision");
            String payload = envelope.getString("payload");
            String signature = envelope.getString("signature");
            if (pinnedKeyId == null || pinnedKeyId.isEmpty()
                    || pinnedPublicKey == null || pinnedPublicKey.isEmpty()) {
                throw new IllegalArgumentException("No policy signing key is pinned");
            }
            if (!constantTimeEquals(pinnedKeyId, keyId)) {
                throw new IllegalArgumentException("Policy was signed by an untrusted key");
            }
            if (revision <= lastAcceptedRevision) {
                throw new IllegalArgumentException("Policy revision would roll back");
            }
            int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
            if (payloadBytes == 0 || payloadBytes > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Policy payload is too large");
            }
            if (!keyId.equals(keyIdForPublicKey(pinnedPublicKey))) {
                throw new IllegalArgumentException("Pinned policy key id does not match");
            }
            if (!verifySignature(pinnedPublicKey, payload, signature)) {
                throw new IllegalArgumentException("Policy signature is invalid");
            }
            GuardPolicy policy = GuardPolicy.fromJson(payload);
            if (policy.revision() != revision) {
                throw new IllegalArgumentException("Policy revision does not match envelope");
            }
            return new Result(policy, revision, keyId);
        } catch (JSONException exception) {
            throw new IllegalArgumentException("Policy envelope is invalid JSON", exception);
        }
    }

    public static String keyIdForPublicKey(String publicKeyBase64) {
        try {
            byte[] encoded = Base64.getDecoder().decode(publicKeyBase64);
            PublicKey key = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(encoded));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return hex.toString();
        } catch (Exception exception) {
            return null;
        }
    }

    private static boolean verifySignature(String publicKeyBase64, String payload, String signatureBase64) {
        try {
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
