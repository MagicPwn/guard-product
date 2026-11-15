package com.example.lockdowndpc.guard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds the minimized quiz-result body; prompts and answers never leave the device. */
public final class QuizResultPayload {
    private QuizResultPayload() {}

    public static JSONObject create(String attemptId, long occurredAt, String targetPackage,
            String triggerId, long policyRevision, long quizVersion, boolean rewardGranted,
            List<QuizQuestion> questions, List<Boolean> correctness) throws JSONException {
        if (attemptId == null || !attemptId.matches("^[A-Za-z0-9._:-]{8,128}$")) {
            throw new IllegalArgumentException("Attempt id is invalid");
        }
        GuardPolicy.requirePackage(targetPackage);
        if (questions == null || correctness == null || questions.isEmpty()
                || questions.size() != correctness.size() || questions.size() > 10) {
            throw new IllegalArgumentException("Quiz result needs 1 to 10 answered questions");
        }
        if (triggerId != null && !triggerId.matches("(quiz-trigger|quiz-manual)-[a-f0-9]{24}")) {
            throw new IllegalArgumentException("Quiz trigger id is invalid");
        }
        JSONArray items = new JSONArray();
        Set<String> ids = new HashSet<>();
        boolean everyCorrect = true;
        for (int index = 0; index < questions.size(); index++) {
            QuizQuestion question = questions.get(index);
            Boolean correct = correctness.get(index);
            if (question == null || correct == null || !ids.add(question.id())) {
                throw new IllegalArgumentException("Quiz questions are invalid");
            }
            everyCorrect = everyCorrect && correct;
            QuizQuestion.Metadata metadata = question.metadata();
            JSONArray tags = new JSONArray();
            for (String tag : metadata.tags()) tags.put(tag);
            items.put(new JSONObject()
                    .put("question_id", question.id())
                    .put("correct", correct)
                    .put("type", question.type().name().toLowerCase(Locale.ROOT))
                    .put("subject", metadata.subject())
                    .put("grade", metadata.grade())
                    .put("difficulty", metadata.difficulty())
                    .put("language", metadata.language())
                    .put("tags", tags));
        }
        if (rewardGranted && !everyCorrect) {
            throw new IllegalArgumentException("A rewarded attempt needs every answer correct");
        }
        return new JSONObject().put("attempt_id", attemptId)
                .put("occurred_at", occurredAt).put("target_package", targetPackage)
                .put("trigger_id", triggerId == null ? JSONObject.NULL : triggerId)
                .put("policy_revision", policyRevision).put("quiz_version", quizVersion)
                .put("reward_granted", rewardGranted).put("questions", items);
    }
}
