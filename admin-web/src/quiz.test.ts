import { describe, expect, it } from "vitest";
import {
  base64ToBytes,
  buildQuestionPayload,
  bytesToBase64,
  emptyQuestion,
  planQuizDelta,
  signQuizPack,
  signQuizDelta,
  verifyAndParseEnvelope,
  type EditableQuestion,
  type QuizSigningIdentity
} from "./quiz";

function question(overrides: Partial<EditableQuestion> = {}): EditableQuestion {
  return {
    ...emptyQuestion("single_choice"),
    id: "q-1",
    prompt: "What is 2 + 2?",
    options: ["3", "4"],
    answerIndex: 1,
    subject: "Math",
    grade: 3,
    difficulty: "easy",
    language: "en",
    tags: "math, addition",
    ...overrides
  };
}

async function identity(): Promise<QuizSigningIdentity> {
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

describe("quiz payloads", () => {
  it("builds each supported question type", () => {
    expect(buildQuestionPayload(question(), 0)).toMatchObject({
      id: "q-1",
      type: "single_choice",
      options: ["3", "4"],
      answer_index: 1,
      difficulty: "easy",
      language: "en",
      tags: ["math", "addition"]
    });

    expect(buildQuestionPayload(question({
      type: "multiple_choice",
      options: ["A", "B", "C"],
      answerIndices: [0, 2]
    }), 1)).toMatchObject({ answer_indices: [0, 2] });

    expect(buildQuestionPayload(question({ type: "true_false", booleanAnswer: false }), 2))
      .toMatchObject({ answer: false });

    expect(buildQuestionPayload(question({
      type: "short_answer",
      acceptedAnswers: "four\n 4 "
    }), 3)).toMatchObject({ accepted_answers: ["four", "4"] });
  });

  it("rejects malformed questions", () => {
    expect(() => buildQuestionPayload(question({ prompt: "" }), 0)).toThrow(/prompt/);
    expect(() => buildQuestionPayload(question({ grade: 13 }), 0)).toThrow(/grade/);
    expect(() => buildQuestionPayload(question({ difficulty: "extreme" }), 0)).toThrow(/difficulty/);
    expect(() => buildQuestionPayload(question({ language: "EN_US" }), 0)).toThrow(/language/);
    expect(() => buildQuestionPayload(question({ options: ["same", "same"] }), 0)).toThrow(/distinct/);
    expect(() => buildQuestionPayload(question({
      type: "multiple_choice",
      options: ["A", "B"],
      answerIndices: [0, 1]
    }), 0)).toThrow(/not all/);
    expect(() => buildQuestionPayload(question({
      type: "short_answer",
      acceptedAnswers: "four\nFour"
    }), 0)).toThrow(/distinct after normalization/);
  });

  it("round-trips base64 data", () => {
    const bytes = new Uint8Array([0, 1, 2, 127, 128, 255]);
    expect([...base64ToBytes(bytesToBase64(bytes))]).toEqual([...bytes]);
    expect(() => base64ToBytes("not base64!")).toThrow(/valid base64/);
  });
});

describe("signed quiz packs", () => {
  it("signs, verifies, and parses a pack", async () => {
    const key = await identity();
    const pack = await signQuizPack(7, [question()], key, "2026-01-02T03:04:05.000Z");
    expect(pack.keyId).toBe(key.keyId);
    expect(pack.questionCount).toBe(1);

    const parsed = await verifyAndParseEnvelope(pack.envelope, key);
    expect(parsed.version).toBe(7);
    expect(parsed.publishedAt).toBe("2026-01-02T03:04:05.000Z");
    expect(parsed.questions[0].prompt).toBe("What is 2 + 2?");
    expect(parsed.questions[0].options).toEqual(["3", "4"]);
  });

  it("detects tampering and signing-key mismatch", async () => {
    const key = await identity();
    const other = await identity();
    const pack = await signQuizPack(1, [question()], key);
    const envelope = JSON.parse(pack.envelope) as Record<string, unknown>;
    const tampered = JSON.stringify({
      ...envelope,
      payload: String(envelope.payload).replace("What is 2 + 2?", "What is 2 + 3?")
    });
    await expect(verifyAndParseEnvelope(tampered, key)).rejects.toThrow(/signature/);
    await expect(verifyAndParseEnvelope(pack.envelope, other)).rejects.toThrow(/different key/);
  });

  it("plans and signs a delta with put and remove operations", async () => {
    const first = question({ id: "q-1" });
    const second = question({ id: "q-2", prompt: "What is 3 + 3?" });
    const changed = question({ id: "q-1", prompt: "What is 4 + 4?" });
    const added = question({ id: "q-3", prompt: "What is 5 + 5?" });

    const plan = planQuizDelta(1, [first, second], 2, [changed, added], "2026-01-03T00:00:00.000Z");
    expect(plan.operations).toHaveLength(3);
    expect(plan.operations.filter((operation) => operation.op === "put")).toHaveLength(2);
    expect(plan.operations.filter((operation) => operation.op === "remove")).toEqual([
      { op: "remove", id: "q-2" }
    ]);

    const key = await identity();
    const signed = await signQuizDelta(1, [first, second], 2, [changed, added], key,
      "2026-01-03T00:00:00.000Z");
    expect(signed.baseVersion).toBe(1);
    expect(signed.version).toBe(2);
    expect(signed.operationCount).toBe(3);
    expect(JSON.parse(signed.envelope).key_id).toBe(key.keyId);
  });
});
