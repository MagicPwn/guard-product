package com.example.lockdowndpc.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.Set;

public final class QuizResultPayloadTest {
    @Test
    public void minimizedPayloadExcludesPromptOptionsAndAnswers() throws Exception {
        QuizQuestion question = QuizQuestion.singleChoice("q-1",
                "What is the private prompt?", List.of("secret option", "other"),
                0, new QuizQuestion.Metadata("Math", 4, "easy", "en", Set.of("Fractions")));
        JSONObject result = QuizResultPayload.create("attempt-12345678", 1234L,
                "com.example.game", "quiz-trigger-0123456789abcdef01234567", 7L, 9L,
                true, List.of(question), List.of(true));

        assertEquals("attempt-12345678", result.getString("attempt_id"));
        assertEquals("com.example.game", result.getString("target_package"));
        assertEquals("quiz-trigger-0123456789abcdef01234567", result.getString("trigger_id"));
        assertTrue(result.getBoolean("reward_granted"));
        JSONObject item = result.getJSONArray("questions").getJSONObject(0);
        assertEquals("q-1", item.getString("question_id"));
        assertEquals("single_choice", item.getString("type"));
        assertEquals("math", item.getString("subject").toLowerCase());
        assertEquals(4, item.getInt("grade"));
        assertTrue(item.getBoolean("correct"));
        String serialized = result.toString();
        assertFalse(serialized.contains("private prompt"));
        assertFalse(serialized.contains("secret option"));
        assertFalse(item.has("options"));
        assertFalse(item.has("answer_index"));
    }

    @Test
    public void rewardedAttemptRejectsAnyWrongAnswer() {
        QuizQuestion first = QuizQuestion.trueFalse("q-1", "One", true,
                QuizQuestion.Metadata.EMPTY);
        QuizQuestion second = QuizQuestion.trueFalse("q-2", "Two", false,
                QuizQuestion.Metadata.EMPTY);
        assertThrows(IllegalArgumentException.class, () -> QuizResultPayload.create(
                "attempt-12345678", 1234L, "com.example.game", null, 7L, 9L,
                true, List.of(first, second), List.of(true, false)));
    }

    @Test
    public void parentTriggeredResultCarriesItsManualRequestId() throws Exception {
        QuizQuestion question = QuizQuestion.trueFalse("q-1", "One", true,
                QuizQuestion.Metadata.EMPTY);
        JSONObject result = QuizResultPayload.create("attempt-12345678", 1234L,
                "com.example.game", "quiz-manual-0123456789abcdef01234567", 7L, 9L,
                false, List.of(question), List.of(false));
        assertEquals("quiz-manual-0123456789abcdef01234567",
                result.getString("trigger_id"));
    }
}
