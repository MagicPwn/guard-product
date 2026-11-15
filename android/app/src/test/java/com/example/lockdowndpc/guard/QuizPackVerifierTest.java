package com.example.lockdowndpc.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.LinkedHashSet;
import java.util.Base64;
import java.util.Arrays;
import java.util.List;

public final class QuizPackVerifierTest {
    private static KeyPair trusted;
    private static KeyPair attacker;

    @BeforeClass
    public static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        trusted = generator.generateKeyPair();
        attacker = generator.generateKeyPair();
    }

    private static String publicKey(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private static String payload(long version) throws Exception {
        JSONObject question = new JSONObject()
                .put("id", "q-" + version)
                .put("prompt", "2 + 2 = ?")
                .put("options", new JSONArray().put("3").put("4").put("5"))
                .put("answer_index", 1);
        return new JSONObject()
                .put("schema", 1)
                .put("version", version)
                .put("published_at", "2026-11-12T00:00:00Z")
                .put("questions", new JSONArray().put(question))
                .toString();
    }

    private static String envelope(String payload, KeyPair signer) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(signer.getPrivate());
        signature.update(payload.getBytes(StandardCharsets.UTF_8));
        return new JSONObject()
                .put("schema", 1)
                .put("key_id", QuizPackVerifier.keyId(signer.getPublic()))
                .put("payload", payload)
                .put("signature", Base64.getEncoder().encodeToString(signature.sign()))
                .toString();
    }

    private static JSONObject deltaQuestion(String id, String prompt) throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("prompt", prompt)
                .put("options", new JSONArray().put("A").put("B").put("C"))
                .put("answer_index", 2);
    }

    private static String deltaPayload(long version, long baseVersion, JSONArray operations)
            throws Exception {
        return new JSONObject()
                .put("schema", 2)
                .put("version", version)
                .put("base_version", baseVersion)
                .put("published_at", "2026-11-13T00:00:00Z")
                .put("operations", operations)
                .toString();
    }

    @Test
    public void validSignedPackIsAccepted() throws Exception {
        QuizPackVerifier.VerifiedPack pack = QuizPackVerifier.verify(
                envelope(payload(4), trusted), publicKey(trusted));
        assertEquals(4, pack.version());
        assertEquals(1, pack.questions().size());
        assertEquals("4", pack.questions().get(0).options().get(1));
        assertEquals(QuizPackVerifier.keyId(trusted.getPublic()), pack.keyId());
    }

    @Test
    public void packSignedByAnotherKeyIsRejectedEvenWithMatchingId() throws Exception {
        String signed = envelope(payload(4), attacker);
        JSONObject forged = new JSONObject(signed)
                .put("key_id", QuizPackVerifier.keyId(trusted.getPublic()));
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizPackVerifier.verify(forged.toString(), publicKey(trusted)));
    }

    @Test
    public void packSignedByASecondTrustedKeyIsAccepted() throws Exception {
        QuizPackVerifier.VerifiedPack pack = QuizPackVerifier.verifyAny(
                envelope(payload(5), attacker),
                Arrays.asList(publicKey(trusted), publicKey(attacker)));
        assertEquals(5, pack.version());
        assertEquals(QuizPackVerifier.keyId(attacker.getPublic()), pack.keyId());
    }

    @Test
    public void untrustedKeyIsRejectedWhenTrustSetHasOtherKeys() throws Exception {
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizPackVerifier.verifyAny(envelope(payload(5), attacker),
                        Arrays.asList(publicKey(trusted))));
    }

    @Test
    public void tamperedPayloadIsRejected() throws Exception {
        JSONObject json = new JSONObject(envelope(payload(4), trusted));
        json.put("payload", json.getString("payload").replace("2 + 2", "2 + 3"));
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizPackVerifier.verify(json.toString(), publicKey(trusted)));
    }

    @Test
    public void trustAnchorMustBeAnEcKey() {
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizPackVerifier.parsePublicKey("not-a-key"));
    }

    @Test
    public void duplicateQuestionIdsAreRejected() throws Exception {
        JSONObject question = new JSONObject()
                .put("id", "same")
                .put("prompt", "Pick one")
                .put("options", new JSONArray().put("A").put("B"))
                .put("answer_index", 0);
        String duplicated = new JSONObject()
                .put("schema", 1)
                .put("version", 5)
                .put("published_at", "now")
                .put("questions", new JSONArray().put(question).put(question))
                .toString();
        QuizPackVerifier.QuizPackException error = assertThrows(
                QuizPackVerifier.QuizPackException.class,
                () -> QuizPackVerifier.verify(envelope(duplicated, trusted), publicKey(trusted)));
        assertTrue(error.getMessage().contains("Duplicate"));
    }

    @Test
    public void emptyPackAndOutOfRangeVersionAreRejected() throws Exception {
        String empty = new JSONObject().put("schema", 1).put("version", 1)
                .put("published_at", "now").put("questions", new JSONArray()).toString();
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizPackVerifier.verify(envelope(empty, trusted), publicKey(trusted)));
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizPackVerifier.verify(envelope(payload(0), trusted), publicKey(trusted)));
    }

    @Test
    public void unsafeQuestionOptionsAreRejected() throws Exception {
        String invalid = new JSONObject().put("schema", 1).put("version", 2)
                .put("published_at", "now").put("questions", new JSONArray().put(
                        new JSONObject().put("id", "q").put("prompt", "Prompt")
                                .put("options", new JSONArray().put("only one"))
                                .put("answer_index", 0))).toString();
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizPackVerifier.verify(envelope(invalid, trusted), publicKey(trusted)));
    }

    @Test
    public void mixedQuestionTypesAndMetadataAreAccepted() throws Exception {
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        JSONObject single = new JSONObject()
                .put("type", "single_choice").put("id", "single")
                .put("prompt", "Pick one").put("options", new JSONArray().put("A").put("B"))
                .put("answer_index", 1).put("subject", "science").put("grade", 5)
                .put("difficulty", "easy").put("language", "en")
                .put("tags", new JSONArray().put("matter"));
        JSONObject multiple = new JSONObject()
                .put("type", "multiple_choice").put("id", "multiple")
                .put("prompt", "Pick two").put("options", new JSONArray().put("A").put("B").put("C"))
                .put("answer_indices", new JSONArray().put(0).put(2));
        JSONObject trueFalse = new JSONObject()
                .put("type", "true_false").put("id", "boolean")
                .put("prompt", "Water freezes").put("answer", true);
        JSONObject shortAnswer = new JSONObject()
                .put("type", "short_answer").put("id", "short")
                .put("prompt", "Name the gas").put("accepted_answers",
                        new JSONArray().put("oxygen").put("O2"));
        JSONObject image = new JSONObject()
                .put("type", "true_false").put("id", "image")
                .put("prompt", "Read the diagram").put("answer", false)
                .put("image_base64", Base64.getEncoder().encodeToString(jpeg))
                .put("image_mime", "image/jpeg");
        String payload = new JSONObject().put("schema", 1).put("version", 12)
                .put("published_at", "2026-11-12T00:00:00Z")
                .put("questions", new JSONArray().put(single).put(multiple)
                        .put(trueFalse).put(shortAnswer).put(image)).toString();
        QuizPackVerifier.VerifiedPack pack = QuizPackVerifier.verify(
                envelope(payload, trusted), publicKey(trusted));
        assertEquals(5, pack.questions().size());
        assertEquals(QuizQuestion.Type.SINGLE_CHOICE, pack.questions().get(0).type());
        assertEquals(QuizQuestion.Type.MULTIPLE_CHOICE, pack.questions().get(1).type());
        assertEquals(new LinkedHashSet<>(java.util.Arrays.asList(0, 2)),
                pack.questions().get(1).answerIndices());
        assertTrue(pack.questions().get(2).acceptsBoolean(true));
        assertTrue(pack.questions().get(3).acceptsText(" o2 "));
        assertTrue(pack.questions().get(4).hasImage());
        assertEquals("science", pack.questions().get(0).metadata().subject());
    }

    @Test
    public void ambiguousMultipleChoiceAndUnknownFieldsAreRejected() throws Exception {
        JSONObject allCorrect = new JSONObject()
                .put("type", "multiple_choice").put("id", "all")
                .put("prompt", "All").put("options", new JSONArray().put("A").put("B"))
                .put("answer_indices", new JSONArray().put(0).put(1));
        String invalid = new JSONObject().put("schema", 1).put("version", 3)
                .put("published_at", "now").put("questions", new JSONArray().put(allCorrect)).toString();
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizPackVerifier.verify(envelope(invalid, trusted), publicKey(trusted)));

        JSONObject unknown = new JSONObject()
                .put("type", "true_false").put("id", "unknown").put("prompt", "Prompt")
                .put("answer", true).put("answer_index", 0);
        String withUnknown = new JSONObject().put("schema", 1).put("version", 4)
                .put("published_at", "now").put("questions", new JSONArray().put(unknown)).toString();
        QuizPackVerifier.QuizPackException error = assertThrows(
                QuizPackVerifier.QuizPackException.class,
                () -> QuizPackVerifier.verify(envelope(withUnknown, trusted), publicKey(trusted)));
        assertTrue(error.getMessage().contains("Unknown field"));
    }

    @Test
    public void invalidEmbeddedImageIsRejected() throws Exception {
        JSONObject question = new JSONObject()
                .put("type", "true_false").put("id", "image").put("prompt", "Image")
                .put("answer", true)
                .put("image_base64", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4}))
                .put("image_mime", "image/jpeg");
        String payload = new JSONObject().put("schema", 1).put("version", 7)
                .put("published_at", "now").put("questions", new JSONArray().put(question)).toString();
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizPackVerifier.verify(envelope(payload, trusted), publicKey(trusted)));
    }

    @Test
    public void signedDeltaMergesPutsAndRemovesAgainstTheExactBase() throws Exception {
        QuizPackVerifier.VerifiedPack base = QuizPackVerifier.verify(
                envelope(payload(4), trusted), publicKey(trusted));
        JSONArray operations = new JSONArray()
                .put(new JSONObject().put("op", "put")
                        .put("question", deltaQuestion("q-5", "5 + 5 = ?")))
                .put(new JSONObject().put("op", "remove").put("id", "q-4"));
        QuizPackVerifier.VerifiedPack merged = QuizPackVerifier.verifyDelta(
                envelope(deltaPayload(5, 4, operations), trusted), base,
                List.of(publicKey(trusted)));
        assertEquals(5, merged.version());
        assertEquals(1, merged.questions().size());
        assertEquals("q-5", merged.questions().get(0).id());
        assertEquals("5 + 5 = ?", merged.questions().get(0).prompt());
    }

    @Test
    public void deltaRejectsWrongBaseVersion() throws Exception {
        QuizPackVerifier.VerifiedPack base = QuizPackVerifier.verify(
                envelope(payload(4), trusted), publicKey(trusted));
        JSONArray operations = new JSONArray().put(new JSONObject().put("op", "put")
                .put("question", deltaQuestion("q-5", "new")));
        QuizPackVerifier.QuizPackException error = assertThrows(
                QuizPackVerifier.QuizPackException.class,
                () -> QuizPackVerifier.verifyDelta(
                        envelope(deltaPayload(5, 3, operations), trusted), base,
                        List.of(publicKey(trusted))));
        assertTrue(error.getMessage().contains("base version"));
    }

    @Test
    public void deltaRejectsTamperingAndMissingRemovals() throws Exception {
        QuizPackVerifier.VerifiedPack base = QuizPackVerifier.verify(
                envelope(payload(4), trusted), publicKey(trusted));
        JSONArray missing = new JSONArray().put(new JSONObject()
                .put("op", "remove").put("id", "does-not-exist"));
        String signed = envelope(deltaPayload(5, 4, missing), trusted);
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizPackVerifier.verifyDelta(signed, base, List.of(publicKey(trusted))));

        JSONObject tampered = new JSONObject(signed)
                .put("payload", new JSONObject(signed).getString("payload")
                        .replace("does-not-exist", "q-4"));
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizPackVerifier.verifyDelta(tampered.toString(), base,
                        List.of(publicKey(trusted))));
    }

    @Test
    public void deltaMustUseTheBaseSigningKey() throws Exception {
        QuizPackVerifier.VerifiedPack base = QuizPackVerifier.verify(
                envelope(payload(4), trusted), publicKey(trusted));
        JSONArray operations = new JSONArray().put(new JSONObject().put("op", "put")
                .put("question", deltaQuestion("q-5", "new")));
        String signedByAttacker = envelope(deltaPayload(5, 4, operations), attacker);
        assertThrows(QuizPackVerifier.QuizPackException.class,
                () -> QuizPackVerifier.verifyDelta(signedByAttacker, base,
                        Arrays.asList(publicKey(trusted), publicKey(attacker))));
    }
}
