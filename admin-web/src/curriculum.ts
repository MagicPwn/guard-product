import {
  CHINESE_POEMS,
  CHINESE_UNITS,
  ENGLISH_UNITS,
  MATH_APPLICATIONS,
  MATH_CALCULATIONS,
  type ChinesePoem
} from "./curriculum-data";
import { emptyQuestion, type EditableQuestion } from "./quiz";

export const CURRICULUM_EDITIONS = ["pep"] as const;
export type CurriculumEdition = (typeof CURRICULUM_EDITIONS)[number];
export const CURRICULUM_GRADES = [1, 2, 3, 4, 5, 6] as const;
export const CURRICULUM_UNITS = [1, 2] as const;
export type CurriculumGrade = (typeof CURRICULUM_GRADES)[number];
export type CurriculumUnit = (typeof CURRICULUM_UNITS)[number];
export type CurriculumGradeFilter = CurriculumGrade | "all";
export type CurriculumUnitFilter = CurriculumUnit | "all";
export type CurriculumSubject = "all" | "english" | "mathematics" | "chinese";

export interface CurriculumSelection {
  edition: CurriculumEdition;
  grade: CurriculumGradeFilter;
  unit: CurriculumUnitFilter;
  subject: CurriculumSubject;
}

type ResolvedSelection = Omit<CurriculumSelection, "grade" | "unit"> & {
  grade: CurriculumGrade;
  unit: CurriculumUnit;
};

const SUBJECTS: Array<Exclude<CurriculumSubject, "all">> = [
  "english",
  "mathematics",
  "chinese"
];

const ALL_WORDS = Object.values(ENGLISH_UNITS)
  .flatMap((units) => [...units[1], ...units[2]])
  .map(([word]) => word);
const ALL_MEANINGS = Object.values(ENGLISH_UNITS)
  .flatMap((units) => [...units[1], ...units[2]])
  .map(([, meaning]) => meaning);
const ALL_CHARACTERS = Object.values(CHINESE_UNITS)
  .flatMap((units) => [...units[1], ...units[2]]);
const ALL_PINYIN = ALL_CHARACTERS.map((item) => item.pinyin);
const ALL_CHINESE_WORDS = ALL_CHARACTERS.map((item) => item.word);
const ALL_POEMS = Object.values(CHINESE_POEMS).flatMap((units) => [units[1], units[2]]);
const ALL_POEM_LINES = ALL_POEMS.flatMap((poem) => [...poem.lines]);
const ALL_POEM_AUTHORS = ALL_POEMS.map((poem) => poem.author);

export function curriculumQuestions(selection: CurriculumSelection): EditableQuestion[] {
  const grades = selection.grade === "all" ? CURRICULUM_GRADES : [selection.grade];
  const units = selection.unit === "all" ? CURRICULUM_UNITS : [selection.unit];
  const compactPoems = selection.grade === "all" || selection.unit === "all";
  return grades.flatMap((grade) => units.flatMap((unit) => {
    const resolved: ResolvedSelection = { ...selection, grade, unit };
    const subjects = selection.subject === "all" ? SUBJECTS : [selection.subject];
    return subjects.flatMap((subject) => {
      switch (subject) {
        case "english":
          return englishQuestions(resolved);
        case "mathematics":
          return mathematicsQuestions(resolved);
        case "chinese":
          return chineseQuestions(resolved, compactPoems);
      }
    });
  }));
}

export function curriculumQuestionCounts(
  selection: CurriculumSelection
): Record<Exclude<CurriculumSubject, "all">, number> {
  const questions = curriculumQuestions(selection);
  return {
    english: questions.filter((question) => question.subject === "english").length,
    mathematics: questions.filter((question) => question.subject === "mathematics").length,
    chinese: questions.filter((question) => question.subject === "chinese").length
  };
}

function englishQuestions(selection: ResolvedSelection): EditableQuestion[] {
  const words = ENGLISH_UNITS[selection.grade]?.[selection.unit] ?? [];
  const result: EditableQuestion[] = [];
  words.forEach(([word, meaning], index) => {
    const base = {
      grade: selection.grade,
      unit: selection.unit,
      language: "en",
      subject: "english" as const,
      difficulty: difficultyFor(selection.grade)
    };
    const prefix = `pep-g${selection.grade}-u${selection.unit}-en-${index + 1}`;
    result.push(choiceQuestion(
      `单词 “${word}” 的中文意思是？`, meaning, ALL_MEANINGS,
      `${prefix}-meaning`, "word-meaning", base
    ));
    result.push(choiceQuestion(
      `“${meaning}”对应的英文单词是？`, word, ALL_WORDS,
      `${prefix}-choice`, "word-choice", base
    ));
    result.push(shortAnswerQuestion(
      `请拼写“${meaning}”的英文单词。`, word,
      `${prefix}-spelling`, "spelling", base
    ));
  });
  return result;
}

function mathematicsQuestions(selection: ResolvedSelection): EditableQuestion[] {
  const calculations = MATH_CALCULATIONS[selection.grade]?.[selection.unit] ?? [];
  const applications = MATH_APPLICATIONS[selection.grade]?.[selection.unit] ?? [];
  const result: EditableQuestion[] = [];
  const base = {
    grade: selection.grade,
    unit: selection.unit,
    language: "zh-CN",
    subject: "mathematics" as const,
    difficulty: difficultyFor(selection.grade)
  };
  calculations.forEach((problem, index) => {
    const prefix = `pep-g${selection.grade}-u${selection.unit}-math-${index + 1}`;
    const answer = formatNumber(Number(problem.answer));
    result.push(choiceFromOptions(
      problem.prompt, numericOptions(answer, stableHash(prefix)), answer,
      `${prefix}-calculation`, "calculation", base
    ));
  });
  applications.forEach((problem, index) => {
    const prefix = `pep-g${selection.grade}-u${selection.unit}-math-app-${index + 1}`;
    result.push(shortAnswerQuestion(
      problem.prompt, problem.answer, `${prefix}-application`, "application", base
    ));
  });
  return result;
}

function chineseQuestions(selection: ResolvedSelection, compactPoems: boolean): EditableQuestion[] {
  const characters = CHINESE_UNITS[selection.grade]?.[selection.unit] ?? [];
  const poem = CHINESE_POEMS[selection.grade]?.[selection.unit];
  const result: EditableQuestion[] = [];
  const base = {
    grade: selection.grade,
    unit: selection.unit,
    language: "zh-CN",
    subject: "chinese" as const,
    difficulty: difficultyFor(selection.grade)
  };
  characters.forEach((character, index) => {
    const prefix = `pep-g${selection.grade}-u${selection.unit}-zh-${index + 1}`;
    result.push(choiceQuestion(
      `“${character.char}”的正确拼音是？`, character.pinyin, ALL_PINYIN,
      `${prefix}-pinyin`, "character-pinyin", base
    ));
    result.push(choiceQuestion(
      `“${character.char}”可以组成下面哪个词语？`, character.word, ALL_CHINESE_WORDS,
      `${prefix}-word`, "character-word", base
    ));
  });
  if (poem) result.push(...poemQuestions(poem, selection, base, compactPoems));
  return result;
}

function poemQuestions(
  poem: ChinesePoem,
  selection: ResolvedSelection,
  base: QuestionBase,
  compact: boolean
): EditableQuestion[] {
  const result: EditableQuestion[] = [];
  const prefix = `pep-g${selection.grade}-u${selection.unit}-zh-poem`;
  const lineQuestions = compact ? 1 : poem.lines.length - 1;
  for (let index = 0; index < lineQuestions; index += 1) {
    result.push(choiceQuestion(
      `“${poem.lines[index]}”的下一句是？`, poem.lines[index + 1], ALL_POEM_LINES,
      `${prefix}-line-${index + 1}`, "poem-recitation", base
    ));
  }
  result.push(choiceQuestion(
    `《${poem.title}》的作者是？`, poem.author, ALL_POEM_AUTHORS,
    `${prefix}-author`, "poem-author", base
  ));
  return result;
}

interface QuestionBase {
  grade: number;
  unit: CurriculumUnit;
  language: string;
  subject: "english" | "mathematics" | "chinese";
  difficulty: string;
}

function choiceQuestion(
  prompt: string,
  correct: string,
  pool: readonly string[],
  id: string,
  kind: string,
  base: QuestionBase
): EditableQuestion {
  return choiceFromOptions(
    prompt, optionsWithCorrect(correct, pool, stableHash(id)), correct, id, kind, base
  );
}

function choiceFromOptions(
  prompt: string,
  options: string[],
  correct: string,
  id: string,
  kind: string,
  base: QuestionBase
): EditableQuestion {
  return {
    ...emptyQuestion("single_choice"),
    id,
    prompt,
    options,
    answerIndex: options.indexOf(correct),
    subject: base.subject,
    grade: base.grade,
    difficulty: base.difficulty,
    language: base.language,
    tags: questionTags(base, kind)
  };
}

function shortAnswerQuestion(
  prompt: string,
  answer: string,
  id: string,
  kind: string,
  base: QuestionBase
): EditableQuestion {
  return {
    ...emptyQuestion("short_answer"),
    id,
    prompt,
    acceptedAnswers: answer,
    subject: base.subject,
    grade: base.grade,
    difficulty: base.difficulty,
    language: base.language,
    tags: questionTags(base, kind)
  };
}

function questionTags(base: QuestionBase, kind: string): string {
  return [
    "pep",
    `grade-${base.grade}`,
    `unit-${base.unit}`,
    base.subject,
    kind
  ].join(", ");
}

function optionsWithCorrect(correct: string, pool: readonly string[], seed: number): string[] {
  const unique = [...new Set(pool.filter((value) => value && value !== correct))];
  if (unique.length < 3) throw new Error("The curriculum question pool is too small");
  const distractors = shuffle(unique, seed).slice(0, 3);
  return shuffle([correct, ...distractors], seed ^ 0x9e3779b9);
}

function numericOptions(answer: string, seed: number): string[] {
  const value = Number(answer);
  if (!Number.isFinite(value)) throw new Error(`Curriculum answer is not numeric: ${answer}`);
  const candidates = [1, -1, 2, -2, 0.1, -0.1, 0.5, -0.5, 10, -10, 5, -5]
    .map((offset) => value + offset)
    .filter((candidate) => candidate >= 0)
    .map(formatNumber)
    .filter((candidate) => candidate !== formatNumber(value));
  const unique = [...new Set(candidates)];
  if (unique.length < 3) throw new Error(`Cannot build numeric choices for ${answer}`);
  const distractors = shuffle(unique, seed).slice(0, 3);
  return shuffle([formatNumber(value), ...distractors], seed ^ 0x85ebca6b);
}

function formatNumber(value: number): string {
  return Number(value.toFixed(4)).toString();
}

function difficultyFor(grade: number): string {
  if (grade <= 2) return "easy";
  if (grade <= 4) return "medium";
  return "hard";
}

function stableHash(value: string): number {
  let hash = 2166136261;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

function shuffle<T>(items: readonly T[], seed: number): T[] {
  const result = [...items];
  let state = seed || 1;
  for (let index = result.length - 1; index > 0; index -= 1) {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    const swap = state % (index + 1);
    [result[index], result[swap]] = [result[swap], result[index]];
  }
  return result;
}
