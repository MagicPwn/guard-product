export type QuizQuestionType = "single_choice" | "multiple_choice" | "true_false" | "short_answer";

export interface EditableQuestion {
  id: string;
  type: QuizQuestionType;
  prompt: string;
  options: string[];
  answerIndex: number;
  answerIndices: number[];
  booleanAnswer: boolean;
  acceptedAnswers: string;
  subject: string;
  grade: number;
  difficulty: string;
  language: string;
  tags: string;
  imageBase64: string;
  imageMime: string;
  imageName: string;
}

export interface QuizSigningIdentity {
  privateKey: CryptoKey;
  publicKeyBase64: string;
  keyId: string;
  createdAt: string;
}

export interface SignedQuizPack {
  envelope: string;
  payload: string;
  keyId: string;
  publicKeyBase64: string;
  version: number;
  publishedAt: string;
  questionCount: number;
}

export interface SignedQuizDelta {
  envelope: string;
  payload: string;
  keyId: string;
  version: number;
  baseVersion: number;
  publishedAt: string;
  operationCount: number;
  byteLength: number;
}

export interface PreparedKeyRotation {
  identity: QuizSigningIdentity;
  transition: string;
}

export interface ParsedQuizPack {
  version: number;
  publishedAt: string;
  keyId: string;
  questions: EditableQuestion[];
}

interface StoredIdentity extends QuizSigningIdentity {
  id: "active";
}

export interface PayloadQuestion {
  id: string;
  type: QuizQuestionType;
  prompt: string;
  subject: string;
  grade: number;
  difficulty: string;
  language: string;
  tags: string[];
  options?: string[];
  answer_index?: number;
  answer_indices?: number[];
  answer?: boolean;
  accepted_answers?: string[];
  image_base64?: string;
  image_mime?: string;
}

export interface QuizDeltaOperation {
  op: "put" | "remove";
  question?: PayloadQuestion;
  id?: string;
}

export interface QuizDeltaPlan {
  operations: QuizDeltaOperation[];
  payload: string;
  byteLength: number;
}

const DB_NAME = "guard-quiz-signing";
const STORE_NAME = "keys";
const ACTIVE_ID = "active";
const encoder = new TextEncoder();
const languagePattern = /^[a-z]{2,8}(?:-[a-z0-9]{1,8})*$/;
const whitespace = /\s+/g;

export function emptyQuestion(type: QuizQuestionType = "single_choice"): EditableQuestion {
  return {
    id: `question-${crypto.randomUUID()}`,
    type,
    prompt: "",
    options: type === "single_choice" || type === "multiple_choice" ? ["", ""] : [],
    answerIndex: 0,
    answerIndices: type === "multiple_choice" ? [0, 1] : [],
    booleanAnswer: true,
    acceptedAnswers: "",
    subject: "",
    grade: 0,
    difficulty: "",
    language: "",
    tags: "",
    imageBase64: "",
    imageMime: "",
    imageName: ""
  };
}

export function changeQuestionType(question: EditableQuestion, type: QuizQuestionType): EditableQuestion {
  const fresh = emptyQuestion(type);
  return {
    ...question,
    type,
    options: fresh.options,
    answerIndex: 0,
    answerIndices: fresh.answerIndices,
    booleanAnswer: true,
    acceptedAnswers: ""
  };
}

export async function loadSigningIdentity(): Promise<QuizSigningIdentity | null> {
  const record = await withStore("readonly", (store) => store.get(ACTIVE_ID)) as StoredIdentity | undefined;
  if (!record) return null;
  const expectedKeyId = await sha256Hex(base64ToBytes(record.publicKeyBase64));
  if (expectedKeyId !== record.keyId || !(record.privateKey instanceof CryptoKey)) {
    throw new Error("The saved quiz signing key is corrupt and cannot be used");
  }
  return record;
}

export async function createSigningIdentity(): Promise<QuizSigningIdentity> {
  const identity = await generateSigningIdentity();
  const stored: StoredIdentity = { id: ACTIVE_ID, ...identity };
  await withStore("readwrite", (store) => store.put(stored));
  return identity;
}

async function generateSigningIdentity(): Promise<QuizSigningIdentity> {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"]
  );
  const publicDer = new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey));
  const privateJwk = await crypto.subtle.exportKey("jwk", pair.privateKey);
  const privateKey = await crypto.subtle.importKey(
    "jwk",
    privateJwk,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"]
  );
  const identity: QuizSigningIdentity = {
    privateKey,
    publicKeyBase64: bytesToBase64(publicDer),
    keyId: await sha256Hex(publicDer),
    createdAt: new Date().toISOString()
  };
  return identity;
}

export async function signQuizPack(
  version: number,
  questions: EditableQuestion[],
  identity: QuizSigningIdentity,
  publishedAt: string = new Date().toISOString()
): Promise<SignedQuizPack> {
  if (!Number.isSafeInteger(version) || version < 1 || version > 9_999_999_999) {
    throw new Error("Pack version must be an integer between 1 and 9999999999");
  }
  if (questions.length < 1 || questions.length > 500) {
    throw new Error("A quiz pack needs 1 to 500 questions");
  }
  const ids = new Set<string>();
  let imageBytes = 0;
  const payloadQuestions = questions.map((question, index) => {
    const built = buildQuestionPayload(question, index);
    if (ids.has(built.id)) throw new Error(`Question ${index + 1}: id ${built.id} is duplicated`);
    ids.add(built.id);
    if (built.image_base64) imageBytes += base64ToBytes(built.image_base64).length;
    if (imageBytes > 5 * 1024 * 1024) throw new Error("Embedded quiz images exceed 5 MiB in total");
    return built;
  });
  const payload = JSON.stringify({
    schema: 1,
    version,
    published_at: publishedAt,
    questions: payloadQuestions
  });
  if (encoder.encode(payload).byteLength > 6_000_000) {
    throw new Error("The signed payload exceeds the 6 MiB server limit");
  }
  const publicKey = await importPublicKey(identity.publicKeyBase64);
  const payloadBytes = encoder.encode(payload);
  const rawSignature = new Uint8Array(await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    identity.privateKey,
    payloadBytes
  ));
  const valid = await crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    publicKey,
    exactBuffer(rawSignature),
    payloadBytes
  );
  if (!valid) throw new Error("The browser produced a quiz signature that does not verify");
  const envelope = JSON.stringify({
    schema: 1,
    key_id: identity.keyId,
    payload,
    signature: bytesToBase64(rawEcdsaToDer(rawSignature))
  });
  if (envelope.length > 8_000_000) throw new Error("The signed quiz envelope is too large");
  return {
    envelope,
    payload,
    keyId: identity.keyId,
    publicKeyBase64: identity.publicKeyBase64,
    version,
    publishedAt,
    questionCount: questions.length
  };
}

export function planQuizDelta(
  baseVersion: number,
  baseQuestions: EditableQuestion[],
  targetVersion: number,
  targetQuestions: EditableQuestion[],
  publishedAt: string = new Date().toISOString()
): QuizDeltaPlan {
  if (!Number.isSafeInteger(targetVersion) || targetVersion < 1 || targetVersion > 9_999_999_999) {
    throw new Error("Pack version must be an integer between 1 and 9999999999");
  }
  if (targetVersion <= baseVersion) {
    throw new Error("The delta target version must be newer than its base version");
  }
  const baseById = new Map<string, PayloadQuestion>();
  baseQuestions.forEach((question, index) => {
    const built = buildQuestionPayload(question, index);
    baseById.set(built.id, built);
  });
  const operations: QuizDeltaOperation[] = [];
  const targetIds = new Set<string>();
  targetQuestions.forEach((question, index) => {
    const built = buildQuestionPayload(question, index);
    if (targetIds.has(built.id)) throw new Error(`Question ${index + 1}: id ${built.id} is duplicated`);
    targetIds.add(built.id);
    const previous = baseById.get(built.id);
    if (!previous || JSON.stringify(previous) !== JSON.stringify(built)) {
      operations.push({ op: "put", question: built });
    }
  });
  for (const id of baseById.keys()) {
    if (!targetIds.has(id)) operations.push({ op: "remove", id });
  }
  if (operations.length === 0) throw new Error("The draft has no changes compared with its base pack");
  if (operations.length > 500) throw new Error("A delta supports at most 500 operations");
  const payload = JSON.stringify({
    schema: 2,
    version: targetVersion,
    base_version: baseVersion,
    published_at: publishedAt,
    operations
  });
  const byteLength = encoder.encode(payload).byteLength;
  if (byteLength > 6_000_000) throw new Error("The delta payload exceeds the 6 MiB server limit");
  return { operations, payload, byteLength };
}

export async function signQuizDelta(
  baseVersion: number,
  baseQuestions: EditableQuestion[],
  targetVersion: number,
  targetQuestions: EditableQuestion[],
  identity: QuizSigningIdentity,
  publishedAt: string = new Date().toISOString()
): Promise<SignedQuizDelta> {
  const plan = planQuizDelta(baseVersion, baseQuestions, targetVersion, targetQuestions, publishedAt);
  const publicKey = await importPublicKey(identity.publicKeyBase64);
  const payloadBytes = encoder.encode(plan.payload);
  const rawSignature = new Uint8Array(await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    identity.privateKey,
    payloadBytes
  ));
  const valid = await crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    publicKey,
    exactBuffer(rawSignature),
    payloadBytes
  );
  if (!valid) throw new Error("The browser produced a delta signature that does not verify");
  const envelope = JSON.stringify({
    schema: 1,
    key_id: identity.keyId,
    payload: plan.payload,
    signature: bytesToBase64(rawEcdsaToDer(rawSignature))
  });
  if (envelope.length > 8_000_000) throw new Error("The signed quiz delta is too large");
  return {
    envelope,
    payload: plan.payload,
    keyId: identity.keyId,
    version: targetVersion,
    baseVersion,
    publishedAt,
    operationCount: plan.operations.length,
    byteLength: plan.byteLength
  };
}

export async function prepareSigningKeyRotation(
  current: QuizSigningIdentity
): Promise<PreparedKeyRotation> {
  const identity = await generateSigningIdentity();
  const notBefore = Date.now();
  const message = signingKeyTransitionMessage(
    current.keyId, identity.keyId, identity.publicKeyBase64, notBefore);
  const rawSignature = new Uint8Array(await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    current.privateKey,
    encoder.encode(message)
  ));
  const publicKey = await importPublicKey(current.publicKeyBase64);
  const valid = await crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    publicKey,
    exactBuffer(rawSignature),
    encoder.encode(message)
  );
  if (!valid) throw new Error("The old key could not sign the key transition");
  const transition = JSON.stringify({
    schema: 1,
    type: "quiz-key-transition",
    old_key_id: current.keyId,
    new_key_id: identity.keyId,
    new_public_key: identity.publicKeyBase64,
    not_before: notBefore,
    signature: bytesToBase64(rawEcdsaToDer(rawSignature))
  });
  if (transition.length > 8_000) throw new Error("The signed key transition is too large");
  return { identity, transition };
}

export async function commitSigningKeyRotation(
  current: QuizSigningIdentity,
  prepared: PreparedKeyRotation
): Promise<void> {
  await withStore("readwrite", (store) => store.put({
    id: `retired-${current.keyId}`,
    ...current,
    retiredAt: new Date().toISOString()
  }));
  await withStore("readwrite", (store) => store.put({
    id: ACTIVE_ID,
    ...prepared.identity
  } satisfies StoredIdentity));
}

function signingKeyTransitionMessage(
  oldKeyId: string,
  newKeyId: string,
  newPublicKey: string,
  notBefore: number
): string {
  return `guard-quiz-key-transition-v1\n${oldKeyId}\n${newKeyId}\n${newPublicKey}\n${notBefore}`;
}

export async function verifyAndParseEnvelope(
  envelopeText: string,
  identity: QuizSigningIdentity
): Promise<ParsedQuizPack> {
  if (envelopeText.length < 1 || envelopeText.length > 8_000_000) {
    throw new Error("Quiz envelope is empty or larger than 8 MiB");
  }
  let envelope: unknown;
  try {
    envelope = JSON.parse(envelopeText);
  } catch {
    throw new Error("Quiz envelope is not valid JSON");
  }
  if (!isRecord(envelope) || envelope.schema !== 1 || typeof envelope.key_id !== "string"
      || typeof envelope.payload !== "string" || typeof envelope.signature !== "string") {
    throw new Error("Quiz envelope does not match schema 1");
  }
  if (envelope.key_id.toLowerCase() !== identity.keyId.toLowerCase()) {
    throw new Error("This quiz pack was signed by a different key; it cannot be edited with the current key");
  }
  const key = await importPublicKey(identity.publicKeyBase64);
  let rawSignature: Uint8Array;
  try {
    rawSignature = derEcdsaToRaw(base64ToBytes(envelope.signature), 32);
  } catch {
    throw new Error("Quiz signature is not a valid ECDSA DER value");
  }
  const valid = await crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    exactBuffer(rawSignature),
    encoder.encode(envelope.payload)
  );
  if (!valid) throw new Error("Quiz signature does not match the payload");

  let payload: unknown;
  try {
    payload = JSON.parse(envelope.payload);
  } catch {
    throw new Error("Quiz payload is not valid JSON");
  }
  if (!isRecord(payload) || payload.schema !== 1 || !Number.isSafeInteger(payload.version)
      || typeof payload.published_at !== "string" || !Array.isArray(payload.questions)) {
    throw new Error("Quiz payload does not match schema 1");
  }
  if (payload.questions.length < 1 || payload.questions.length > 500) {
    throw new Error("A quiz pack needs 1 to 500 questions");
  }
  const questions = payload.questions.map((value, index) => editableFromPayload(value, index));
  questions.forEach((question, index) => buildQuestionPayload(question, index));
  return {
    version: payload.version as number,
    publishedAt: payload.published_at,
    keyId: envelope.key_id,
    questions
  };
}

export function buildQuestionPayload(question: EditableQuestion, index: number): PayloadQuestion {
  const prefix = `Question ${index + 1}`;
  const id = requiredText(question.id, `${prefix} id`, 128);
  const prompt = requiredText(question.prompt, `${prefix} prompt`, 2_000);
  const subject = optionalText(question.subject, `${prefix} subject`, 64);
  if (!Number.isInteger(question.grade) || question.grade < 0 || question.grade > 12) {
    throw new Error(`${prefix} grade must be an integer from 0 to 12`);
  }
  const difficulty = question.difficulty.trim().toLowerCase();
  if (difficulty && !["easy", "medium", "hard"].includes(difficulty)) {
    throw new Error(`${prefix} difficulty must be easy, medium, or hard`);
  }
  const language = question.language.trim().toLowerCase();
  if (language && !languagePattern.test(language)) {
    throw new Error(`${prefix} language must be a BCP-47 tag such as en or zh-CN`);
  }
  const tags = question.tags.split(",").map((tag) => tag.trim().toLowerCase()).filter(Boolean);
  if (tags.length > 32 || tags.some((tag) => tag.length > 48)) {
    throw new Error(`${prefix} supports at most 32 tags of 48 characters each`);
  }
  const base: PayloadQuestion = {
    id,
    type: question.type,
    prompt,
    subject,
    grade: question.grade,
    difficulty,
    language,
    tags: [...new Set(tags)]
  };

  if (question.type === "single_choice" || question.type === "multiple_choice") {
    const options = question.options.map((option) => requiredText(option, `${prefix} option`, 300));
    if (options.length < 2 || options.length > 8) {
      throw new Error(`${prefix} needs 2 to 8 options`);
    }
    if (new Set(options).size !== options.length) throw new Error(`${prefix} options must be distinct`);
    base.options = options;
    if (question.type === "single_choice") {
      if (!Number.isInteger(question.answerIndex) || question.answerIndex < 0
          || question.answerIndex >= options.length) {
        throw new Error(`${prefix} must mark exactly one valid answer`);
      }
      base.answer_index = question.answerIndex;
    } else {
      const answers = [...new Set(question.answerIndices)].sort((left, right) => left - right);
      if (answers.length < 2 || answers.length >= options.length
          || answers.some((answer) => !Number.isInteger(answer) || answer < 0 || answer >= options.length)) {
        throw new Error(`${prefix} must mark two or more, but not all, valid answers`);
      }
      base.answer_indices = answers;
    }
  } else if (question.type === "true_false") {
    base.answer = question.booleanAnswer;
  } else {
    const answers = question.acceptedAnswers.split(/\r?\n/).map((answer) => answer.trim()).filter(Boolean);
    if (answers.length < 1 || answers.length > 8) {
      throw new Error(`${prefix} needs 1 to 8 accepted answers, one per line`);
    }
    const normalized = new Set(answers.map(normalizeText));
    if (normalized.size !== answers.length) {
      throw new Error(`${prefix} accepted answers must be distinct after normalization`);
    }
    answers.forEach((answer) => requiredText(answer, `${prefix} accepted answer`, 300));
    base.accepted_answers = answers;
  }

  if (question.imageBase64 || question.imageMime) {
    if (!question.imageBase64 || !question.imageMime) {
      throw new Error(`${prefix} image data and MIME type must be supplied together`);
    }
    const image = base64ToBytes(question.imageBase64);
    if (image.length < 4 || image.length > 512 * 1024) {
      throw new Error(`${prefix} image must be 4 bytes to 512 KiB`);
    }
    const mime = detectImageMime(image);
    if (!mime || mime !== question.imageMime) {
      throw new Error(`${prefix} image must be JPEG or PNG with a matching MIME type`);
    }
    base.image_base64 = question.imageBase64;
    base.image_mime = question.imageMime;
  }
  return base;
}

export async function imageFileToQuestion(file: File): Promise<{ base64: string; mime: string; name: string }> {
  if (file.size < 4 || file.size > 512 * 1024) throw new Error("Question images must be 4 bytes to 512 KiB");
  const bytes = new Uint8Array(await file.arrayBuffer());
  const mime = detectImageMime(bytes);
  if (!mime || (file.type && file.type !== mime)) {
    throw new Error("Question images must be JPEG or PNG with a matching MIME type");
  }
  return { base64: bytesToBase64(bytes), mime, name: file.name };
}

export function downloadText(filename: string, content: string, type: string) {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1_000);
}

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunk = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunk) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunk));
  }
  return btoa(binary);
}

export function base64ToBytes(value: string): Uint8Array {
  const clean = value.replace(/\s+/g, "");
  let binary: string;
  try {
    binary = atob(clean);
  } catch {
    throw new Error("Value is not valid base64");
  }
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

async function importPublicKey(publicKeyBase64: string): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "spki",
    exactBuffer(base64ToBytes(publicKeyBase64)),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"]
  );
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", exactBuffer(bytes)));
  return [...digest].map((value) => value.toString(16).padStart(2, "0")).join("");
}

function editableFromPayload(value: unknown, index: number): EditableQuestion {
  if (!isRecord(value) || typeof value.type !== "string") {
    throw new Error(`Question ${index + 1} is not a valid object`);
  }
  const type = value.type;
  if (!["single_choice", "multiple_choice", "true_false", "short_answer"].includes(type)) {
    throw new Error(`Question ${index + 1} has an unsupported type`);
  }
  const options = Array.isArray(value.options) ? value.options.map(String) : [];
  const answerIndices = Array.isArray(value.answer_indices)
    ? value.answer_indices.filter((answer): answer is number => typeof answer === "number") : [];
  const accepted = Array.isArray(value.accepted_answers)
    ? value.accepted_answers.filter((answer): answer is string => typeof answer === "string") : [];
  const tags = Array.isArray(value.tags) ? value.tags.filter((tag): tag is string => typeof tag === "string") : [];
  return {
    id: typeof value.id === "string" ? value.id : "",
    type: type as QuizQuestionType,
    prompt: typeof value.prompt === "string" ? value.prompt : "",
    options,
    answerIndex: typeof value.answer_index === "number" ? value.answer_index : 0,
    answerIndices,
    booleanAnswer: typeof value.answer === "boolean" ? value.answer : true,
    acceptedAnswers: accepted.join("\n"),
    subject: typeof value.subject === "string" ? value.subject : "",
    grade: typeof value.grade === "number" ? value.grade : 0,
    difficulty: typeof value.difficulty === "string" ? value.difficulty : "",
    language: typeof value.language === "string" ? value.language : "",
    tags: tags.join(", "),
    imageBase64: typeof value.image_base64 === "string" ? value.image_base64 : "",
    imageMime: typeof value.image_mime === "string" ? value.image_mime : "",
    imageName: typeof value.image_base64 === "string" ? "Embedded image" : ""
  };
}

function requiredText(value: string, label: string, maxLength: number): string {
  const text = value.trim();
  if (!text || text.length > maxLength) throw new Error(`${label} must be 1 to ${maxLength} characters`);
  return text;
}

function optionalText(value: string, label: string, maxLength: number): string {
  const text = value.trim();
  if (text.length > maxLength || /[\u0000-\u001f\u007f]/u.test(text)) {
    throw new Error(`${label} is invalid or too long`);
  }
  return text;
}

function normalizeText(value: string): string {
  return value.normalize("NFKC").trim().replace(whitespace, " ").toLowerCase();
}

function detectImageMime(bytes: Uint8Array): "image/jpeg" | "image/png" | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return "image/jpeg";
  }
  const png = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (bytes.length >= png.length && png.every((value, index) => bytes[index] === value)) {
    return "image/png";
  }
  return null;
}

function rawEcdsaToDer(raw: Uint8Array): Uint8Array {
  if (raw.length !== 64) throw new Error("Unexpected ECDSA signature size");
  const r = derInteger(raw.subarray(0, 32));
  const s = derInteger(raw.subarray(32));
  const body = concatBytes(r, s);
  if (body.length >= 128) throw new Error("ECDSA signature is too large");
  return concatBytes(new Uint8Array([0x30, body.length]), body);
}

function derEcdsaToRaw(der: Uint8Array, integerBytes: number): Uint8Array {
  let offset = 0;
  if (der[offset++] !== 0x30) throw new Error("Missing DER sequence");
  const length = readDerLength(der, offset);
  offset = length.offset;
  if (offset + length.value !== der.length) throw new Error("Invalid DER sequence length");
  const first = readDerInteger(der, offset, integerBytes);
  offset = first.offset;
  const second = readDerInteger(der, offset, integerBytes);
  if (second.offset !== der.length) throw new Error("Unexpected DER trailing data");
  return concatBytes(first.value, second.value);
}

function readDerInteger(der: Uint8Array, offset: number, size: number): { value: Uint8Array; offset: number } {
  if (der[offset++] !== 0x02) throw new Error("Missing DER integer");
  const length = readDerLength(der, offset);
  offset = length.offset;
  if (length.value < 1 || length.value > size + 1 || offset + length.value > der.length) {
    throw new Error("Invalid DER integer length");
  }
  let value = der.subarray(offset, offset + length.value);
  offset += length.value;
  while (value.length > 1 && value[0] === 0) value = value.subarray(1);
  if (value.length > size) throw new Error("DER integer is too large");
  const padded = new Uint8Array(size);
  padded.set(value, size - value.length);
  return { value: padded, offset };
}

function readDerLength(bytes: Uint8Array, offset: number): { value: number; offset: number } {
  if (offset >= bytes.length) throw new Error("Missing DER length");
  const first = bytes[offset++];
  if ((first & 0x80) === 0) return { value: first, offset };
  const count = first & 0x7f;
  if (count < 1 || count > 2 || offset + count > bytes.length) throw new Error("Invalid DER length");
  let value = 0;
  for (let index = 0; index < count; index += 1) value = value * 256 + bytes[offset++];
  return { value, offset };
}

function derInteger(bytes: Uint8Array): Uint8Array {
  let offset = 0;
  while (offset < bytes.length - 1 && bytes[offset] === 0) offset += 1;
  let value = bytes.subarray(offset);
  if ((value[0] & 0x80) !== 0) value = concatBytes(new Uint8Array([0]), value);
  return concatBytes(new Uint8Array([0x02, value.length]), value);
}

function concatBytes(...parts: Uint8Array[]): Uint8Array {
  const result = new Uint8Array(parts.reduce((total, part) => total + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.length;
  }
  return result;
}

function exactBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function withStore<T>(
  mode: IDBTransactionMode,
  action: (store: IDBObjectStore) => IDBRequest<T>
): Promise<T> {
  return new Promise((resolve, reject) => {
    let result: T | undefined;
    let requestSucceeded = false;
    let settled = false;
    const fail = (error: unknown): void => {
      if (settled) return;
      settled = true;
      reject(error instanceof Error ? error : new Error("Quiz signing key operation failed"));
    };
    const open = indexedDB.open(DB_NAME, 1);
    open.onupgradeneeded = () => {
      if (!open.result.objectStoreNames.contains(STORE_NAME)) {
        open.result.createObjectStore(STORE_NAME, { keyPath: "id" });
      }
    };
    open.onerror = () => fail(open.error ?? new Error("Cannot open the quiz signing key store"));
    open.onsuccess = () => {
      const database = open.result;
      const transaction = database.transaction(STORE_NAME, mode);
      let request: IDBRequest<T>;
      try {
        request = action(transaction.objectStore(STORE_NAME));
      } catch (error) {
        transaction.abort();
        database.close();
        fail(error);
        return;
      }
      request.onerror = () => fail(request.error ?? new Error("Quiz signing key operation failed"));
      request.onsuccess = () => {
        result = request.result;
        requestSucceeded = true;
      };
      transaction.oncomplete = () => {
        database.close();
        if (!settled && requestSucceeded) {
          settled = true;
          resolve(result as T);
        }
      };
      transaction.onerror = () => fail(transaction.error ?? new Error("Quiz signing key transaction failed"));
      transaction.onabort = () => fail(transaction.error ?? new Error("Quiz signing key transaction was aborted"));
    };
  });
}
