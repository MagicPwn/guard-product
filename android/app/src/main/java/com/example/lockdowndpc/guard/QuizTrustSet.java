package com.example.lockdowndpc.guard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure helper for the local quiz trust set and its persisted JSON representation. */
public final class QuizTrustSet {
    public static final int MAX_KEYS = 8;

    private QuizTrustSet() {}

    public static List<String> parse(String encoded) {
        if (encoded == null || encoded.trim().isEmpty()) return Collections.emptyList();
        try {
            JSONArray array = new JSONArray(encoded);
            if (array.length() > MAX_KEYS) {
                throw new QuizPackVerifier.QuizPackException("Too many quiz trust keys");
            }
            List<String> keys = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int index = 0; index < array.length(); index++) {
                String value = array.getString(index);
                PublicKey key = QuizPackVerifier.parsePublicKey(value);
                String id = QuizPackVerifier.keyId(key);
                if (!ids.add(id)) {
                    throw new QuizPackVerifier.QuizPackException("Duplicate quiz trust key");
                }
                keys.add(Base64.getEncoder().encodeToString(key.getEncoded()));
            }
            return Collections.unmodifiableList(keys);
        } catch (JSONException exception) {
            throw new QuizPackVerifier.QuizPackException("Quiz trust set is not valid JSON", exception);
        }
    }

    public static String add(String encoded, String publicKeyBase64) {
        List<String> current = parse(encoded);
        PublicKey key = QuizPackVerifier.parsePublicKey(publicKeyBase64);
        String normalized = Base64.getEncoder().encodeToString(key.getEncoded());
        String targetId = QuizPackVerifier.keyId(key);
        List<String> next = new ArrayList<>();
        for (String existing : current) {
            PublicKey existingKey = QuizPackVerifier.parsePublicKey(existing);
            if (QuizPackVerifier.keyId(existingKey).equalsIgnoreCase(targetId)) return encode(current);
            next.add(existing);
        }
        if (next.size() >= MAX_KEYS) {
            throw new QuizPackVerifier.QuizPackException("At most " + MAX_KEYS
                    + " quiz trust keys are supported");
        }
        next.add(normalized);
        return encode(next);
    }

    public static String mergeLegacy(String encoded, String legacyPublicKeyBase64) {
        if (legacyPublicKeyBase64 == null || legacyPublicKeyBase64.trim().isEmpty()) {
            return encode(parse(encoded));
        }
        return add(encoded, legacyPublicKeyBase64);
    }

    public static boolean containsKeyId(String encoded, String keyId) {
        if (keyId == null || keyId.isEmpty()) return false;
        for (String key : parse(encoded)) {
            if (QuizPackVerifier.keyId(QuizPackVerifier.parsePublicKey(key))
                    .equalsIgnoreCase(keyId)) return true;
        }
        return false;
    }

    /**
     * Applies an old-key-signed transition. A transition whose old key is not
     * already trusted is ignored, which makes delivery order and retries safe.
     */
    public static String applyTransition(String encoded, String transitionJson) {
        List<String> current = parse(encoded);
        if (transitionJson == null || transitionJson.isEmpty() || transitionJson.length() > 8_000) {
            throw new QuizPackVerifier.QuizPackException("Key transition is missing or too large");
        }
        try {
            JSONObject transition = new JSONObject(transitionJson);
            if (transition.getInt("schema") != 1
                    || !"quiz-key-transition".equals(transition.getString("type"))) {
                throw new QuizPackVerifier.QuizPackException("Unsupported key transition");
            }
            String oldKeyId = transition.getString("old_key_id").toLowerCase(java.util.Locale.ROOT);
            String newKeyId = transition.getString("new_key_id").toLowerCase(java.util.Locale.ROOT);
            String newPublicKey = transition.getString("new_public_key").trim();
            long notBefore = transition.getLong("not_before");
            if (oldKeyId.isEmpty() || newKeyId.isEmpty() || oldKeyId.equals(newKeyId)) {
                throw new QuizPackVerifier.QuizPackException("Key transition ids are invalid");
            }
            if (notBefore > System.currentTimeMillis() + 60L * 60L * 1000L) {
                throw new QuizPackVerifier.QuizPackException("Key transition is not active yet");
            }
            String oldPublicKey = null;
            for (String key : current) {
                PublicKey parsed = QuizPackVerifier.parsePublicKey(key);
                if (QuizPackVerifier.keyId(parsed).equalsIgnoreCase(oldKeyId)) {
                    oldPublicKey = key;
                    break;
                }
            }
            if (oldPublicKey == null) return encode(current);
            PublicKey newKey = QuizPackVerifier.parsePublicKey(newPublicKey);
            String normalizedNew = Base64.getEncoder().encodeToString(newKey.getEncoded());
            if (!QuizPackVerifier.keyId(newKey).equalsIgnoreCase(newKeyId)) {
                throw new QuizPackVerifier.QuizPackException(
                        "Key transition new_key_id does not match its public key");
            }
            String message = "guard-quiz-key-transition-v1\n" + oldKeyId + "\n" + newKeyId
                    + "\n" + normalizedNew + "\n" + notBefore;
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(QuizPackVerifier.parsePublicKey(oldPublicKey));
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getDecoder().decode(transition.getString("signature")))) {
                throw new QuizPackVerifier.QuizPackException(
                        "Key transition signature does not match the old key");
            }
            return add(encoded, normalizedNew);
        } catch (QuizPackVerifier.QuizPackException exception) {
            throw exception;
        } catch (JSONException | java.security.GeneralSecurityException
                | IllegalArgumentException exception) {
            throw new QuizPackVerifier.QuizPackException("Key transition is invalid", exception);
        }
    }

    public static String revoke(String encoded, String keyId) {
        List<String> current = parse(encoded);
        List<String> next = new ArrayList<>();
        for (String key : current) {
            if (!QuizPackVerifier.keyId(QuizPackVerifier.parsePublicKey(key))
                    .equalsIgnoreCase(keyId)) next.add(key);
        }
        if (next.isEmpty()) {
            throw new QuizPackVerifier.QuizPackException("Cannot revoke the last quiz trust key");
        }
        return encode(next);
    }

    public static String encode(List<String> keys) {
        JSONArray array = new JSONArray();
        for (String key : keys) array.put(key);
        return array.toString();
    }
}
