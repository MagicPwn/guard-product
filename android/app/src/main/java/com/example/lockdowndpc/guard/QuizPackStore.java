package com.example.lockdowndpc.guard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class QuizPackStore {
    private static final String PREFS = "guard_quiz_packs";
    private static final String KEY_TRUST = "trusted_public_key";
    private static final String KEY_TRUST_SET = "trusted_public_keys";
    private static final String KEY_ACTIVE = "active_envelope";
    private static final String KEY_ACTIVE_DELTAS = "active_delta_envelopes";
    private static final String KEY_PREVIOUS = "previous_envelope";
    private static final String KEY_PREVIOUS_DELTAS = "previous_delta_envelopes";
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final List<QuizQuestion> BUILT_IN = Collections.unmodifiableList(Arrays.asList(
            QuizQuestion.singleChoice("arithmetic-01", "7 + 5 = ?",
                    Arrays.asList("10", "11", "12", "13"), 2, mathMetadata(3, "easy")),
            QuizQuestion.singleChoice("arithmetic-02", "9 x 6 = ?",
                    Arrays.asList("45", "48", "54", "63"), 2, mathMetadata(3, "easy")),
            QuizQuestion.singleChoice("arithmetic-03", "48 / 8 = ?",
                    Arrays.asList("4", "5", "6", "8"), 2, mathMetadata(3, "easy")),
            QuizQuestion.singleChoice("arithmetic-04", "100 - 37 = ?",
                    Arrays.asList("53", "63", "67", "73"), 1, mathMetadata(3, "medium")),
            QuizQuestion.singleChoice("arithmetic-05", "Which fraction equals 0.5?",
                    Arrays.asList("1/4", "1/3", "1/2", "3/4"), 2, mathMetadata(4, "medium")),
            QuizQuestion.singleChoice("arithmetic-06", "3 x (4 + 2) = ?",
                    Arrays.asList("14", "16", "18", "20"), 2, mathMetadata(4, "medium"))
    ));

    private QuizPackStore() {}

    public static synchronized String installTrustAnchor(Context context, String publicKeyBase64) {
        PublicKey key = QuizPackVerifier.parsePublicKey(publicKeyBase64);
        SharedPreferences preferences = prefs(context);
        String encoded = QuizTrustSet.add(preferences.getString(KEY_TRUST_SET, null),
                publicKeyBase64);
        if (!preferences.edit().putString(KEY_TRUST_SET, encoded).remove(KEY_TRUST).commit()) {
            throw new IllegalStateException("Cannot persist quiz trust anchor");
        }
        return QuizPackVerifier.keyId(key);
    }

    public static synchronized List<String> trustedKeys(Context context) {
        SharedPreferences preferences = prefs(context);
        String encoded = preferences.getString(KEY_TRUST_SET, null);
        String legacy = preferences.getString(KEY_TRUST, null);
        if (legacy != null && !legacy.trim().isEmpty()) {
            encoded = QuizTrustSet.mergeLegacy(encoded, legacy);
            if (!preferences.edit().putString(KEY_TRUST_SET, encoded).remove(KEY_TRUST).commit()) {
                throw new IllegalStateException("Cannot migrate quiz trust anchor");
            }
        }
        return QuizTrustSet.parse(encoded);
    }

    public static boolean isTrustedKeyId(Context context, String keyId) {
        return QuizPackVerifier.isKeyTrusted(trustedKeys(context), keyId);
    }

    public static synchronized boolean resetTrustAndPacks(Context context) {
        return prefs(context).edit().remove(KEY_TRUST_SET).remove(KEY_TRUST)
                .remove(KEY_ACTIVE).remove(KEY_ACTIVE_DELTAS)
                .remove(KEY_PREVIOUS).remove(KEY_PREVIOUS_DELTAS).commit();
    }

    public static ImportResult importSigned(Context context, String envelopeJson) {
        List<String> trusted = trustedKeys(context);
        if (trusted.isEmpty()) {
            return ImportResult.failure(Status.NO_TRUST_ANCHOR, "Install a quiz trust key first");
        }
        QuizPackVerifier.VerifiedPack verified;
        try {
            verified = QuizPackVerifier.verifyAny(envelopeJson, trusted);
        } catch (QuizPackVerifier.QuizPackException exception) {
            return ImportResult.failure(Status.INVALID, exception.getMessage());
        }
        QuizPackVerifier.VerifiedPack active = activePack(context);
        if (active != null && verified.version() <= active.version()) {
            return ImportResult.failure(Status.NOT_NEWER,
                    "Pack version " + verified.version() + " is not newer than " + active.version());
        }
        synchronized (QuizPackStore.class) {
            SharedPreferences preferences = prefs(context);
            List<String> activeDeltas = readEnvelopes(preferences, KEY_ACTIVE_DELTAS);
            SharedPreferences.Editor editor = preferences.edit();
            String current = preferences.getString(KEY_ACTIVE, null);
            if (current != null) {
                editor.putString(KEY_PREVIOUS, current);
                editor.putString(KEY_PREVIOUS_DELTAS, encodeEnvelopes(activeDeltas));
            } else {
                editor.remove(KEY_PREVIOUS).remove(KEY_PREVIOUS_DELTAS);
            }
            editor.putString(KEY_ACTIVE, envelopeJson).remove(KEY_ACTIVE_DELTAS);
            if (!editor.commit()) return ImportResult.failure(Status.IO_ERROR, "Cannot persist quiz pack");
        }
        return ImportResult.success(verified);
    }

    /**
     * Applies one signed delta to the current effective pack. The full base
     * envelope stays untouched and deltas are retained in order, so successive
     * delta-only syncs remain verifiable after process restarts.
     */
    public static synchronized ImportResult importDelta(Context context, String deltaEnvelopeJson) {
        List<String> trusted = trustedKeys(context);
        if (trusted.isEmpty()) {
            return ImportResult.failure(Status.NO_TRUST_ANCHOR, "Install a quiz trust key first");
        }
        QuizPackVerifier.VerifiedPack active = activePack(context);
        if (active == null) {
            return ImportResult.failure(Status.NO_ACTIVE, "A full quiz pack is required before a delta");
        }
        QuizPackVerifier.VerifiedPack merged;
        try {
            merged = QuizPackVerifier.verifyDelta(deltaEnvelopeJson, active, trusted);
        } catch (QuizPackVerifier.QuizPackException exception) {
            return ImportResult.failure(Status.INVALID, exception.getMessage());
        }
        if (merged.version() <= active.version()) {
            return ImportResult.failure(Status.NOT_NEWER,
                    "Pack version " + merged.version() + " is not newer than " + active.version());
        }
        synchronized (QuizPackStore.class) {
            SharedPreferences preferences = prefs(context);
            String current = preferences.getString(KEY_ACTIVE, null);
            if (current == null) {
                return ImportResult.failure(Status.NO_ACTIVE, "A full quiz pack is required before a delta");
            }
            List<String> activeDeltas = readEnvelopes(preferences, KEY_ACTIVE_DELTAS);
            List<String> nextDeltas = new ArrayList<>(activeDeltas);
            nextDeltas.add(deltaEnvelopeJson);
            SharedPreferences.Editor editor = preferences.edit()
                    .putString(KEY_PREVIOUS, current)
                    .putString(KEY_PREVIOUS_DELTAS, encodeEnvelopes(activeDeltas))
                    .putString(KEY_ACTIVE_DELTAS, encodeEnvelopes(nextDeltas));
            if (!editor.commit()) {
                return ImportResult.failure(Status.IO_ERROR, "Cannot persist quiz delta");
            }
        }
        return ImportResult.success(merged);
    }

    /** Applies and persists a single old-key-signed transition. */
    public static synchronized String importKeyTransition(Context context, String transitionJson) {
        SharedPreferences preferences = prefs(context);
        String encoded = encodedTrustSet(preferences);
        String next = QuizTrustSet.applyTransition(encoded, transitionJson);
        if (!next.equals(encoded) && !preferences.edit().putString(KEY_TRUST_SET, next).commit()) {
            throw new IllegalStateException("Cannot persist quiz key transition");
        }
        return next;
    }

    /**
     * Applies the server's ordered transition history. Revocations are only
     * enforced when they cannot strand the active pack or remove the last key.
     */
    public static synchronized TransitionResult applyKeyTransitions(Context context,
            List<GuardRemoteClient.QuizKeyTransitionInfo> transitions) {
        SharedPreferences preferences = prefs(context);
        String encoded = encodedTrustSet(preferences);
        String original = encoded;
        int applied = 0;
        int revoked = 0;
        int ignored = 0;
        QuizPackVerifier.VerifiedPack active = activePack(context);
        String activeKeyId = active == null ? null : active.keyId();
        for (GuardRemoteClient.QuizKeyTransitionInfo transition : transitions == null
                ? Collections.<GuardRemoteClient.QuizKeyTransitionInfo>emptyList() : transitions) {
            if (transition == null) continue;
            boolean isRevoked = transition.revokedAt != null && !transition.revokedAt.isEmpty();
            if (!isRevoked) {
                String next = QuizTrustSet.applyTransition(encoded, transition.transition);
                if (!next.equals(encoded)) applied += 1;
                encoded = next;
                continue;
            }
            if (activeKeyId != null && activeKeyId.equalsIgnoreCase(transition.newKeyId)) {
                ignored += 1;
                continue;
            }
            try {
                String next = QuizTrustSet.revoke(encoded, transition.newKeyId);
                if (!next.equals(encoded)) revoked += 1;
                encoded = next;
            } catch (QuizPackVerifier.QuizPackException exception) {
                ignored += 1;
            }
        }
        if (!encoded.equals(original)
                && !preferences.edit().putString(KEY_TRUST_SET, encoded).commit()) {
            throw new IllegalStateException("Cannot persist quiz key transitions");
        }
        return new TransitionResult(applied, revoked, ignored, !encoded.equals(original));
    }

    public static synchronized boolean rollback(Context context) {
        SharedPreferences preferences = prefs(context);
        String previous = preferences.getString(KEY_PREVIOUS, null);
        if (previous == null) return false;
        List<String> trusted = trustedKeys(context);
        if (trusted.isEmpty()) return false;
        List<String> previousDeltas = readEnvelopes(preferences, KEY_PREVIOUS_DELTAS);
        QuizPackVerifier.VerifiedPack restored;
        try {
            restored = QuizPackVerifier.verifyAny(previous, trusted);
            for (String delta : previousDeltas) {
                restored = QuizPackVerifier.verifyDelta(delta, restored, trusted);
            }
        } catch (QuizPackVerifier.QuizPackException exception) {
            return false;
        }
        String active = preferences.getString(KEY_ACTIVE, null);
        List<String> activeDeltas = readEnvelopes(preferences, KEY_ACTIVE_DELTAS);
        SharedPreferences.Editor editor = preferences.edit()
                .putString(KEY_ACTIVE, previous)
                .putString(KEY_ACTIVE_DELTAS, encodeEnvelopes(previousDeltas));
        if (active == null) editor.remove(KEY_PREVIOUS);
        else {
            editor.putString(KEY_PREVIOUS, active)
                    .putString(KEY_PREVIOUS_DELTAS, encodeEnvelopes(activeDeltas));
        }
        return editor.commit();
    }

    public static synchronized QuizPackVerifier.VerifiedPack activePack(Context context) {
        List<String> trusted = trustedKeys(context);
        SharedPreferences preferences = prefs(context);
        String active = preferences.getString(KEY_ACTIVE, null);
        if (trusted.isEmpty() || active == null) return null;
        QuizPackVerifier.VerifiedPack current;
        try {
            current = QuizPackVerifier.verifyAny(active, trusted);
        } catch (QuizPackVerifier.QuizPackException exception) {
            return null;
        }
        try {
            for (String delta : readEnvelopes(preferences, KEY_ACTIVE_DELTAS)) {
                current = QuizPackVerifier.verifyDelta(delta, current, trusted);
            }
            return current;
        } catch (QuizPackVerifier.QuizPackException exception) {
            // A bad or out-of-order delta must never hide the still-valid full
            // pack. The next sync will request a full replacement from the server.
            return current;
        }
    }

    public static long activeVersion(Context context) {
        QuizPackVerifier.VerifiedPack active = activePack(context);
        return active == null ? 0L : active.version();
    }

    public static boolean canRollback(Context context) {
        return prefs(context).getString(KEY_PREVIOUS, null) != null;
    }

    public static QuizQuestion questionFor(Context context, String packageName) {
        List<QuizQuestion> questions = questionsFor(context, GuardPolicy.QuizSettings.defaults());
        return questions.isEmpty() ? null : questions.get(0);
    }

    /** Selects a distinct, filtered set for one attempt. The caller validates count at runtime. */
    public static List<QuizQuestion> questionsFor(Context context, GuardPolicy policy) {
        return questionsFor(context, policy.quizSettings());
    }

    static List<QuizQuestion> questionsFor(Context context, GuardPolicy.QuizSettings settings) {
        QuizPackVerifier.VerifiedPack active = activePack(context);
        List<QuizQuestion> source = active == null ? BUILT_IN : active.questions();
        List<QuizQuestion> matching = new ArrayList<>();
        for (QuizQuestion question : source) {
            if (settings.matches(question)) matching.add(question);
        }
        Collections.shuffle(matching, RANDOM);
        int count = Math.min(settings.questionCount(), matching.size());
        return Collections.unmodifiableList(new ArrayList<>(matching.subList(0, count)));
    }

    static List<QuizQuestion> matchingQuestions(List<QuizQuestion> source,
            GuardPolicy.QuizSettings settings) {
        List<QuizQuestion> matching = new ArrayList<>();
        for (QuizQuestion question : source) {
            if (settings.matches(question)) matching.add(question);
        }
        return Collections.unmodifiableList(matching);
    }

    private static QuizQuestion.Metadata mathMetadata(int grade, String difficulty) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add("math");
        tags.add("arithmetic");
        return new QuizQuestion.Metadata("mathematics", grade, difficulty, "en", tags);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String encodedTrustSet(SharedPreferences preferences) {
        String encoded = preferences.getString(KEY_TRUST_SET, null);
        String legacy = preferences.getString(KEY_TRUST, null);
        if (legacy != null && !legacy.trim().isEmpty()) {
            encoded = QuizTrustSet.mergeLegacy(encoded, legacy);
            if (!preferences.edit().putString(KEY_TRUST_SET, encoded).remove(KEY_TRUST).commit()) {
                throw new IllegalStateException("Cannot migrate quiz trust anchor");
            }
        }
        return QuizTrustSet.encode(QuizTrustSet.parse(encoded));
    }

    private static List<String> readEnvelopes(SharedPreferences preferences, String key) {
        String source = preferences.getString(key, null);
        if (source == null || source.trim().isEmpty()) return Collections.emptyList();
        try {
            JSONArray array = new JSONArray(source);
            if (array.length() > 64) {
                throw new QuizPackVerifier.QuizPackException("Too many queued quiz deltas");
            }
            List<String> values = new ArrayList<>(array.length());
            for (int index = 0; index < array.length(); index++) {
                String value = array.getString(index);
                if (value.isEmpty() || value.length() > 8_000_000) {
                    throw new QuizPackVerifier.QuizPackException("Queued quiz delta is invalid");
                }
                values.add(value);
            }
            return Collections.unmodifiableList(values);
        } catch (JSONException exception) {
            throw new QuizPackVerifier.QuizPackException("Queued quiz deltas are not valid JSON", exception);
        }
    }

    private static String encodeEnvelopes(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array.toString();
    }

    public enum Status { IMPORTED, NO_TRUST_ANCHOR, NO_ACTIVE, INVALID, NOT_NEWER, IO_ERROR }

    public record TransitionResult(int applied, int revoked, int ignored, boolean changed) {}

    public record ImportResult(Status status, QuizPackVerifier.VerifiedPack pack, String message) {
        static ImportResult success(QuizPackVerifier.VerifiedPack pack) {
            return new ImportResult(Status.IMPORTED, pack, "Imported version " + pack.version());
        }
        static ImportResult failure(Status status, String message) {
            return new ImportResult(status, null, message);
        }
        public boolean imported() { return status == Status.IMPORTED; }
    }
}
