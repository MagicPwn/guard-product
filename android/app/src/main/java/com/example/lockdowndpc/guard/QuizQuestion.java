package com.example.lockdowndpc.guard;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable, verified quiz content. Every field is copied before it is retained. */
public final class QuizQuestion {
    public static final int MAX_IMAGE_BYTES = 512 * 1024;
    private static final byte[] PNG_MAGIC = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    public enum Type {
        SINGLE_CHOICE,
        MULTIPLE_CHOICE,
        TRUE_FALSE,
        SHORT_ANSWER
    }

    private final Type type;
    private final String id;
    private final String prompt;
    private final List<String> options;
    private final Set<Integer> answerIndices;
    private final Boolean booleanAnswer;
    private final List<String> acceptedAnswers;
    private final Metadata metadata;
    private final byte[] imageData;
    private final String imageMime;

    /** Backward-compatible constructor for the original single-choice pack format. */
    public QuizQuestion(String id, String prompt, List<String> options, int answerIndex) {
        this(Type.SINGLE_CHOICE, id, prompt, options,
                Collections.singleton(answerIndex), null, Collections.emptyList(),
                Metadata.EMPTY, null, null);
    }

    private QuizQuestion(Type type, String id, String prompt, List<String> options,
            Set<Integer> answerIndices, Boolean booleanAnswer, List<String> acceptedAnswers,
            Metadata metadata, byte[] imageData, String imageMime) {
        requireText(id, "Question id", 128);
        requireText(prompt, "Question prompt", 2_000);
        Metadata checkedMetadata = metadata == null ? Metadata.EMPTY : metadata;

        List<String> checkedOptions = new ArrayList<>();
        if (options != null) {
            if (options.size() > 8) throw new IllegalArgumentException("A question supports at most 8 options");
            for (String option : options) {
                requireText(option, "Question option", 300);
                checkedOptions.add(option.trim());
            }
        }
        if (new HashSet<>(checkedOptions).size() != checkedOptions.size()) {
            throw new IllegalArgumentException("Question options must be distinct");
        }

        Set<Integer> checkedAnswers = new LinkedHashSet<>();
        if (answerIndices != null) {
            for (Integer answer : answerIndices) {
                if (answer == null || answer < 0 || answer >= checkedOptions.size()) {
                    throw new IllegalArgumentException("Question answer index is out of range");
                }
                checkedAnswers.add(answer);
            }
        }
        switch (type) {
            case SINGLE_CHOICE:
                if (checkedOptions.size() < 2 || checkedAnswers.size() != 1) {
                    throw new IllegalArgumentException("Single choice needs 2 to 8 options and one answer");
                }
                break;
            case MULTIPLE_CHOICE:
                if (checkedOptions.size() < 2 || checkedAnswers.size() < 2
                        || checkedAnswers.size() >= checkedOptions.size()) {
                    throw new IllegalArgumentException(
                            "Multiple choice needs 2 to 8 options and 2 or more, but not all, marked correct");
                }
                break;
            case TRUE_FALSE:
                if (!checkedOptions.isEmpty() || !checkedAnswers.isEmpty() || booleanAnswer == null) {
                    throw new IllegalArgumentException("True/false questions use only a boolean answer");
                }
                break;
            case SHORT_ANSWER:
                if (!checkedOptions.isEmpty() || !checkedAnswers.isEmpty() || booleanAnswer != null) {
                    throw new IllegalArgumentException("Short-answer questions use only accepted text answers");
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported question type");
        }

        List<String> checkedAccepted = new ArrayList<>();
        if (acceptedAnswers != null) {
            if (acceptedAnswers.size() > 8) {
                throw new IllegalArgumentException("A short-answer question supports at most 8 accepted answers");
            }
            Set<String> normalized = new HashSet<>();
            for (String answer : acceptedAnswers) {
                requireText(answer, "Accepted answer", 300);
                String canonical = normalizeText(answer);
                if (!normalized.add(canonical)) {
                    throw new IllegalArgumentException("Accepted answers must be distinct after normalization");
                }
                checkedAccepted.add(answer.trim());
            }
        }
        if (type == Type.SHORT_ANSWER && checkedAccepted.isEmpty()) {
            throw new IllegalArgumentException("Short-answer questions need at least one accepted answer");
        }
        if (type != Type.SHORT_ANSWER && !checkedAccepted.isEmpty()) {
            throw new IllegalArgumentException("Only short-answer questions may define accepted answers");
        }

        byte[] checkedImage = imageData == null ? null : imageData.clone();
        if (checkedImage != null) {
            validateImage(checkedImage, imageMime);
        } else if (imageMime != null) {
            throw new IllegalArgumentException("Image MIME type requires image data");
        }

        this.type = type;
        this.id = id.trim();
        this.prompt = prompt.trim();
        this.options = Collections.unmodifiableList(checkedOptions);
        this.answerIndices = Collections.unmodifiableSet(checkedAnswers);
        this.booleanAnswer = booleanAnswer;
        this.acceptedAnswers = Collections.unmodifiableList(checkedAccepted);
        this.metadata = checkedMetadata;
        this.imageData = checkedImage;
        this.imageMime = checkedImage == null ? null : imageMime;
    }

    public static QuizQuestion singleChoice(String id, String prompt, List<String> options,
            int answerIndex, Metadata metadata) {
        return new QuizQuestion(Type.SINGLE_CHOICE, id, prompt, options,
                Collections.singleton(answerIndex), null, Collections.emptyList(),
                metadata, null, null);
    }

    public static QuizQuestion multipleChoice(String id, String prompt, List<String> options,
            Set<Integer> answerIndices, Metadata metadata) {
        return new QuizQuestion(Type.MULTIPLE_CHOICE, id, prompt, options,
                answerIndices, null, Collections.emptyList(), metadata, null, null);
    }

    public static QuizQuestion trueFalse(String id, String prompt, boolean answer, Metadata metadata) {
        return new QuizQuestion(Type.TRUE_FALSE, id, prompt, Collections.emptyList(),
                Collections.emptySet(), answer, Collections.emptyList(), metadata, null, null);
    }

    public static QuizQuestion shortAnswer(String id, String prompt, List<String> acceptedAnswers,
            Metadata metadata) {
        return new QuizQuestion(Type.SHORT_ANSWER, id, prompt, Collections.emptyList(),
                Collections.emptySet(), null, acceptedAnswers, metadata, null, null);
    }

    /** Returns a copy of this question with an embedded, verified JPEG or PNG image. */
    public QuizQuestion withImage(byte[] data, String mime) {
        return new QuizQuestion(type, id, prompt, options, answerIndices, booleanAnswer,
                acceptedAnswers, metadata, data, mime);
    }

    public Type type() { return type; }
    public String id() { return id; }
    public String prompt() { return prompt; }
    public List<String> options() { return options; }
    public Set<Integer> answerIndices() { return answerIndices; }
    public boolean booleanAnswer() {
        if (booleanAnswer == null) throw new IllegalStateException("Question is not true/false");
        return booleanAnswer;
    }
    public List<String> acceptedAnswers() { return acceptedAnswers; }
    public Metadata metadata() { return metadata; }
    public boolean hasImage() { return imageData != null; }
    public byte[] imageData() { return imageData == null ? null : imageData.clone(); }
    public String imageMime() { return imageMime; }

    /** Legacy accessor retained for old callers; returns -1 unless exactly one answer is marked. */
    public int answerIndex() {
        return answerIndices.size() == 1 ? answerIndices.iterator().next() : -1;
    }

    public boolean acceptsSelection(Set<Integer> selected) {
        return (type == Type.SINGLE_CHOICE || type == Type.MULTIPLE_CHOICE)
                && selected != null && new HashSet<>(selected).equals(answerIndices);
    }

    public boolean acceptsBoolean(boolean answer) {
        return type == Type.TRUE_FALSE && booleanAnswer != null && booleanAnswer == answer;
    }

    public boolean acceptsText(String answer) {
        if (type != Type.SHORT_ANSWER || answer == null) return false;
        String normalized = normalizeText(answer);
        if (normalized.isEmpty()) return false;
        for (String accepted : acceptedAnswers) {
            if (normalizeText(accepted).equals(normalized)) return true;
        }
        return false;
    }

    public static String normalizeText(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static void validateImage(byte[] data, String mime) {
        if (data.length < 4 || data.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Embedded question image must be 4 bytes to 512 KiB");
        }
        boolean jpeg = data.length >= 3 && data[0] == (byte) 0xff
                && data[1] == (byte) 0xd8 && data[2] == (byte) 0xff;
        boolean png = data.length >= PNG_MAGIC.length;
        if (png) {
            for (int index = 0; index < PNG_MAGIC.length; index++) {
                if (data[index] != PNG_MAGIC[index]) {
                    png = false;
                    break;
                }
            }
        }
        if (jpeg && !"image/jpeg".equals(mime)) {
            throw new IllegalArgumentException("JPEG questions require image/jpeg MIME type");
        }
        if (png && !"image/png".equals(mime)) {
            throw new IllegalArgumentException("PNG questions require image/png MIME type");
        }
        if (!jpeg && !png) {
            throw new IllegalArgumentException("Embedded question image must be JPEG or PNG");
        }
    }

    private static void requireText(String value, String label, int maxLength) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(label + " must be 1 to " + maxLength + " characters");
        }
    }

    /** Educational metadata used for parent-selected question filters. */
    public static final class Metadata {
        public static final Metadata EMPTY = new Metadata("", 0, "", "", Collections.emptySet());
        private final String subject;
        private final int grade;
        private final String difficulty;
        private final String language;
        private final Set<String> tags;

        public Metadata(String subject, int grade, String difficulty, String language,
                Set<String> tags) {
            if (grade < 0 || grade > 12) {
                throw new IllegalArgumentException("Question grade must be 0 (unspecified) to 12");
            }
            this.subject = optionalText(subject, "Subject", 64);
            this.grade = grade;
            String cleanedDifficulty = optionalText(difficulty, "Difficulty", 16)
                    .toLowerCase(Locale.ROOT);
            if (!cleanedDifficulty.isEmpty() && !cleanedDifficulty.equals("easy")
                    && !cleanedDifficulty.equals("medium") && !cleanedDifficulty.equals("hard")) {
                throw new IllegalArgumentException("Difficulty must be easy, medium, or hard");
            }
            this.difficulty = cleanedDifficulty;
            String cleanedLanguage = optionalText(language, "Language", 32)
                    .toLowerCase(Locale.ROOT);
            if (!cleanedLanguage.isEmpty()
                    && !cleanedLanguage.matches("[a-z]{2,8}(?:-[a-z0-9]{1,8})*")) {
                throw new IllegalArgumentException("Question language must be a BCP-47 tag");
            }
            this.language = cleanedLanguage;
            Set<String> checkedTags = new LinkedHashSet<>();
            if (tags != null) {
                if (tags.size() > 32) throw new IllegalArgumentException("At most 32 question tags are supported");
                for (String tag : tags) {
                    String cleaned = optionalText(tag, "Question tag", 48).toLowerCase(Locale.ROOT);
                    if (!cleaned.isEmpty()) checkedTags.add(cleaned);
                }
            }
            this.tags = Collections.unmodifiableSet(checkedTags);
        }

        public String subject() { return subject; }
        public int grade() { return grade; }
        public String difficulty() { return difficulty; }
        public String language() { return language; }
        public Set<String> tags() { return tags; }

        private static String optionalText(String value, String label, int maxLength) {
            if (value == null) return "";
            String cleaned = value.trim();
            if (cleaned.length() > maxLength) {
                throw new IllegalArgumentException(label + " is too long");
            }
            for (int index = 0; index < cleaned.length(); index++) {
                if (Character.isISOControl(cleaned.charAt(index))) {
                    throw new IllegalArgumentException(label + " contains control characters");
                }
            }
            return cleaned;
        }
    }
}
