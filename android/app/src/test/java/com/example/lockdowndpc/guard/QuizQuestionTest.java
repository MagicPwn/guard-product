package com.example.lockdowndpc.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class QuizQuestionTest {
    @Test
    public void answerAndOptionsAreImmutable() {
        ArrayList<String> options = new ArrayList<>(Arrays.asList("10", "12"));
        QuizQuestion question = new QuizQuestion("addition", "7 + 5 = ?", options, 1);
        options.clear();
        assertEquals(2, question.options().size());
        assertEquals(1, question.answerIndex());
        assertThrows(UnsupportedOperationException.class, () -> question.options().clear());
    }

    @Test
    public void emptyAndAmbiguousQuestionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new QuizQuestion("", "Question", Arrays.asList("A", "B"), 0));
        assertThrows(IllegalArgumentException.class, () ->
                new QuizQuestion("id", "Question", Arrays.asList("A", "A"), 0));
        assertThrows(IllegalArgumentException.class, () ->
                new QuizQuestion("id", "Question", Arrays.asList("A", "B"), 2));
        assertThrows(IllegalArgumentException.class, () ->
                new QuizQuestion("id", "Question", Arrays.asList("A", " "), 0));
    }

    @Test
    public void multipleChoiceRequiresExactSelectionAndMeaningfulAnswers() {
        Set<Integer> answers = new LinkedHashSet<>(Arrays.asList(0, 2));
        QuizQuestion question = QuizQuestion.multipleChoice("multi", "Select both",
                Arrays.asList("A", "B", "C", "D"), answers, QuizQuestion.Metadata.EMPTY);
        assertTrue(question.acceptsSelection(new LinkedHashSet<>(Arrays.asList(0, 2))));
        assertTrue(question.acceptsSelection(new LinkedHashSet<>(Arrays.asList(2, 0))));
        assertFalse(question.acceptsSelection(Collections.singleton(0)));
        assertFalse(question.acceptsSelection(new LinkedHashSet<>(Arrays.asList(0, 1, 2))));
        assertThrows(IllegalArgumentException.class, () -> QuizQuestion.multipleChoice("all",
                "All correct", Arrays.asList("A", "B"), new LinkedHashSet<>(Arrays.asList(0, 1)),
                QuizQuestion.Metadata.EMPTY));
    }

    @Test
    public void trueFalseAndShortAnswerUseTheirOwnAnswerShapes() {
        QuizQuestion yesNo = QuizQuestion.trueFalse("tf", "The sky is blue", true,
                QuizQuestion.Metadata.EMPTY);
        assertTrue(yesNo.acceptsBoolean(true));
        assertFalse(yesNo.acceptsBoolean(false));
        assertFalse(yesNo.acceptsText("true"));

        QuizQuestion shortAnswer = QuizQuestion.shortAnswer("short", "Name the planet",
                Arrays.asList("Earth", "the Earth"), QuizQuestion.Metadata.EMPTY);
        assertTrue(shortAnswer.acceptsText(" earth "));
        assertTrue(shortAnswer.acceptsText("THE   EARTH"));
        assertFalse(shortAnswer.acceptsText("Mars"));
        assertFalse(shortAnswer.acceptsSelection(Collections.singleton(0)));
    }

    @Test
    public void embeddedImagesAreMagicCheckedAndCopied() {
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        QuizQuestion question = QuizQuestion.trueFalse("image", "Look at this", true,
                QuizQuestion.Metadata.EMPTY).withImage(jpeg, "image/jpeg");
        jpeg[0] = 0;
        assertTrue(question.hasImage());
        assertEquals((byte) 0xff, question.imageData()[0]);
        assertThrows(IllegalArgumentException.class, () -> QuizQuestion.trueFalse("bad",
                "Wrong MIME", true, QuizQuestion.Metadata.EMPTY)
                .withImage(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9}, "image/png"));
        assertThrows(IllegalArgumentException.class, () -> QuizQuestion.trueFalse("bad",
                "Not an image", true, QuizQuestion.Metadata.EMPTY)
                .withImage(new byte[]{1, 2, 3, 4}, "image/jpeg"));
    }

    @Test
    public void metadataIsNormalizedAndValidated() {
        QuizQuestion.Metadata metadata = new QuizQuestion.Metadata(
                " Mathematics ", 4, "MEDIUM", "en-US",
                new LinkedHashSet<>(Arrays.asList("Fractions", "Arithmetic")));
        assertEquals("Mathematics", metadata.subject());
        assertEquals("medium", metadata.difficulty());
        assertEquals("en-us", metadata.language());
        assertTrue(metadata.tags().contains("fractions"));
        assertThrows(IllegalArgumentException.class, () -> new QuizQuestion.Metadata(
                "Math", 13, "easy", "en", Collections.emptySet()));
        assertThrows(IllegalArgumentException.class, () -> new QuizQuestion.Metadata(
                "Math", 4, "extreme", "en", Collections.emptySet()));
    }
}
