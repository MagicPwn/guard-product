package com.example.lockdowndpc.guard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

public final class QuizPackVerifier {
    public static final int SCHEMA = 1;
    public static final int MAX_QUESTIONS = 500;
    private static final int MAX_ENVELOPE_CHARS = 8_000_000;
    private static final int MAX_PAYLOAD_BYTES = 6_000_000;
    private static final int MAX_PACK_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final Set<String> COMMON_QUESTION_KEYS = keys(
            "id", "prompt", "type", "subject", "grade", "difficulty", "language", "tags",
            "image_base64", "image_mime");
    private static final Set<String> CHOICE_KEYS = union(COMMON_QUESTION_KEYS, "options");
    private static final Set<String> SINGLE_KEYS = union(CHOICE_KEYS, "answer_index");
    private static final Set<String> MULTIPLE_KEYS = union(CHOICE_KEYS, "answer_indices");
    private static final Set<String> BOOLEAN_KEYS = union(COMMON_QUESTION_KEYS, "answer");
    private static final Set<String> SHORT_KEYS = union(COMMON_QUESTION_KEYS, "accepted_answers");

    private QuizPackVerifier() {}

    public static PublicKey parsePublicKey(String base64) {
        try {
            byte[] der = Base64.getDecoder().decode(base64.trim());
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception exception) {
            throw new QuizPackException("The trust anchor is not a valid EC public key", exception);
        }
    }

    public static String keyId(PublicKey key) {
        return fingerprint(key.getEncoded());
    }

    public static boolean isKeyTrusted(List<String> trustedPublicKeys, String keyId) {
        if (trustedPublicKeys == null || keyId == null || keyId.isEmpty()) return false;
        for (String trusted : trustedPublicKeys) {
            if (keyId(parsePublicKey(trusted)).equalsIgnoreCase(keyId)) return true;
        }
        return false;
    }

    public static VerifiedPack verifyAny(String envelopeJson, List<String> trustedPublicKeys) {
        if (trustedPublicKeys == null || trustedPublicKeys.isEmpty()) {
            throw new QuizPackException("No quiz trust anchor is installed");
        }
        String envelopeKeyId = envelopeKeyId(envelopeJson);
        for (String trusted : trustedPublicKeys) {
            PublicKey key = parsePublicKey(trusted);
            if (keyId(key).equalsIgnoreCase(envelopeKeyId)) {
                return verify(envelopeJson, Base64.getEncoder().encodeToString(key.getEncoded()));
            }
        }
        throw new QuizPackException("Quiz pack was signed by an untrusted key");
    }

    private static String envelopeKeyId(String envelopeJson) {
        if (envelopeJson == null || envelopeJson.isEmpty()
                || envelopeJson.length() > MAX_ENVELOPE_CHARS) {
            throw new QuizPackException("Quiz envelope is missing or too large");
        }
        try {
            JSONObject envelope = new JSONObject(envelopeJson);
            if (envelope.getInt("schema") != SCHEMA) {
                throw new QuizPackException("Unsupported quiz envelope schema");
            }
            return envelope.getString("key_id");
        } catch (QuizPackException exception) {
            throw exception;
        } catch (JSONException exception) {
            throw new QuizPackException("Quiz envelope is not valid JSON", exception);
        }
    }

    public static VerifiedPack verify(String envelopeJson, String trustedPublicKeyBase64) {
        PublicKey trusted = parsePublicKey(trustedPublicKeyBase64);
        try {
            if (envelopeJson == null || envelopeJson.isEmpty()
                    || envelopeJson.length() > MAX_ENVELOPE_CHARS) {
                throw new QuizPackException("Quiz envelope is missing or too large");
            }
            JSONObject envelope = new JSONObject(envelopeJson);
            if (envelope.getInt("schema") != SCHEMA) {
                throw new QuizPackException("Unsupported quiz envelope schema");
            }
            String keyId = envelope.getString("key_id");
            String payload = envelope.getString("payload");
            String signatureText = envelope.getString("signature");
            int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
            if (payloadBytes == 0 || payloadBytes > MAX_PAYLOAD_BYTES) {
                throw new QuizPackException("Quiz payload is too large");
            }
            if (!keyId(trusted).equalsIgnoreCase(keyId)) {
                throw new QuizPackException("Quiz pack was signed by an untrusted key");
            }
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(trusted);
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            byte[] signature;
            try {
                signature = Base64.getDecoder().decode(signatureText);
            } catch (IllegalArgumentException exception) {
                throw new QuizPackException("Quiz signature is not valid base64", exception);
            }
            if (!verifier.verify(signature)) {
                throw new QuizPackException("Quiz signature does not match the payload");
            }
            return parsePayload(payload, keyId);
        } catch (QuizPackException exception) {
            throw exception;
        } catch (JSONException exception) {
            throw new QuizPackException("Quiz envelope is not valid JSON", exception);
        } catch (Exception exception) {
            throw new QuizPackException("Quiz signature verification failed", exception);
        }
    }

    /**
     * Applies a signed schema-2 delta to the exact verified pack named by its
     * base_version. Callers fall back to the full envelope when this fails.
     */
    public static VerifiedPack verifyDelta(String deltaEnvelopeJson, VerifiedPack base,
            List<String> trustedPublicKeys) {
        if (base == null) throw new QuizPackException("A delta needs a verified base pack");
        if (trustedPublicKeys == null || trustedPublicKeys.isEmpty()) {
            throw new QuizPackException("No quiz trust anchor is installed");
        }
        String envelopeKeyId = envelopeKeyId(deltaEnvelopeJson);
        if (!envelopeKeyId.equalsIgnoreCase(base.keyId())) {
            throw new QuizPackException("A delta must be signed by the same key as its base pack");
        }
        String trusted = null;
        for (String candidate : trustedPublicKeys) {
            if (keyId(parsePublicKey(candidate)).equalsIgnoreCase(envelopeKeyId)) {
                trusted = candidate;
                break;
            }
        }
        if (trusted == null) throw new QuizPackException("Quiz delta key is not trusted");
        try {
            JSONObject envelope = new JSONObject(deltaEnvelopeJson);
            String payload = envelope.getString("payload");
            int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
            if (payloadBytes == 0 || payloadBytes > MAX_PAYLOAD_BYTES) {
                throw new QuizPackException("Quiz delta payload is too large");
            }
            PublicKey key = parsePublicKey(trusted);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            byte[] signature;
            try {
                signature = Base64.getDecoder().decode(envelope.getString("signature"));
            } catch (IllegalArgumentException exception) {
                throw new QuizPackException("Quiz delta signature is not valid base64", exception);
            }
            if (!verifier.verify(signature)) {
                throw new QuizPackException("Quiz delta signature does not match the payload");
            }
            JSONObject delta = new JSONObject(payload);
            if (delta.getInt("schema") != 2) {
                throw new QuizPackException("Unsupported quiz delta payload schema");
            }
            long version = delta.getLong("version");
            long baseVersion = delta.getLong("base_version");
            if (version <= baseVersion || baseVersion != base.version()) {
                throw new QuizPackException("Quiz delta does not apply to this base version");
            }
            String publishedAt = delta.getString("published_at");
            if (publishedAt.length() > 64) {
                throw new QuizPackException("Quiz delta published_at is too long");
            }
            JSONArray operations = delta.getJSONArray("operations");
            if (operations.length() < 1 || operations.length() > MAX_QUESTIONS) {
                throw new QuizPackException("Quiz delta has an invalid operation count");
            }
            LinkedHashMap<String, QuizQuestion> merged = new LinkedHashMap<>();
            for (QuizQuestion question : base.questions()) merged.put(question.id(), question);
            for (int index = 0; index < operations.length(); index++) {
                JSONObject operation = operations.getJSONObject(index);
                String op = operation.getString("op");
                if ("put".equals(op)) {
                    QuizQuestion question = parseQuestion(operation.getJSONObject("question"));
                    merged.put(question.id(), question);
                } else if ("remove".equals(op)) {
                    String id = operation.getString("id");
                    if (merged.remove(id) == null) {
                        throw new QuizPackException("Quiz delta removes a missing question");
                    }
                } else {
                    throw new QuizPackException("Quiz delta operation is not supported");
                }
            }
            if (merged.isEmpty() || merged.size() > MAX_QUESTIONS) {
                throw new QuizPackException("Quiz delta result has an invalid question count");
            }
            List<QuizQuestion> questions = new ArrayList<>(merged.values());
            int imageBytes = 0;
            for (QuizQuestion question : questions) {
                if (question.hasImage()) {
                    imageBytes += question.imageData().length;
                    if (imageBytes > MAX_PACK_IMAGE_BYTES) {
                        throw new QuizPackException("Embedded quiz images exceed 5 MiB in total");
                    }
                }
            }
            return new VerifiedPack(version, envelopeKeyId, publishedAt,
                    List.copyOf(questions), payload);
        } catch (QuizPackException exception) {
            throw exception;
        } catch (JSONException exception) {
            throw new QuizPackException("Quiz delta payload is invalid", exception);
        } catch (Exception exception) {
            throw new QuizPackException("Quiz delta signature verification failed", exception);
        }
    }

    public static VerifiedPack parsePayload(String payload, String keyId) {
        try {
            JSONObject json = new JSONObject(payload);
            if (json.getInt("schema") != SCHEMA) {
                throw new QuizPackException("Unsupported quiz payload schema");
            }
            long version = json.getLong("version");
            if (version < 1 || version > 9_999_999_999L) {
                throw new QuizPackException("Quiz pack version is out of range");
            }
            String publishedAt = json.getString("published_at");
            if (publishedAt.length() > 64) {
                throw new QuizPackException("Quiz published_at is too long");
            }
            JSONArray source = json.getJSONArray("questions");
            if (source.length() < 1 || source.length() > MAX_QUESTIONS) {
                throw new QuizPackException("Quiz pack needs 1 to " + MAX_QUESTIONS + " questions");
            }
            List<QuizQuestion> questions = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            int imageBytes = 0;
            for (int index = 0; index < source.length(); index++) {
                JSONObject item = source.getJSONObject(index);
                QuizQuestion question = parseQuestion(item);
                if (!ids.add(question.id())) {
                    throw new QuizPackException("Duplicate question id: " + question.id());
                }
                if (question.hasImage()) {
                    imageBytes += question.imageData().length;
                    if (imageBytes > MAX_PACK_IMAGE_BYTES) {
                        throw new QuizPackException("Embedded quiz images exceed 5 MiB in total");
                    }
                }
                questions.add(question);
            }
            return new VerifiedPack(version, keyId, publishedAt, List.copyOf(questions), payload);
        } catch (QuizPackException exception) {
            throw exception;
        } catch (JSONException | IllegalArgumentException exception) {
            throw new QuizPackException("Quiz payload is invalid", exception);
        }
    }

    private static QuizQuestion parseQuestion(JSONObject item) throws JSONException {
        String typeName = item.optString("type", "single_choice").trim().toLowerCase(java.util.Locale.ROOT);
        Set<String> allowed;
        QuizQuestion.Type type;
        switch (typeName) {
            case "single_choice":
                type = QuizQuestion.Type.SINGLE_CHOICE;
                allowed = SINGLE_KEYS;
                break;
            case "multiple_choice":
                type = QuizQuestion.Type.MULTIPLE_CHOICE;
                allowed = MULTIPLE_KEYS;
                break;
            case "true_false":
                type = QuizQuestion.Type.TRUE_FALSE;
                allowed = BOOLEAN_KEYS;
                break;
            case "short_answer":
                type = QuizQuestion.Type.SHORT_ANSWER;
                allowed = SHORT_KEYS;
                break;
            default:
                throw new QuizPackException("Unsupported question type: " + typeName);
        }
        requireOnly(item, allowed, "question");

        String id = item.getString("id");
        String prompt = item.getString("prompt");
        Set<String> tags = new LinkedHashSet<>();
        JSONArray tagSource = item.optJSONArray("tags");
        if (tagSource != null) {
            for (int index = 0; index < tagSource.length(); index++) {
                tags.add(tagSource.getString(index));
            }
        }
        QuizQuestion.Metadata metadata = new QuizQuestion.Metadata(
                item.optString("subject", ""), item.optInt("grade", 0),
                item.optString("difficulty", ""), item.optString("language", ""), tags);

        QuizQuestion question;
        switch (type) {
            case SINGLE_CHOICE:
                question = QuizQuestion.singleChoice(id, prompt, options(item),
                        item.getInt("answer_index"), metadata);
                break;
            case MULTIPLE_CHOICE:
                JSONArray indexes = item.getJSONArray("answer_indices");
                Set<Integer> answers = new LinkedHashSet<>();
                for (int index = 0; index < indexes.length(); index++) {
                    answers.add(indexes.getInt(index));
                }
                question = QuizQuestion.multipleChoice(id, prompt, options(item), answers, metadata);
                break;
            case TRUE_FALSE:
                question = QuizQuestion.trueFalse(id, prompt, item.getBoolean("answer"), metadata);
                break;
            case SHORT_ANSWER:
                JSONArray accepted = item.getJSONArray("accepted_answers");
                List<String> answersText = new ArrayList<>();
                for (int index = 0; index < accepted.length(); index++) {
                    answersText.add(accepted.getString(index));
                }
                question = QuizQuestion.shortAnswer(id, prompt, answersText, metadata);
                break;
            default:
                throw new QuizPackException("Unsupported question type");
        }

        String image = item.optString("image_base64", "").trim();
        String mime = item.optString("image_mime", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (image.isEmpty() != mime.isEmpty()) {
            throw new QuizPackException("Question image_base64 and image_mime must be supplied together");
        }
        if (!image.isEmpty()) {
            byte[] data;
            try {
                data = Base64.getDecoder().decode(image);
            } catch (IllegalArgumentException exception) {
                throw new QuizPackException("Question image is not valid base64", exception);
            }
            try {
                question = question.withImage(data, mime);
            } catch (IllegalArgumentException exception) {
                throw new QuizPackException("Question image is invalid", exception);
            }
        }
        return question;
    }

    private static List<String> options(JSONObject item) throws JSONException {
        JSONArray optionSource = item.getJSONArray("options");
        if (optionSource.length() < 2 || optionSource.length() > 8) {
            throw new QuizPackException("Each choice question needs 2 to 8 options");
        }
        List<String> options = new ArrayList<>();
        for (int option = 0; option < optionSource.length(); option++) {
            options.add(optionSource.getString(option));
        }
        return options;
    }

    private static void requireOnly(JSONObject item, Set<String> allowed, String label)
            throws JSONException {
        java.util.Iterator<String> keys = item.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) {
                throw new QuizPackException("Unknown field in " + label + ": " + key);
            }
        }
    }

    private static Set<String> keys(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static Set<String> union(Set<String> source, String... values) {
        Set<String> result = new HashSet<>(source);
        result.addAll(Arrays.asList(values));
        return result;
    }

    private static String fingerprint(byte[] der) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record VerifiedPack(long version, String keyId, String publishedAt,
            List<QuizQuestion> questions, String payload) {}

    public static final class QuizPackException extends IllegalArgumentException {
        public QuizPackException(String message) { super(message); }
        public QuizPackException(String message, Throwable cause) { super(message, cause); }
    }
}
