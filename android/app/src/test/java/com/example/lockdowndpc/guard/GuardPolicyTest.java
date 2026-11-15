package com.example.lockdowndpc.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GuardPolicyTest {
    private GuardPolicy policy(String zone, Map<String, Integer> budgets) {
        return new GuardPolicy(7, zone, true, false, true, 10, 8, 60,
                budgets, Collections.singleton("com.example.blocked"));
    }

    @Test
    public void jsonRoundTripPreservesAllPolicyFields() {
        GuardPolicy original = policy("Asia/Shanghai", Map.of("com.example.game", 60, "com.example.video", 30));
        GuardPolicy decoded = GuardPolicy.fromJson(original.toJson().toString());
        assertEquals(original.toJson().toString(), decoded.toJson().toString());
    }

    @Test
    public void policyCopiesInputCollections() {
        Map<String, Integer> budgets = new LinkedHashMap<>();
        budgets.put("com.example.game", 60);
        GuardPolicy policy = policy("UTC", budgets);
        budgets.clear();
        assertFalse(policy.dailyBudgets().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> policy.dailyBudgets().clear());
    }

    @Test
    public void invalidTimezoneCannotSilentlyBecomeGmt() {
        assertThrows(java.time.DateTimeException.class, () -> policy("Not/AZone", Collections.emptyMap()));
    }

    @Test
    public void negativeAndExcessiveBudgetsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> policy("UTC", Collections.singletonMap("com.example.game", -1)));
        assertThrows(IllegalArgumentException.class, () -> policy("UTC", Collections.singletonMap("com.example.game", 1441)));
    }

    @Test
    public void malformedPackageIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> policy("UTC", Collections.singletonMap("bad package", 60)));
    }

    @Test
    public void missingOrFutureSchemaIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GuardPolicy.fromJson("{}"));
        assertThrows(IllegalArgumentException.class, () -> GuardPolicy.fromJson("{\"schema\":2}"));
    }

    @Test
    public void fractionalBudgetsAreNotSilentlyRounded() throws Exception {
        org.json.JSONObject json = policy("UTC", Collections.singletonMap("com.example.game", 60)).toJson();
        json.getJSONObject("daily_budgets").put("com.example.game", 1.5);
        assertThrows(IllegalArgumentException.class, () -> GuardPolicy.fromJson(json.toString()));
    }

    @Test
    public void quizFiltersRoundTripAndMatchSignedMetadata() {
        Set<String> subjects = new LinkedHashSet<>(Collections.singleton("mathematics"));
        Set<Integer> grades = new LinkedHashSet<>(java.util.Arrays.asList(3, 4));
        Set<String> difficulties = new LinkedHashSet<>(Collections.singleton("medium"));
        Set<String> languages = new LinkedHashSet<>(Collections.singleton("en"));
        Set<String> tags = new LinkedHashSet<>(Collections.singleton("arithmetic"));
        GuardPolicy.QuizSettings settings = new GuardPolicy.QuizSettings(
                3, subjects, grades, difficulties, languages, tags);
        GuardPolicy original = new GuardPolicy(8, "UTC", true, false, true, 10, 8, 60,
                Map.of("com.example.game", 60), Collections.emptySet(), Collections.emptyMap(), settings);
        GuardPolicy decoded = GuardPolicy.fromJson(original.toJson().toString());
        assertEquals(3, decoded.quizSettings().questionCount());
        assertEquals(original.toJson().toString(), decoded.toJson().toString());

        QuizQuestion matching = QuizQuestion.trueFalse("q1", "2 + 2 = 4", true,
                new QuizQuestion.Metadata("Mathematics", 4, "medium", "en-US",
                        Set.of("arithmetic", "addition")));
        assertFalse(decoded.quizSettings().matches(matching));
        QuizQuestion exact = QuizQuestion.trueFalse("q2", "2 + 2 = 4", true,
                new QuizQuestion.Metadata("mathematics", 4, "medium", "en",
                        Set.of("arithmetic")));
        assertEquals(true, decoded.quizSettings().matches(exact));
    }

    @Test
    public void invalidQuizFilterLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GuardPolicy.QuizSettings(
                0, Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet(), Collections.emptySet()));
        assertThrows(IllegalArgumentException.class, () -> new GuardPolicy.QuizSettings(
                11, Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet(), Collections.emptySet()));
        assertThrows(IllegalArgumentException.class, () -> new GuardPolicy.QuizSettings(
                1, Collections.emptySet(), Collections.singleton(13), Collections.emptySet(),
                Collections.emptySet(), Collections.emptySet()));
        assertThrows(IllegalArgumentException.class, () -> new GuardPolicy.QuizSettings(
                1, Collections.emptySet(), Collections.emptySet(), Collections.singleton("extreme"),
                Collections.emptySet(), Collections.emptySet()));
    }

    @Test
    public void manualQuizTargetsMustBeManagedAndCannotBeAlwaysBlocked() {
        GuardPolicy policy = policy("UTC", Map.of("com.example.game", 60));
        assertEquals(true, policy.managesPackage("com.example.game"));
        assertFalse(policy.managesPackage("com.example.unmanaged"));
        assertFalse(policy.managesPackage("com.example.blocked"));
        assertFalse(policy.managesPackage(null));
    }

    @Test
    public void alwaysAllowedPackagesRoundTripAndBypassManagement() {
        GuardPolicy policy = new GuardPolicy(12, "UTC", true, false, true, 10, 8, 60,
                Map.of("com.example.game", 60), Collections.emptySet(), Collections.emptyMap(),
                Collections.emptyList(), GuardPolicy.QuizSettings.defaults(), Collections.emptyList(),
                Set.of("com.example.phone"));
        GuardPolicy decoded = GuardPolicy.fromJson(policy.toJson().toString());
        assertTrue(decoded.isAlwaysAllowed("com.example.phone"));
        assertFalse(decoded.managesPackage("com.example.phone"));
        assertEquals(policy.toJson().toString(), decoded.toJson().toString());
    }

    @Test
    public void alwaysAllowedAndBlockedAreMutuallyExclusive() {
        assertThrows(IllegalArgumentException.class, () -> new GuardPolicy(
                13, "UTC", true, false, true, 10, 8, 60,
                Collections.emptyMap(), Set.of("com.example.phone"), Collections.emptyMap(),
                Collections.emptyList(), GuardPolicy.QuizSettings.defaults(), Collections.emptyList(),
                Set.of("com.example.phone")));
    }

    @Test
    public void usageReminderThresholdsRoundTripAndAreBounded() {
        GuardPolicy policy = new GuardPolicy(14, "UTC", true, false, true, 10, 8, 60,
                Map.of("com.example.game", 60), Collections.emptySet(), Collections.emptyMap(),
                Collections.emptyList(), GuardPolicy.QuizSettings.defaults(),
                Collections.emptyList(), Collections.emptySet(), 15, 30);
        GuardPolicy decoded = GuardPolicy.fromJson(policy.toJson().toString());
        assertEquals(15, decoded.budgetWarningMinutes());
        assertEquals(30, decoded.continuousUseReminderMinutes());
        assertEquals(policy.toJson().toString(), decoded.toJson().toString());

        assertThrows(IllegalArgumentException.class, () -> new GuardPolicy(
                15, "UTC", true, false, true, 10, 8, 60,
                Collections.emptyMap(), Collections.emptySet(), Collections.emptyMap(),
                Collections.emptyList(), GuardPolicy.QuizSettings.defaults(),
                Collections.emptyList(), Collections.emptySet(), -1, 30));
        assertThrows(IllegalArgumentException.class, () -> new GuardPolicy(
                16, "UTC", true, false, true, 10, 8, 60,
                Collections.emptyMap(), Collections.emptySet(), Collections.emptyMap(),
                Collections.emptyList(), GuardPolicy.QuizSettings.defaults(),
                Collections.emptyList(), Collections.emptySet(), 15, 1441));
    }

    @Test
    public void helpContactsRoundTripAndRemainOptional() {
        GuardPolicy withoutContacts = policy("UTC", Map.of("com.example.game", 60));
        assertEquals(0, withoutContacts.helpContacts().size());

        GuardPolicy withContacts = new GuardPolicy(9, "UTC", true, false, true, 10, 8, 60,
                Map.of("com.example.game", 60), Collections.emptySet(), Collections.emptyMap(),
                Collections.emptyList(), GuardPolicy.QuizSettings.defaults(),
                List.of(new GuardPolicy.HelpContact(" Mum ", "+86 138 0000 0000"),
                        new GuardPolicy.HelpContact("School", "110")));
        GuardPolicy decoded = GuardPolicy.fromJson(withContacts.toJson().toString());
        assertEquals(2, decoded.helpContacts().size());
        assertEquals("Mum", decoded.helpContacts().get(0).label());
        assertEquals("+86 138 0000 0000", decoded.helpContacts().get(0).phone());
        assertEquals(withContacts.toJson().toString(), decoded.toJson().toString());
    }

    @Test
    public void invalidHelpContactsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new GuardPolicy.HelpContact("", "110"));
        assertThrows(IllegalArgumentException.class,
                () -> new GuardPolicy.HelpContact("Parent", "call-me"));
        assertThrows(IllegalArgumentException.class,
                () -> new GuardPolicy.HelpContact("Parent", "1"));
        assertThrows(IllegalArgumentException.class, () -> new GuardPolicy(1, "UTC", true,
                false, true, 10, 8, 60, Collections.emptyMap(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyList(),
                GuardPolicy.QuizSettings.defaults(), List.of(
                        new GuardPolicy.HelpContact("A", "110"),
                        new GuardPolicy.HelpContact("A", "110"))));
    }
}
