import { describe, expect, it } from "vitest";
import {
  CURRICULUM_GRADES,
  CURRICULUM_UNITS,
  curriculumQuestionCounts,
  curriculumQuestions,
  type CurriculumSelection
} from "./curriculum";
import {
  buildQuestionPayload,
  bytesToBase64,
  signQuizPack,
  verifyAndParseEnvelope,
  type QuizSigningIdentity
} from "./quiz";

function selection(overrides: Partial<CurriculumSelection> = {}): CurriculumSelection {
  return { edition: "pep", grade: 3, unit: 1, subject: "all", ...overrides };
}

async function signingIdentity(): Promise<QuizSigningIdentity> {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"]
  );
  const publicDer = new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey));
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", publicDer));
  return {
    privateKey: pair.privateKey,
    publicKeyBase64: bytesToBase64(publicDer),
    keyId: [...digest].map((value) => value.toString(16).padStart(2, "0")).join(""),
    createdAt: "2026-01-01T00:00:00.000Z"
  };
}

describe("PEP curriculum question generation", () => {
  it("builds valid English, mathematics and Chinese questions", () => {
    const questions = curriculumQuestions(selection());
    expect(questions.length).toBeGreaterThan(35);
    expect(new Set(questions.map((question) => question.id)).size).toBe(questions.length);
    questions.forEach((question, index) => {
      expect(question.subject).toMatch(/^(english|mathematics|chinese)$/);
      expect(question.grade).toBe(3);
      expect(question.tags).toContain("pep");
      expect(question.tags).toContain("unit-1");
      expect(() => buildQuestionPayload(question, index)).not.toThrow();
    });
  });

  it("reports a subject split that matches the generated pack", () => {
    const counts = curriculumQuestionCounts(selection());
    expect(counts).toEqual({ english: 18, mathematics: 9, chinese: 16 });
  });

  it("filters a single subject without changing its question metadata", () => {
    const math = curriculumQuestions(selection({ subject: "mathematics" }));
    expect(math.length).toBe(9);
    expect(math.every((question) => question.subject === "mathematics")).toBe(true);
    expect(math.some((question) => question.tags.includes("calculation"))).toBe(true);
    expect(math.some((question) => question.tags.includes("application"))).toBe(true);
    expect(math.some((question) => question.type === "short_answer")).toBe(true);
  });

  it("covers every supported grade and unit with unique, valid question ids", () => {
    for (const grade of CURRICULUM_GRADES) {
      for (const unit of CURRICULUM_UNITS) {
        const questions = curriculumQuestions(selection({ grade, unit }));
        expect(questions.length).toBeGreaterThan(30);
        expect(new Set(questions.map((question) => question.id)).size).toBe(questions.length);
        questions.forEach((question, index) => {
          expect(() => buildQuestionPayload(question, index)).not.toThrow();
        });
      }
    }
  });

  it("builds one complete PEP library within the signed pack limit", () => {
    const questions = curriculumQuestions(selection({ grade: "all", unit: "all" }));
    expect(questions.length).toBeGreaterThan(480);
    expect(questions.length).toBeLessThanOrEqual(500);
    expect(new Set(questions.map((question) => question.id)).size).toBe(questions.length);
    questions.forEach((question, index) => {
      expect(["1", "2", "3", "4", "5", "6"]).toContain(String(question.grade));
      expect(question.tags).toMatch(/unit-[12]/);
      expect(() => buildQuestionPayload(question, index)).not.toThrow();
    });
  });

  it("signs and verifies the complete library as one deliverable pack", async () => {
    const key = await signingIdentity();
    const questions = curriculumQuestions(selection({ grade: "all", unit: "all" }));
    const pack = await signQuizPack(1, questions, key);
    expect(pack.questionCount).toBe(questions.length);
    expect(pack.envelope.length).toBeLessThan(8_000_000);
    const parsed = await verifyAndParseEnvelope(pack.envelope, key);
    expect(parsed.questions.length).toBe(questions.length);
    expect(parsed.version).toBe(1);
  });
});
