package com.example.lockdowndpc.guard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class GuardPolicy {
    public static final int CURRENT_SCHEMA = 1;
    public static final int MAX_SCHEDULES_PER_PACKAGE = 21;
    public static final int MAX_QUIZ_TRIGGERS = 32;
    public static final int MAX_HELP_CONTACTS = 3;
    public static final int MAX_ALWAYS_ALLOWED = 500;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9][0-9 ()-]{0,23}");
    private final long revision;
    private final String timezone;
    private final boolean enabled;
    private final boolean launcherEnabled;
    private final boolean quizGateEnabled;
    private final int rewardMinutes;
    private final int maxAttemptsPerDay;
    private final int cooldownSeconds;
    private final int budgetWarningMinutes;
    private final int continuousUseReminderMinutes;
    private final Map<String, Integer> dailyBudgets;
    private final Set<String> blockedPackages;
    private final Set<String> alwaysAllowedPackages;
    private final Map<String, List<GuardSchedule>> schedules;
    private final QuizSettings quizSettings;
    private final List<QuizTrigger> quizTriggers;
    private final List<HelpContact> helpContacts;

    public GuardPolicy(long revision, String timezone, boolean enabled, boolean launcherEnabled,
            boolean quizGateEnabled, int rewardMinutes, int maxAttemptsPerDay, int cooldownSeconds,
            Map<String, Integer> budgets, Set<String> blocked) {
        this(revision, timezone, enabled, launcherEnabled, quizGateEnabled, rewardMinutes,
                maxAttemptsPerDay, cooldownSeconds, budgets, blocked, Collections.emptyMap());
    }

    public GuardPolicy(long revision, String timezone, boolean enabled, boolean launcherEnabled,
            boolean quizGateEnabled, int rewardMinutes, int maxAttemptsPerDay, int cooldownSeconds,
            Map<String, Integer> budgets, Set<String> blocked,
            Map<String, List<GuardSchedule>> scheduleSource) {
        this(revision, timezone, enabled, launcherEnabled, quizGateEnabled, rewardMinutes,
                maxAttemptsPerDay, cooldownSeconds, budgets, blocked, scheduleSource,
                QuizSettings.defaults());
    }

    public GuardPolicy(long revision, String timezone, boolean enabled, boolean launcherEnabled,
            boolean quizGateEnabled, int rewardMinutes, int maxAttemptsPerDay, int cooldownSeconds,
            Map<String, Integer> budgets, Set<String> blocked,
            Map<String, List<GuardSchedule>> scheduleSource, QuizSettings quizSettings) {
        this(revision, timezone, enabled, launcherEnabled, quizGateEnabled, rewardMinutes,
                maxAttemptsPerDay, cooldownSeconds, budgets, blocked, scheduleSource,
                Collections.emptyList(), quizSettings);
    }

    public GuardPolicy(long revision, String timezone, boolean enabled, boolean launcherEnabled,
            boolean quizGateEnabled, int rewardMinutes, int maxAttemptsPerDay, int cooldownSeconds,
            Map<String, Integer> budgets, Set<String> blocked,
            Map<String, List<GuardSchedule>> scheduleSource, List<QuizTrigger> triggerSource,
            QuizSettings quizSettings) {
        this(revision, timezone, enabled, launcherEnabled, quizGateEnabled, rewardMinutes,
                maxAttemptsPerDay, cooldownSeconds, budgets, blocked, scheduleSource,
                triggerSource, quizSettings, Collections.emptyList());
    }

    public GuardPolicy(long revision, String timezone, boolean enabled, boolean launcherEnabled,
            boolean quizGateEnabled, int rewardMinutes, int maxAttemptsPerDay, int cooldownSeconds,
            Map<String, Integer> budgets, Set<String> blocked,
            Map<String, List<GuardSchedule>> scheduleSource, List<QuizTrigger> triggerSource,
            QuizSettings quizSettings, List<HelpContact> contactSource) {
        this(revision, timezone, enabled, launcherEnabled, quizGateEnabled, rewardMinutes,
                maxAttemptsPerDay, cooldownSeconds, budgets, blocked, scheduleSource,
                triggerSource, quizSettings, contactSource, Collections.emptySet());
    }

    public GuardPolicy(long revision, String timezone, boolean enabled, boolean launcherEnabled,
            boolean quizGateEnabled, int rewardMinutes, int maxAttemptsPerDay, int cooldownSeconds,
            Map<String, Integer> budgets, Set<String> blocked,
            Map<String, List<GuardSchedule>> scheduleSource, List<QuizTrigger> triggerSource,
            QuizSettings quizSettings, List<HelpContact> contactSource,
            Set<String> alwaysAllowedSource) {
        this(revision, timezone, enabled, launcherEnabled, quizGateEnabled, rewardMinutes,
                maxAttemptsPerDay, cooldownSeconds, budgets, blocked, scheduleSource,
                triggerSource, quizSettings, contactSource, alwaysAllowedSource, 0, 0);
    }

    public GuardPolicy(long revision, String timezone, boolean enabled, boolean launcherEnabled,
            boolean quizGateEnabled, int rewardMinutes, int maxAttemptsPerDay, int cooldownSeconds,
            Map<String, Integer> budgets, Set<String> blocked,
            Map<String, List<GuardSchedule>> scheduleSource, List<QuizTrigger> triggerSource,
            QuizSettings quizSettings, List<HelpContact> contactSource,
            Set<String> alwaysAllowedSource, int budgetWarningMinutes,
            int continuousUseReminderMinutes) {
        if (revision < 0 || rewardMinutes < 0 || rewardMinutes > 1440
                || maxAttemptsPerDay < 0 || maxAttemptsPerDay > 1000
                || cooldownSeconds < 0 || cooldownSeconds > 86400
                || budgetWarningMinutes < 0 || budgetWarningMinutes > 1440
                || continuousUseReminderMinutes < 0 || continuousUseReminderMinutes > 1440) {
            throw new IllegalArgumentException("Invalid policy limits");
        }
        this.timezone = ZoneId.of(timezone == null ? "UTC" : timezone.trim()).getId();
        Map<String, Integer> checkedBudgets = new LinkedHashMap<>();
        if (budgets != null) {
            for (Map.Entry<String, Integer> entry : budgets.entrySet()) {
                requirePackage(entry.getKey());
                if (entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > 1440) {
                    throw new IllegalArgumentException("Daily minutes must be between 0 and 1440");
                }
                checkedBudgets.put(entry.getKey(), entry.getValue());
            }
        }
        Set<String> checkedBlocked = new LinkedHashSet<>();
        if (blocked != null) {
            for (String packageName : blocked) {
                requirePackage(packageName);
                checkedBlocked.add(packageName);
            }
        }
        Set<String> checkedAlwaysAllowed = new LinkedHashSet<>();
        if (alwaysAllowedSource != null) {
            if (alwaysAllowedSource.size() > MAX_ALWAYS_ALLOWED) {
                throw new IllegalArgumentException("At most 500 always-allowed packages are supported");
            }
            for (String packageName : alwaysAllowedSource) {
                requirePackage(packageName);
                if (checkedBlocked.contains(packageName)) {
                    throw new IllegalArgumentException(
                            "A package cannot be both always allowed and always blocked");
                }
                checkedAlwaysAllowed.add(packageName);
            }
        }
        Map<String, List<GuardSchedule>> checkedSchedules = new LinkedHashMap<>();
        if (scheduleSource != null) {
            for (Map.Entry<String, List<GuardSchedule>> entry : scheduleSource.entrySet()) {
                requirePackage(entry.getKey());
                List<GuardSchedule> windows = entry.getValue();
                if (windows == null || windows.isEmpty() || windows.size() > MAX_SCHEDULES_PER_PACKAGE) {
                    throw new IllegalArgumentException("A scheduled package needs 1 to 21 windows");
                }
                checkedSchedules.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(windows)));
            }
        }
        this.revision = revision;
        this.enabled = enabled;
        this.launcherEnabled = launcherEnabled;
        this.quizGateEnabled = quizGateEnabled;
        this.rewardMinutes = rewardMinutes;
        this.maxAttemptsPerDay = maxAttemptsPerDay;
        this.cooldownSeconds = cooldownSeconds;
        this.budgetWarningMinutes = budgetWarningMinutes;
        this.continuousUseReminderMinutes = continuousUseReminderMinutes;
        this.dailyBudgets = Collections.unmodifiableMap(checkedBudgets);
        this.blockedPackages = Collections.unmodifiableSet(checkedBlocked);
        this.alwaysAllowedPackages = Collections.unmodifiableSet(checkedAlwaysAllowed);
        this.schedules = Collections.unmodifiableMap(checkedSchedules);
        this.quizSettings = quizSettings == null ? QuizSettings.defaults() : quizSettings;
        List<QuizTrigger> checkedTriggers = new ArrayList<>();
        if (triggerSource != null) {
            if (triggerSource.size() > MAX_QUIZ_TRIGGERS) {
                throw new IllegalArgumentException("At most 32 quiz triggers are supported");
            }
            Set<String> triggerIds = new LinkedHashSet<>();
            for (QuizTrigger trigger : triggerSource) {
                if (trigger == null || !triggerIds.add(trigger.id())) {
                    throw new IllegalArgumentException("Quiz trigger ids must be unique");
                }
                if (!trigger.id().equals(trigger.computedId())) {
                    throw new IllegalArgumentException("Quiz trigger id does not match its content");
                }
                for (String packageName : trigger.packages()) {
                    if (!checkedBudgets.containsKey(packageName)
                            && !checkedSchedules.containsKey(packageName)) {
                        throw new IllegalArgumentException(
                                "Quiz trigger package needs a daily budget or schedule");
                    }
                    if (checkedBlocked.contains(packageName)) {
                        throw new IllegalArgumentException(
                                "An always-blocked package cannot be a quiz trigger target");
                    }
                }
                checkedTriggers.add(trigger);
            }
        }
        this.quizTriggers = Collections.unmodifiableList(checkedTriggers);
        List<HelpContact> checkedContacts = new ArrayList<>();
        if (contactSource != null) {
            if (contactSource.size() > MAX_HELP_CONTACTS) {
                throw new IllegalArgumentException("At most 3 help contacts are supported");
            }
            Set<String> keys = new LinkedHashSet<>();
            for (HelpContact contact : contactSource) {
                if (contact == null || !keys.add(contact.key())) {
                    throw new IllegalArgumentException("Help contacts must be unique");
                }
                checkedContacts.add(contact);
            }
        }
        this.helpContacts = Collections.unmodifiableList(checkedContacts);
    }

    public static void requirePackage(String packageName) {
        if (packageName == null || packageName.length() > 255 || !PACKAGE.matcher(packageName).matches()) {
            throw new IllegalArgumentException("Invalid Android package name");
        }
    }

    public static GuardPolicy disabled(String timezone) {
        return new GuardPolicy(0, timezone, false, false, true, 10, 8, 60,
                Collections.emptyMap(), Collections.emptySet());
    }

    public long revision() { return revision; }
    public String timezone() { return timezone; }
    public boolean enabled() { return enabled; }
    public boolean launcherEnabled() { return launcherEnabled; }
    public boolean quizGateEnabled() { return quizGateEnabled; }
    public int rewardMinutes() { return rewardMinutes; }
    public int maxAttemptsPerDay() { return maxAttemptsPerDay; }
    public int cooldownSeconds() { return cooldownSeconds; }
    public int budgetWarningMinutes() { return budgetWarningMinutes; }
    public int continuousUseReminderMinutes() { return continuousUseReminderMinutes; }
    public Map<String, Integer> dailyBudgets() { return dailyBudgets; }
    public Set<String> blockedPackages() { return blockedPackages; }
    public Set<String> alwaysAllowedPackages() { return alwaysAllowedPackages; }
    public Map<String, List<GuardSchedule>> schedules() { return schedules; }
    public QuizSettings quizSettings() { return quizSettings; }
    public List<QuizTrigger> quizTriggers() { return quizTriggers; }
    public List<HelpContact> helpContacts() { return helpContacts; }

    /** A package is manageable when the signed policy gives it a budget or window. */
    public boolean managesPackage(String packageName) {
        return packageName != null
                && !alwaysAllowedPackages.contains(packageName)
                && (dailyBudgets.containsKey(packageName) || schedules.containsKey(packageName));
    }

    /** An always-allowed package bypasses schedules, budgets and quiz gates. */
    public boolean isAlwaysAllowed(String packageName) {
        return packageName != null && alwaysAllowedPackages.contains(packageName);
    }

    public boolean isWithinSchedule(String packageName, LocalDateTime localTime) {
        List<GuardSchedule> windows = schedules.get(packageName);
        if (windows == null) return true;
        for (GuardSchedule window : windows) {
            if (window.isActive(localTime)) return true;
        }
        return false;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("schema", CURRENT_SCHEMA);
            json.put("revision", revision);
            json.put("timezone", timezone);
            json.put("enabled", enabled);
            json.put("launcher_enabled", launcherEnabled);
            json.put("quiz_gate_enabled", quizGateEnabled);
            json.put("reward_minutes", rewardMinutes);
            json.put("max_attempts_per_day", maxAttemptsPerDay);
            json.put("cooldown_seconds", cooldownSeconds);
            json.put("budget_warning_minutes", budgetWarningMinutes);
            json.put("continuous_use_reminder_minutes", continuousUseReminderMinutes);
            JSONObject budgets = new JSONObject();
            for (Map.Entry<String, Integer> entry : dailyBudgets.entrySet()) {
                budgets.put(entry.getKey(), entry.getValue());
            }
            json.put("daily_budgets", budgets);
            JSONArray blocked = new JSONArray();
            for (String packageName : blockedPackages) blocked.put(packageName);
            json.put("blocked_packages", blocked);
            JSONArray alwaysAllowed = new JSONArray();
            for (String packageName : alwaysAllowedPackages) alwaysAllowed.put(packageName);
            json.put("always_allowed_packages", alwaysAllowed);
            JSONObject scheduleJson = new JSONObject();
            for (Map.Entry<String, List<GuardSchedule>> entry : schedules.entrySet()) {
                JSONArray windows = new JSONArray();
                for (GuardSchedule window : entry.getValue()) windows.put(window.toJson());
                scheduleJson.put(entry.getKey(), windows);
            }
            json.put("schedules", scheduleJson);
            json.put("quiz_question_count", quizSettings.questionCount());
            json.put("quiz_subjects", strings(quizSettings.subjects()));
            JSONArray grades = new JSONArray();
            for (Integer grade : quizSettings.grades()) grades.put(grade);
            json.put("quiz_grades", grades);
            json.put("quiz_difficulties", strings(quizSettings.difficulties()));
            json.put("quiz_languages", strings(quizSettings.languages()));
            json.put("quiz_tags", strings(quizSettings.tags()));
            JSONArray triggerJson = new JSONArray();
            for (QuizTrigger trigger : quizTriggers) triggerJson.put(trigger.toJson());
            json.put("quiz_triggers", triggerJson);
            JSONArray contacts = new JSONArray();
            for (HelpContact contact : helpContacts) contacts.put(contact.toJson());
            json.put("help_contacts", contacts);
            return json;
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static GuardPolicy fromJson(String raw) {
        try {
            JSONObject json = new JSONObject(raw);
            String[] required = {"schema", "revision", "timezone", "enabled", "launcher_enabled",
                    "quiz_gate_enabled", "reward_minutes", "max_attempts_per_day", "cooldown_seconds",
                    "daily_budgets", "blocked_packages"};
            for (String field : required) {
                if (!json.has(field) || json.isNull(field)) {
                    throw new IllegalArgumentException("Missing policy field: " + field);
                }
            }
            json.getBoolean("enabled");
            json.getBoolean("launcher_enabled");
            json.getBoolean("quiz_gate_enabled");
            JSONObject source = json.getJSONObject("daily_budgets");
            JSONArray sourceBlocked = json.getJSONArray("blocked_packages");
            if (json.getInt("schema") != CURRENT_SCHEMA) {
                throw new IllegalArgumentException("Unsupported policy schema");
            }
            Map<String, Integer> budgets = new LinkedHashMap<>();
            JSONArray names = source.names();
            if (names != null) {
                for (int index = 0; index < names.length(); index++) {
                    String name = names.getString(index);
                    Object value = source.get(name);
                    if (!(value instanceof Integer)) {
                        throw new IllegalArgumentException("Daily minutes must be an integer");
                    }
                    budgets.put(name, (Integer) value);
                }
            }
            Set<String> blocked = new LinkedHashSet<>();
            for (int index = 0; index < sourceBlocked.length(); index++) {
                blocked.add(sourceBlocked.getString(index));
            }
            Set<String> alwaysAllowed = new LinkedHashSet<>();
            JSONArray sourceAlwaysAllowed = json.optJSONArray("always_allowed_packages");
            if (sourceAlwaysAllowed != null) {
                for (int index = 0; index < sourceAlwaysAllowed.length(); index++) {
                    alwaysAllowed.add(sourceAlwaysAllowed.getString(index));
                }
            }
            Map<String, List<GuardSchedule>> scheduleMap = new LinkedHashMap<>();
            JSONObject schedulesJson = json.optJSONObject("schedules");
            if (schedulesJson != null) {
                JSONArray packageNames = schedulesJson.names();
                if (packageNames != null) {
                    for (int index = 0; index < packageNames.length(); index++) {
                        String packageName = packageNames.getString(index);
                        JSONArray windows = schedulesJson.getJSONArray(packageName);
                        List<GuardSchedule> parsed = new ArrayList<>();
                        for (int window = 0; window < windows.length(); window++) {
                            parsed.add(GuardSchedule.fromJson(windows.getJSONObject(window)));
                        }
                        scheduleMap.put(packageName, parsed);
                    }
                }
            }
            QuizSettings quizSettings = QuizSettings.fromJson(json);
            List<QuizTrigger> triggers = new ArrayList<>();
            JSONArray triggerSource = json.optJSONArray("quiz_triggers");
            if (triggerSource != null) {
                for (int index = 0; index < triggerSource.length(); index++) {
                    triggers.add(QuizTrigger.fromJson(triggerSource.getJSONObject(index)));
                }
            }
            List<HelpContact> contacts = new ArrayList<>();
            JSONArray contactSource = json.optJSONArray("help_contacts");
            if (contactSource != null) {
                if (contactSource.length() > MAX_HELP_CONTACTS) {
                    throw new IllegalArgumentException("At most 3 help contacts are supported");
                }
                for (int index = 0; index < contactSource.length(); index++) {
                    contacts.add(HelpContact.fromJson(contactSource.getJSONObject(index)));
                }
            }
            return new GuardPolicy(json.getLong("revision"), json.getString("timezone"),
                    json.getBoolean("enabled"), json.getBoolean("launcher_enabled"),
                    json.getBoolean("quiz_gate_enabled"), json.getInt("reward_minutes"),
                    json.getInt("max_attempts_per_day"), json.getInt("cooldown_seconds"),
                    budgets, blocked, scheduleMap, triggers, quizSettings, contacts,
                    alwaysAllowed, json.optInt("budget_warning_minutes", 0),
                    json.optInt("continuous_use_reminder_minutes", 0));
        } catch (JSONException exception) {
            throw new IllegalArgumentException("Invalid policy JSON", exception);
        }
    }

    private static JSONArray strings(Set<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    /** Parent-selected filters and the number of questions in one reward attempt. */
    public static final class QuizSettings {
        private final int questionCount;
        private final Set<String> subjects;
        private final Set<Integer> grades;
        private final Set<String> difficulties;
        private final Set<String> languages;
        private final Set<String> tags;

        public QuizSettings(int questionCount, Set<String> subjects, Set<Integer> grades,
                Set<String> difficulties, Set<String> languages, Set<String> tags) {
            if (questionCount < 1 || questionCount > 10) {
                throw new IllegalArgumentException("Quiz question count must be 1 to 10");
            }
            this.questionCount = questionCount;
            this.subjects = normalizedStrings(subjects, "subject", 64, false);
            Set<Integer> checkedGrades = new LinkedHashSet<>();
            if (grades != null) {
                for (Integer grade : grades) {
                    if (grade == null || grade < 0 || grade > 12) {
                        throw new IllegalArgumentException("Quiz grades must be 0 to 12");
                    }
                    checkedGrades.add(grade);
                }
            }
            this.grades = Collections.unmodifiableSet(checkedGrades);
            this.difficulties = normalizedStrings(difficulties, "difficulty", 16, true);
            for (String difficulty : this.difficulties) {
                if (!difficulty.equals("easy") && !difficulty.equals("medium")
                        && !difficulty.equals("hard")) {
                    throw new IllegalArgumentException("Quiz difficulty must be easy, medium, or hard");
                }
            }
            this.languages = normalizedStrings(languages, "language", 32, true);
            for (String language : this.languages) {
                if (!language.matches("[a-z]{2,8}(?:-[a-z0-9]{1,8})*")) {
                    throw new IllegalArgumentException("Quiz language must be a BCP-47 tag");
                }
            }
            this.tags = normalizedStrings(tags, "tag", 48, false);
        }

        public static QuizSettings defaults() {
            return new QuizSettings(1, Collections.emptySet(), Collections.emptySet(),
                    Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        }

        public int questionCount() { return questionCount; }
        public Set<String> subjects() { return subjects; }
        public Set<Integer> grades() { return grades; }
        public Set<String> difficulties() { return difficulties; }
        public Set<String> languages() { return languages; }
        public Set<String> tags() { return tags; }

        public boolean hasFilters() {
            return !subjects.isEmpty() || !grades.isEmpty() || !difficulties.isEmpty()
                    || !languages.isEmpty() || !tags.isEmpty();
        }

        public boolean matches(QuizQuestion question) {
            if (question == null) return false;
            QuizQuestion.Metadata metadata = question.metadata();
            String subject = QuizQuestion.normalizeText(metadata.subject());
            if (!subjects.isEmpty() && !subjects.contains(subject)) return false;
            if (!grades.isEmpty() && !grades.contains(metadata.grade())) return false;
            if (!difficulties.isEmpty()
                    && !difficulties.contains(metadata.difficulty().toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
            if (!languages.isEmpty()
                    && !languages.contains(metadata.language().toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
            if (!tags.isEmpty() && Collections.disjoint(tags, metadata.tags())) return false;
            return true;
        }

        private static Set<String> normalizedStrings(Set<String> source, String label,
                int maxLength, boolean lowercase) {
            Set<String> result = new LinkedHashSet<>();
            if (source == null) return Collections.emptySet();
            if (source.size() > 64) {
                throw new IllegalArgumentException("At most 64 quiz filters are supported");
            }
            for (String value : source) {
                if (value == null) continue;
                String cleaned = value.trim();
                if (cleaned.isEmpty()) continue;
                if (cleaned.length() > maxLength) {
                    throw new IllegalArgumentException("Quiz " + label + " is too long");
                }
                for (int index = 0; index < cleaned.length(); index++) {
                    if (Character.isISOControl(cleaned.charAt(index))) {
                        throw new IllegalArgumentException("Quiz " + label + " contains control characters");
                    }
                }
                result.add(lowercase ? cleaned.toLowerCase(java.util.Locale.ROOT)
                        : QuizQuestion.normalizeText(cleaned));
            }
            return Collections.unmodifiableSet(result);
        }

        private static QuizSettings fromJson(JSONObject json) throws JSONException {
            if (!json.has("quiz_question_count") && !json.has("quiz_subjects")
                    && !json.has("quiz_grades") && !json.has("quiz_difficulties")
                    && !json.has("quiz_languages") && !json.has("quiz_tags")) {
                return defaults();
            }
            Set<String> subjects = readStrings(json, "quiz_subjects");
            Set<String> difficulties = readStrings(json, "quiz_difficulties");
            Set<String> languages = readStrings(json, "quiz_languages");
            Set<String> tags = readStrings(json, "quiz_tags");
            Set<Integer> grades = new LinkedHashSet<>();
            JSONArray gradeSource = json.optJSONArray("quiz_grades");
            if (gradeSource != null) {
                for (int index = 0; index < gradeSource.length(); index++) {
                    grades.add(gradeSource.getInt(index));
                }
            }
            return new QuizSettings(json.optInt("quiz_question_count", 1),
                    subjects, grades, difficulties, languages, tags);
        }

        private static Set<String> readStrings(JSONObject json, String name) throws JSONException {
            Set<String> result = new LinkedHashSet<>();
            JSONArray source = json.optJSONArray(name);
            if (source != null) {
                for (int index = 0; index < source.length(); index++) {
                    result.add(source.getString(index));
                }
            }
            return result;
        }
    }

    /** A signed rule that raises the quiz gate for one or more managed apps. */
    public static final class QuizTrigger {
        public enum Type {
            TOTAL_USAGE("total_usage"),
            APP_ENTER("app_enter"),
            CONTINUOUS_USE("continuous_use"),
            SCHEDULE_START("schedule_start");

            private final String wireName;

            Type(String wireName) { this.wireName = wireName; }

            public String wireName() { return wireName; }

            static Type fromWireName(String value) {
                for (Type type : values()) {
                    if (type.wireName.equals(value)) return type;
                }
                throw new IllegalArgumentException("Unsupported quiz trigger type");
            }
        }

        private final String id;
        private final Type type;
        private final List<String> packages;
        private final int minutes;
        private final int delaySeconds;
        private final Set<DayOfWeek> days;
        private final int startMinute;

        public QuizTrigger(String id, Type type, List<String> packages, int minutes,
                int delaySeconds, Set<DayOfWeek> days, int startMinute) {
            if (id == null || !id.matches("quiz-trigger-[a-f0-9]{24}")) {
                throw new IllegalArgumentException("Quiz trigger id is invalid");
            }
            if (type == null) throw new IllegalArgumentException("Quiz trigger type is required");
            if (packages == null || packages.isEmpty() || packages.size() > 50) {
                throw new IllegalArgumentException("A quiz trigger needs 1 to 50 packages");
            }
            List<String> checkedPackages = new ArrayList<>(packages);
            for (String packageName : checkedPackages) requirePackage(packageName);
            Collections.sort(checkedPackages);
            if (new LinkedHashSet<>(checkedPackages).size() != checkedPackages.size()) {
                throw new IllegalArgumentException("Quiz trigger packages contain duplicates");
            }
            if (minutes < 0 || minutes > 1440) {
                throw new IllegalArgumentException("Quiz trigger minutes must be 0 to 1440");
            }
            if (delaySeconds < 0 || delaySeconds > 3600) {
                throw new IllegalArgumentException("Quiz trigger delay must be 0 to 3600 seconds");
            }
            if (startMinute < 0 || startMinute > 1439) {
                throw new IllegalArgumentException("Quiz trigger start minute is invalid");
            }
            Set<DayOfWeek> checkedDays = new LinkedHashSet<>();
            if (days != null) {
                for (DayOfWeek day : days) {
                    if (day != null) checkedDays.add(day);
                }
            }
            switch (type) {
                case TOTAL_USAGE:
                case CONTINUOUS_USE:
                    if (minutes < 1 || delaySeconds != 0 || !checkedDays.isEmpty()
                            || startMinute != 0) {
                        throw new IllegalArgumentException("Usage triggers need only a positive minute value");
                    }
                    break;
                case APP_ENTER:
                    if (minutes != 0 || !checkedDays.isEmpty() || startMinute != 0) {
                        throw new IllegalArgumentException("An app-enter trigger may only use delay_seconds");
                    }
                    break;
                case SCHEDULE_START:
                    if (minutes != 0 || delaySeconds != 0 || checkedDays.isEmpty()
                            || startMinute < 0) {
                        throw new IllegalArgumentException("A schedule trigger needs days and start_minute");
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported quiz trigger type");
            }
            this.id = id;
            this.type = type;
            this.packages = Collections.unmodifiableList(checkedPackages);
            this.minutes = minutes;
            this.delaySeconds = delaySeconds;
            this.days = Collections.unmodifiableSet(checkedDays);
            this.startMinute = startMinute;
        }

        public String id() { return id; }
        public Type type() { return type; }
        public List<String> packages() { return packages; }
        public int minutes() { return minutes; }
        public int delaySeconds() { return delaySeconds; }
        public Set<DayOfWeek> days() { return days; }
        public int startMinute() { return startMinute; }

        public String computedId() {
            return idFor(type, packages, minutes, delaySeconds, days, startMinute);
        }

        public static QuizTrigger create(Type type, List<String> packages, int minutes,
                int delaySeconds, Set<DayOfWeek> days, int startMinute) {
            String id = idFor(type, packages, minutes, delaySeconds, days, startMinute);
            return new QuizTrigger(id, type, packages, minutes, delaySeconds, days, startMinute);
        }

        public static String idFor(Type type, List<String> packages, int minutes,
                int delaySeconds, Set<DayOfWeek> days, int startMinute) {
            List<String> sortedPackages = new ArrayList<>(packages);
            Collections.sort(sortedPackages);
            return "quiz-trigger-" + fingerprint("guard-quiz-trigger-v1\n"
                    + type.wireName + "\n" + String.join(",", sortedPackages) + "\n" + minutes
                    + "\n" + delaySeconds + "\n" + dayCsv(days) + "\n" + startMinute)
                    .substring(0, 24);
        }

        JSONObject toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("id", id);
                json.put("type", type.wireName);
                JSONArray packageJson = new JSONArray();
                for (String packageName : packages) packageJson.put(packageName);
                json.put("packages", packageJson);
                if (type == Type.TOTAL_USAGE || type == Type.CONTINUOUS_USE) {
                    json.put("minutes", minutes);
                } else if (type == Type.APP_ENTER) {
                    json.put("delay_seconds", delaySeconds);
                } else {
                    JSONArray dayJson = new JSONArray();
                    for (DayOfWeek day : days) dayJson.put(day.getValue());
                    json.put("days", dayJson);
                    json.put("start_minute", startMinute);
                }
                return json;
            } catch (JSONException exception) {
                throw new IllegalStateException(exception);
            }
        }

        static QuizTrigger fromJson(JSONObject json) throws JSONException {
            String id = json.getString("id");
            Type type = Type.fromWireName(json.getString("type"));
            Set<String> allowed = new LinkedHashSet<>();
            allowed.add("id");
            allowed.add("type");
            allowed.add("packages");
            if (type == Type.TOTAL_USAGE || type == Type.CONTINUOUS_USE) allowed.add("minutes");
            if (type == Type.APP_ENTER) allowed.add("delay_seconds");
            if (type == Type.SCHEDULE_START) {
                allowed.add("days");
                allowed.add("start_minute");
            }
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!allowed.contains(key)) {
                    throw new IllegalArgumentException("Unknown quiz trigger field: " + key);
                }
            }
            JSONArray packageJson = json.getJSONArray("packages");
            List<String> packages = new ArrayList<>();
            for (int index = 0; index < packageJson.length(); index++) {
                packages.add(packageJson.getString(index));
            }
            Set<DayOfWeek> days = new LinkedHashSet<>();
            JSONArray dayJson = json.optJSONArray("days");
            if (dayJson != null) {
                for (int index = 0; index < dayJson.length(); index++) {
                    days.add(DayOfWeek.of(dayJson.getInt(index)));
                }
            }
            return new QuizTrigger(id, type, packages,
                    type == Type.TOTAL_USAGE || type == Type.CONTINUOUS_USE
                            ? json.getInt("minutes") : 0,
                    type == Type.APP_ENTER ? json.optInt("delay_seconds", 0) : 0,
                    days, type == Type.SCHEDULE_START ? json.getInt("start_minute") : 0);
        }

        private static String dayCsv(Set<DayOfWeek> days) {
            List<Integer> values = new ArrayList<>();
            for (DayOfWeek day : days) values.add(day.getValue());
            Collections.sort(values);
            List<String> text = new ArrayList<>();
            for (Integer value : values) text.add(String.valueOf(value));
            return String.join(",", text);
        }

        private static String fingerprint(String value) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8));
                StringBuilder result = new StringBuilder(digest.length * 2);
                for (byte item : digest) result.append(String.format("%02x", item));
                return result.toString();
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    /** A destination shown on the child task page; Guard never dials it automatically. */
    public static final class HelpContact {
        private final String label;
        private final String phone;

        public HelpContact(String label, String phone) {
            String checkedLabel = label == null ? "" : label.trim();
            String checkedPhone = phone == null ? "" : phone.trim();
            if (checkedLabel.isEmpty() || checkedLabel.length() > 40
                    || hasControlCharacter(checkedLabel)) {
                throw new IllegalArgumentException("Help contact label must be 1 to 40 characters");
            }
            if (checkedPhone.length() < 2 || checkedPhone.length() > 24
                    || !PHONE.matcher(checkedPhone).matches()) {
                throw new IllegalArgumentException("Help contact phone number is invalid");
            }
            int digits = 0;
            for (int index = 0; index < checkedPhone.length(); index++) {
                if (Character.isDigit(checkedPhone.charAt(index))) digits++;
            }
            if (digits < 2 || digits > 20) {
                throw new IllegalArgumentException("Help contact phone number has an invalid length");
            }
            this.label = checkedLabel;
            this.phone = checkedPhone;
        }

        public String label() { return label; }
        public String phone() { return phone; }

        String key() { return label + "\u0000" + phone; }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("label", label).put("phone", phone);
        }

        static HelpContact fromJson(JSONObject json) throws JSONException {
            Set<String> allowed = new LinkedHashSet<>();
            allowed.add("label");
            allowed.add("phone");
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!allowed.contains(key)) {
                    throw new IllegalArgumentException("Unknown help contact field: " + key);
                }
            }
            return new HelpContact(json.getString("label"), json.getString("phone"));
        }

        private static boolean hasControlCharacter(String value) {
            for (int index = 0; index < value.length(); index++) {
                if (Character.isISOControl(value.charAt(index))) return true;
            }
            return false;
        }
    }

    public static final class GuardSchedule {
        private final Set<DayOfWeek> days;
        private final int startMinute;
        private final int durationMinutes;

        public GuardSchedule(Set<DayOfWeek> days, int startMinute, int durationMinutes) {
            if (days == null || days.isEmpty()) {
                throw new IllegalArgumentException("A schedule needs at least one day");
            }
            if (startMinute < 0 || startMinute >= 1440) {
                throw new IllegalArgumentException("Schedule start must be within one day");
            }
            if (durationMinutes < 1 || durationMinutes > 1440) {
                throw new IllegalArgumentException("Schedule duration must be 1 to 1440 minutes");
            }
            this.days = Collections.unmodifiableSet(new LinkedHashSet<>(days));
            this.startMinute = startMinute;
            this.durationMinutes = durationMinutes;
        }

        public Set<DayOfWeek> days() { return days; }
        public int startMinute() { return startMinute; }
        public int durationMinutes() { return durationMinutes; }

        public boolean isActive(LocalDateTime time) {
            int minuteOfDay = time.getHour() * 60 + time.getMinute();
            if (minuteOfDay >= startMinute
                    && minuteOfDay < startMinute + durationMinutes) {
                return days.contains(time.getDayOfWeek());
            }
            if (startMinute + durationMinutes > 1440
                    && minuteOfDay < startMinute + durationMinutes - 1440) {
                return days.contains(time.getDayOfWeek().minus(1));
            }
            return false;
        }

        JSONObject toJson() throws JSONException {
            JSONArray dayJson = new JSONArray();
            for (DayOfWeek day : days) dayJson.put(day.getValue());
            JSONObject json = new JSONObject();
            json.put("days", dayJson);
            json.put("start_minute", startMinute);
            json.put("duration_minutes", durationMinutes);
            return json;
        }

        static GuardSchedule fromJson(JSONObject json) throws JSONException {
            JSONArray dayJson = json.getJSONArray("days");
            Set<DayOfWeek> days = new LinkedHashSet<>();
            for (int index = 0; index < dayJson.length(); index++) {
                days.add(DayOfWeek.of(dayJson.getInt(index)));
            }
            return new GuardSchedule(days, json.getInt("start_minute"),
                    json.getInt("duration_minutes"));
        }
    }
}
