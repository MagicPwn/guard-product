import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent } from "react";
import {
  api,
  type QuizKeyTransition,
  type QuizPackSummary,
  type QuizUpstreamConfig,
  type QuizUpstreamEvent
} from "../api";
import {
  changeQuestionType,
  commitSigningKeyRotation,
  createSigningIdentity,
  downloadText,
  emptyQuestion,
  imageFileToQuestion,
  loadSigningIdentity,
  planQuizDelta,
  prepareSigningKeyRotation,
  signQuizDelta,
  signQuizPack,
  verifyAndParseEnvelope,
  type EditableQuestion,
  type ParsedQuizPack,
  type QuizQuestionType,
  type QuizSigningIdentity,
  type SignedQuizPack
} from "../quiz";
import {
  CURRICULUM_EDITIONS,
  CURRICULUM_GRADES,
  CURRICULUM_UNITS,
  curriculumQuestions,
  type CurriculumEdition,
  type CurriculumGrade,
  type CurriculumGradeFilter,
  type CurriculumSelection,
  type CurriculumSubject,
  type CurriculumUnit,
  type CurriculumUnitFilter
} from "../curriculum";
import { errorText, useI18n } from "../i18n";

const TYPE_LABELS: Record<QuizQuestionType, string> = {
  single_choice: "Single choice",
  multiple_choice: "Multiple choice",
  true_false: "True / false",
  short_answer: "Short answer"
};

export function QuizPacksPage() {
  const { t, formatDate } = useI18n();
  const messageOf = (problem: unknown) => errorText(problem, "The operation failed", t);
  const [identity, setIdentity] = useState<QuizSigningIdentity | null>(null);
  const [packs, setPacks] = useState<QuizPackSummary[]>([]);
  const [title, setTitle] = useState(() => t("Home learning pack"));
  const [version, setVersion] = useState(1);
  const [questions, setQuestions] = useState<EditableQuestion[]>([emptyQuestion("single_choice")]);
  const [curriculumEdition, setCurriculumEdition] = useState<CurriculumEdition>("pep");
  const [curriculumGrade, setCurriculumGrade] = useState<CurriculumGradeFilter>(3);
  const [curriculumUnit, setCurriculumUnit] = useState<CurriculumUnitFilter>(1);
  const [curriculumSubject, setCurriculumSubject] = useState<CurriculumSubject>("all");
  const [signed, setSigned] = useState<SignedQuizPack | null>(null);
  const [basePack, setBasePack] = useState<ParsedQuizPack | null>(null);
  const [publishMode, setPublishMode] = useState<"full" | "delta">("full");
  const [transitions, setTransitions] = useState<QuizKeyTransition[]>([]);
  const [rotationBusy, setRotationBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [upstream, setUpstream] = useState<QuizUpstreamConfig | null>(null);
  const [upstreamEvents, setUpstreamEvents] = useState<QuizUpstreamEvent[]>([]);
  const [upstreamTitle, setUpstreamTitle] = useState(() => t("Trusted upstream quiz pack"));
  const [upstreamUrl, setUpstreamUrl] = useState("");
  const [upstreamPublicKey, setUpstreamPublicKey] = useState("");
  const [upstreamInterval, setUpstreamInterval] = useState(60);
  const [upstreamEnabled, setUpstreamEnabled] = useState(true);
  const [upstreamBusy, setUpstreamBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const refreshPacks = useCallback(async () => {
    const next = await api.listQuizPacks();
    setPacks(next);
    return next;
  }, []);

  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const [savedIdentity, nextPacks, savedUpstream, savedEvents, savedTransitions] = await Promise.all([
          loadSigningIdentity(),
          api.listQuizPacks(),
          api.getQuizUpstream(),
          api.quizUpstreamEvents(),
          api.quizKeyTransitions()
        ]);
        if (!active) return;
        setIdentity(savedIdentity);
        setPacks(nextPacks);
        setTransitions(savedTransitions);
        setVersion(nextPacks[0] ? nextPacks[0].version + 1 : 1);
        setUpstream(savedUpstream ?? null);
        setUpstreamEvents(savedUpstream ? savedEvents : []);
        if (savedUpstream) {
          setUpstreamTitle(savedUpstream.title);
          setUpstreamUrl(savedUpstream.url);
          setUpstreamPublicKey(savedUpstream.public_key);
          setUpstreamInterval(savedUpstream.interval_minutes);
          setUpstreamEnabled(savedUpstream.enabled);
        } else if (savedIdentity) {
          setUpstreamPublicKey(savedIdentity.publicKeyBase64);
        }
      } catch (problem) {
        if (active) setError(messageOf(problem));
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => { active = false; };
  }, []);

  const deltaPreview = useMemo(() => {
    if (!basePack || !identity) return { plan: null, error: null as string | null };
    try {
      return {
        plan: planQuizDelta(basePack.version, basePack.questions, version, questions),
        error: null as string | null
      };
    } catch (problem) {
      return { plan: null, error: messageOf(problem) };
    }
  }, [basePack, identity, questions, version]);

  const curriculumSelection = useMemo<CurriculumSelection>(() => ({
    edition: curriculumEdition,
    grade: curriculumGrade,
    unit: curriculumUnit,
    subject: curriculumSubject
  }), [curriculumEdition, curriculumGrade, curriculumSubject, curriculumUnit]);

  const curriculumGenerated = useMemo(
    () => curriculumQuestions(curriculumSelection),
    [curriculumSelection]
  );

  const curriculumCounts = useMemo(() => ({
    english: curriculumGenerated.filter((question) => question.subject === "english").length,
    mathematics: curriculumGenerated.filter((question) => question.subject === "mathematics").length,
    chinese: curriculumGenerated.filter((question) => question.subject === "chinese").length
  }), [curriculumGenerated]);

  function updateQuestion(index: number, patch: Partial<EditableQuestion>) {
    setQuestions((current) => current.map((question, itemIndex) =>
      itemIndex === index ? { ...question, ...patch } : question));
  }

  function addQuestion(type: QuizQuestionType) {
    setQuestions((current) => [...current, emptyQuestion(type)]);
  }

  function removeQuestion(index: number) {
    setQuestions((current) => current.length === 1 ? current : current.filter((_, itemIndex) => itemIndex !== index));
  }

  function curriculumSubjectLabel(subject: CurriculumSubject): string {
    if (subject === "all") return t("All subjects");
    if (subject === "english") return t("English");
    if (subject === "mathematics") return t("Mathematics");
    return t("Chinese");
  }

  function curriculumTitle(selection: CurriculumSelection): string {
    const grade = selection.grade === "all"
      ? t("All grades") : t("Grade {grade}", { grade: selection.grade });
    const unit = selection.unit === "all"
      ? t("All units") : t("Unit {unit}", { unit: selection.unit });
    return t("PEP {grade} {unit}: {subject}", {
      grade,
      unit,
      subject: curriculumSubjectLabel(selection.subject)
    });
  }

  function nextPackVersion(): number {
    const newest = packs.reduce((maximum, pack) => Math.max(maximum, pack.version), 0);
    return Math.max(version, newest + 1);
  }

  function cloneQuestions(source: EditableQuestion[]): EditableQuestion[] {
    return source.map((question) => ({
      ...question,
      options: [...question.options],
      answerIndices: [...question.answerIndices]
    }));
  }

  function loadCurriculumIntoEditor() {
    if (curriculumGenerated.length > 100) {
      setNotice(t("The full library is published directly to avoid loading hundreds of editor rows. Choose a single grade and unit to preview or edit individual questions."));
      return;
    }
    setError(null);
    setQuestions(cloneQuestions(curriculumGenerated));
    setTitle(curriculumTitle(curriculumSelection));
    setVersion(nextPackVersion());
    setPublishMode("full");
    setNotice(t("Loaded {count} curriculum questions into the editor. Review them, then sign and publish.",
      { count: curriculumGenerated.length }));
  }

  async function publishCurriculum() {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      if (!identity) throw new Error("Create the quiz signing key first");
      if (curriculumGenerated.length > 500) {
        throw new Error("The generated curriculum exceeds the 500-question pack limit; publish a smaller grade or unit range");
      }
      const packTitle = curriculumTitle(curriculumSelection);
      const targetVersion = nextPackVersion();
      const pack = await signQuizPack(targetVersion, curriculumGenerated, identity);
      const created = await api.createQuizPack(packTitle, pack.publicKeyBase64, pack.envelope);
      await refreshPacks();
      setTitle(packTitle);
      setVersion(pack.version + 1);
      setSigned(pack);
      if (curriculumGenerated.length <= 100) {
        const parsed = await verifyAndParseEnvelope(pack.envelope, identity);
        setQuestions(cloneQuestions(curriculumGenerated));
        setBasePack(parsed);
        setPublishMode("delta");
      } else {
        setBasePack(null);
        setPublishMode("full");
      }
      setNotice(t("Curriculum pack version {version} with {count} questions was signed, hosted and activated. Child devices will apply it automatically on their next sync.", {
        version: created.version,
        count: pack.questionCount
      }));
    } catch (problem) {
      setError(messageOf(problem));
    } finally {
      setBusy(false);
    }
  }

  async function generateKey() {
    setBusy(true);
    setError(null);
    try {
      const created = await createSigningIdentity();
      setIdentity(created);
      setUpstreamPublicKey((current) => current.trim() ? current : created.publicKeyBase64);
      setNotice(t("Created signing key {key}... This browser keeps the private key; export the public key to install it on a child device only if you need manual trust.",
        { key: created.keyId.slice(0, 16) }));
    } catch (problem) {
      setError(messageOf(problem));
    } finally {
      setBusy(false);
    }
  }

  async function copyPublicKey() {
    if (!identity) return;
    try {
      await navigator.clipboard.writeText(identity.publicKeyBase64);
      setNotice(t("Quiz signing public key copied to the clipboard."));
      setError(null);
    } catch {
      setError(t("Clipboard access was denied; download the public key instead."));
    }
  }

  function downloadPublicKey() {
    if (!identity) return;
    downloadText("guard-quiz-public-key.txt", identity.publicKeyBase64, "text/plain;charset=utf-8");
  }

  async function rotateSigningKey() {
    if (!identity) return;
    if (!window.confirm(t(
      "Rotate the quiz signing key? The current key will sign a transition that authorizes the new key, and this browser will stop using the old active key. Keep this browser profile backed up."))) return;
    setRotationBusy(true);
    setError(null);
    setNotice(null);
    try {
      const prepared = await prepareSigningKeyRotation(identity);
      const accepted = await api.rotateQuizKey(prepared.transition);
      await commitSigningKeyRotation(identity, prepared);
      setIdentity(prepared.identity);
      setUpstreamPublicKey((current) => current.trim() === identity.publicKeyBase64
        ? prepared.identity.publicKeyBase64 : current);
      setTransitions(await api.quizKeyTransitions());
      setBasePack(null);
      setPublishMode("full");
      setNotice(t("New quiz signing key {key}... is active. The transition signed by the old key is now available to paired devices.",
        { key: accepted.new_key_id.slice(0, 16) }));
    } catch (problem) {
      setError(messageOf(problem));
    } finally {
      setRotationBusy(false);
    }
  }

  function downloadTransition(transition: QuizKeyTransition) {
    downloadText(`guard-quiz-key-transition-${transition.new_key_id.slice(0, 16)}.json`,
      transition.transition, "application/json;charset=utf-8");
  }

  async function revokeTransition(transition: QuizKeyTransition) {
    if (!window.confirm(t(
      "Revoke this key transition? Devices will stop trusting the new key after they apply the revocation, unless the active quiz pack still uses it."))) return;
    setRotationBusy(true);
    setError(null);
    setNotice(null);
    try {
      await api.revokeQuizKey(transition.id);
      setTransitions(await api.quizKeyTransitions());
      setNotice(t("Key transition revoked. The server refuses to revoke a key currently signing the active quiz pack."));
    } catch (problem) {
      setError(messageOf(problem));
    } finally {
      setRotationBusy(false);
    }
  }

  async function signCurrent(publishedAt?: string): Promise<SignedQuizPack> {
    if (!identity) throw new Error("Create the quiz signing key first");
    const pack = await signQuizPack(version, questions, identity, publishedAt);
    setSigned(pack);
    return pack;
  }

  async function downloadEnvelope() {
    setBusy(true);
    setError(null);
    try {
      const pack = await signCurrent();
      downloadText(`guard-quiz-v${pack.version}.json`, pack.envelope, "application/json;charset=utf-8");
      setNotice(t("Signed version {version} and downloaded the envelope for manual import.", { version: pack.version }));
    } catch (problem) {
      setError(messageOf(problem));
    } finally {
      setBusy(false);
    }
  }

  async function uploadEnvelope() {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      if (!identity) throw new Error("Create the quiz signing key first");
      if (!title.trim()) throw new Error("Give this quiz pack a title");
      const pack = await signCurrent();
      const created = await api.createQuizPack(title.trim(), pack.publicKeyBase64, pack.envelope);
      const parsed = await verifyAndParseEnvelope(pack.envelope, identity);
      await refreshPacks();
      setVersion(pack.version + 1);
      setSigned(pack);
      setBasePack(parsed);
      setPublishMode("delta");
      setNotice(t("Version {version} was verified by the server, hosted, and activated for your paired devices.",
        { version: created.version }));
    } catch (problem) {
      setError(messageOf(problem));
    } finally {
      setBusy(false);
    }
  }

  async function uploadDelta() {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      if (!identity) throw new Error("Create the quiz signing key first");
      if (!basePack) throw new Error("Load the active pack before publishing a delta");
      if (basePack.keyId !== identity.keyId) {
        throw new Error("The loaded base pack was signed by a different key; rotate or restore that key first");
      }
      if (!title.trim()) throw new Error("Give this quiz pack a title");
      const full = await signCurrent();
      const delta = await signQuizDelta(basePack.version, basePack.questions,
        version, questions, identity, full.publishedAt);
      const created = await api.createQuizPack(title.trim(), full.publicKeyBase64,
        full.envelope, delta.envelope, basePack.version);
      const parsed = await verifyAndParseEnvelope(full.envelope, identity);
      await refreshPacks();
      setVersion(full.version + 1);
      setSigned(full);
      setBasePack(parsed);
      setPublishMode("delta");
      setNotice(t("Version {version} was hosted with a verified {operations}-operation delta against version {base} ({size} KiB).", {
        version: created.version,
        operations: delta.operationCount,
        base: delta.baseVersion,
        size: Math.ceil(delta.byteLength / 1024)
      }));
    } catch (problem) {
      setError(messageOf(problem));
    } finally {
      setBusy(false);
    }
  }

  async function reloadUpstream() {
    const config = await api.getQuizUpstream();
    setUpstream(config ?? null);
    const events = config ? await api.quizUpstreamEvents() : [];
    setUpstreamEvents(events);
    return config ?? null;
  }

  async function saveUpstream() {
    setUpstreamBusy(true);
    setError(null);
    setNotice(null);
    try {
      const saved = await api.saveQuizUpstream({
        title: upstreamTitle.trim(),
        url: upstreamUrl.trim(),
        public_key: upstreamPublicKey.trim(),
        interval_minutes: upstreamInterval,
        enabled: upstreamEnabled
      });
      setUpstream(saved);
      setUpstreamTitle(saved.title);
      setUpstreamUrl(saved.url);
      setUpstreamPublicKey(saved.public_key);
      setUpstreamInterval(saved.interval_minutes);
      setUpstreamEnabled(saved.enabled);
      await reloadUpstream();
      setNotice(t("Trusted upstream configuration saved. The scheduler will check it at the configured interval."));
    } catch (problem) {
      setError(messageOf(problem));
    } finally {
      setUpstreamBusy(false);
    }
  }

  async function syncUpstreamNow() {
    setUpstreamBusy(true);
    setError(null);
    setNotice(null);
    try {
      const result = await api.syncQuizUpstream();
      await reloadUpstream();
      if (result.ok) {
        const version = result.version === null ? "" : t(" Version {version}.", { version: result.version });
        setNotice(t("Upstream sync completed: {status}{version}", {
          status: t(result.status), version
        }));
      } else {
        setError(t("Upstream sync failed: {message}", { message: result.message || t(result.status) }));
      }
    } catch (problem) {
      setError(messageOf(problem));
    } finally {
      setUpstreamBusy(false);
    }
  }

  async function deleteUpstream() {
    if (!window.confirm(t("Delete this trusted upstream configuration? The active hosted quiz pack will remain available."))) return;
    setUpstreamBusy(true);
    setError(null);
    setNotice(null);
    try {
      await api.deleteQuizUpstream();
      setUpstream(null);
      setUpstreamEvents([]);
      setNotice(t("Trusted upstream configuration deleted. The active hosted pack was not changed."));
    } catch (problem) {
      setError(messageOf(problem));
    } finally {
      setUpstreamBusy(false);
    }
  }

  async function loadServerPack(pack: QuizPackSummary) {
    if (!identity) {
      setError(t("Load or create the matching signing key before editing this pack"));
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const detail = await api.getQuizPack(pack.id);
      const parsed = await verifyAndParseEnvelope(detail.envelope, identity);
      setTitle(detail.title);
      setQuestions(parsed.questions);
      setVersion(parsed.version + 1);
      setSigned(null);
      setBasePack(detail.active ? parsed : null);
      setPublishMode(detail.active ? "delta" : "full");
      setNotice(detail.active
        ? t("Loaded active version {version}. The next publish can use a delta against this base.", { version: parsed.version })
        : t("Loaded inactive version {version}. Activate it first or publish a complete pack before using deltas.", { version: parsed.version }));
    } catch (problem) {
      setError(messageOf(problem));
    } finally {
      setBusy(false);
    }
  }

  async function loadFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    if (!identity) {
      setError(t("Create or restore the matching signing key before importing a pack for editing"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const parsed = await verifyAndParseEnvelope(await file.text(), identity);
      setQuestions(parsed.questions);
      setVersion(parsed.version + 1);
      setTitle(file.name.replace(/\.json$/i, "") || "Imported quiz pack");
      setSigned(null);
      setBasePack(parsed);
      setPublishMode("delta");
      setNotice(t("Imported signed version {version}. It is available as the delta base; the editor targets version {next}.", {
        version: parsed.version, next: parsed.version + 1
      }));
    } catch (problem) {
      setError(messageOf(problem));
    } finally {
      setBusy(false);
    }
  }

  async function downloadServerEnvelope(pack: QuizPackSummary) {
    setBusy(true);
    setError(null);
    try {
      const detail = await api.getQuizPack(pack.id);
      downloadText(`guard-quiz-v${detail.version}.json`, detail.envelope, "application/json;charset=utf-8");
    } catch (problem) {
      setError(messageOf(problem));
    } finally {
      setBusy(false);
    }
  }

  async function setQuestionImage(index: number, file: File | null) {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const image = await imageFileToQuestion(file);
      updateQuestion(index, {
        imageBase64: image.base64,
        imageMime: image.mime,
        imageName: image.name
      });
    } catch (problem) {
      setError(messageOf(problem));
    } finally {
      setBusy(false);
    }
  }

  if (loading) return <p>{t("Loading quiz packs...")}</p>;

  return (
    <section>
      <div className="page-title">
        <div>
          <h1>{t("Signed quiz packs")}</h1>
          <p className="muted">{t("Author questions here. The private signing key stays in this browser; only a verified signed envelope is uploaded.")}</p>
        </div>
        <button className="secondary" disabled={busy} onClick={() => void refreshPacks().catch((problem) => setError(messageOf(problem)))}>
          {t("Refresh")}
        </button>
      </div>

      {error && <p className="error" role="alert">{error}</p>}
      {notice && <p className="notice" role="status">{notice}</p>}

      <section className="card signing-card">
        <div className="page-title">
          <div>
            <h2>{t("Quiz signing key")}</h2>
            <p className="muted">{t("The private P-256 key is non-extractable and stored in this browser profile. Back up the browser profile if you need to edit these packs later.")}</p>
          </div>
          {!identity && <button className="primary" disabled={busy} onClick={() => void generateKey()}>{t("Create signing key")}</button>}
        </div>
        {identity ? (
          <>
            <dl className="key-details">
              <dt>{t("Key id")}</dt><dd className="mono">{identity.keyId}</dd>
              <dt>{t("Created")}</dt><dd>{formatDate(identity.createdAt)}</dd>
            </dl>
            <label>{t("Public key (install this as the child trust anchor for manual transfers)")}
              <textarea className="key-text" readOnly rows={4} value={identity.publicKeyBase64} />
            </label>
            <div className="row-actions">
              <button className="secondary" onClick={() => void copyPublicKey()}>{t("Copy public key")}</button>
              <button className="secondary" onClick={downloadPublicKey}>{t("Download public key")}</button>
              <button className="danger-button" disabled={rotationBusy}
                onClick={() => void rotateSigningKey()}>
                {rotationBusy ? t("Rotating...") : t("Rotate signing key")}
              </button>
            </div>
          </>
        ) : <p className="hint">{t("No key exists in this browser yet. Create one before signing or editing packs.")}</p>}
      </section>

      <section className="card">
        <div className="page-title">
          <div>
            <h2>{t("Quiz key rotation history")}</h2>
            <p className="muted">{t("Every new key is authorized by an ECDSA transition signed with the currently trusted key. Paired devices apply transitions before downloading a quiz pack.")}</p>
          </div>
          <span className={`badge ${transitions.some((item) => !item.revoked_at) ? "ok" : "warn"}`}>
            {t(transitions.filter((item) => !item.revoked_at).length === 1
              ? "{count} trusted transition" : "{count} trusted transitions",
              { count: transitions.filter((item) => !item.revoked_at).length })}
          </span>
        </div>
        <dl className="key-details">
          <dt>{t("Browser signing key")}</dt><dd className="mono">{identity?.keyId ?? t("No browser key")}</dd>
          <dt>{t("Active hosted pack key")}</dt>
          <dd className="mono">{packs.find((pack) => pack.active)?.key_id ?? t("No active pack")}</dd>
        </dl>
        {transitions.length === 0 ? <p className="empty">{t("No key transitions have been published.")}</p> : (
          <div className="table-wrap"><table>
            <thead><tr><th>{t("Created")}</th><th>{t("Old key")}</th><th>{t("New key")}</th><th>{t("Status")}</th><th /></tr></thead>
            <tbody>{transitions.map((transition) => (
              <tr key={transition.id}>
                <td>{formatDate(transition.created_at)}</td>
                <td className="mono">{transition.old_key_id.slice(0, 16)}...</td>
                <td className="mono">{transition.new_key_id.slice(0, 16)}...</td>
                <td><span className={`badge ${transition.revoked_at ? "danger" : "ok"}`}>
                  {t(transition.revoked_at ? "Revoked" : "Trusted")}
                </span></td>
                <td><div className="row-actions compact-actions">
                  <button className="link-button" onClick={() => downloadTransition(transition)}>{t("Transition")}</button>
                  <button className="link-button" disabled={rotationBusy || Boolean(transition.revoked_at)}
                    onClick={() => void revokeTransition(transition)}>{t("Revoke")}</button>
                </div></td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </section>

      <section className="card upstream-card">
        <div className="page-title">
          <div>
            <h2>{t("Trusted upstream")}</h2>
            <p className="muted">{t("Fetch a signed quiz envelope, or a delta bundle containing the complete target envelope plus a signed delta, from one public HTTPS endpoint on a fixed interval. The server verifies every signature and version before activating anything.")}</p>
          </div>
          <span className={`badge ${upstreamStatusClass(upstream?.last_status ?? "never")}`}>
            {t(upstream?.last_status ?? "not configured")}
          </span>
        </div>
        <p className="hint">{t("A delta bundle is applied only when base_version equals the active hosted version; otherwise the server safely falls back to the complete target envelope. Standalone deltas without a recoverable full target are rejected. A bad signature, equal version or version regression leaves the active pack unchanged.")}</p>
        <div className="form-grid">
          <label>{t("Display title")}
            <input value={upstreamTitle} maxLength={120} disabled={upstreamBusy}
              onChange={(event) => setUpstreamTitle(event.target.value)} />
          </label>
          <label>{t("Sync interval (15 to 1440 minutes)")}
            <input type="number" min={15} max={1440} value={upstreamInterval} disabled={upstreamBusy}
              onChange={(event) => setUpstreamInterval(Number(event.target.value))} />
          </label>
          <label className="wide">{t("Public HTTPS endpoint")}
            <input value={upstreamUrl} maxLength={2048} disabled={upstreamBusy}
              placeholder={t("Public HTTPS URL serving the signed envelope")}
              onChange={(event) => setUpstreamUrl(event.target.value)} />
          </label>
          <label className="wide">{t("Trusted quiz signing public key")}
            <textarea rows={3} value={upstreamPublicKey} disabled={upstreamBusy}
              placeholder={t("Base64-encoded P-256 public key")}
              onChange={(event) => setUpstreamPublicKey(event.target.value)} />
          </label>
          <label className="toggle">
            <input type="checkbox" checked={upstreamEnabled} disabled={upstreamBusy}
              onChange={(event) => setUpstreamEnabled(event.target.checked)} />
            {t("Scheduled checks enabled")}
          </label>
        </div>
        <div className="row-actions">
          <button className="primary" disabled={upstreamBusy} onClick={() => void saveUpstream()}>
            {upstreamBusy ? t("Working...") : upstream ? t("Save changes") : t("Save upstream")}
          </button>
          <button className="secondary" disabled={upstreamBusy || !upstream}
            onClick={() => void syncUpstreamNow()}>{t("Sync now")}</button>
          <button className="danger-button" disabled={upstreamBusy || !upstream}
            onClick={() => void deleteUpstream()}>{t("Delete configuration")}</button>
          {identity && (
            <button className="link-button" disabled={upstreamBusy}
              onClick={() => setUpstreamPublicKey(identity.publicKeyBase64)}>
              {t("Use browser signing key")}
            </button>
          )}
        </div>
        {upstream && (
          <dl className="key-details upstream-details">
            <dt>{t("Trusted key id")}</dt><dd className="mono">{upstream.key_id}</dd>
            <dt>{t("Scheduled checks")}</dt><dd>{t(upstream.enabled ? "Enabled" : "Paused")}</dd>
            <dt>{t("Last checked")}</dt>
            <dd>{upstream.last_checked_at ? formatDate(upstream.last_checked_at) : t("Never")}</dd>
            <dt>{t("Last successful check")}</dt>
            <dd>{upstream.last_success_at ? formatDate(upstream.last_success_at) : t("Never")}</dd>
            <dt>{t("Last accepted version")}</dt><dd>{upstream.last_version ?? t("None")}</dd>
            <dt>{t("Last status")}</dt><dd><span className={`badge ${upstreamStatusClass(upstream.last_status)}`}>{t(upstream.last_status)}</span></dd>
          </dl>
        )}
        {upstream?.last_error && <p className="error" role="alert">{t("Last upstream error: {error}", { error: upstream.last_error })}</p>}
        {upstreamEvents.length > 0 && (
          <div className="table-wrap upstream-events">
            <table>
              <thead><tr><th>{t("Time")}</th><th>{t("Event")}</th><th>{t("Detail")}</th></tr></thead>
              <tbody>{upstreamEvents.map((event) => (
                <tr key={event.id}>
                  <td>{formatDate(event.created_at)}</td>
                  <td className="mono">{event.event}</td>
                  <td className="mono">{event.detail || "-"}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card curriculum-builder">
        <div className="pack-heading">
          <div>
            <h2>{t("Textbook question builder")}</h2>
            <p className="muted">{t("Generate grade- and unit-based questions from the built-in People's Education Press curriculum. English covers word meaning, word choice and spelling; mathematics covers calculation and word problems; Chinese covers characters, word building and poetry.")}</p>
          </div>
          <span className="badge ok">{t("{count} questions", { count: curriculumGenerated.length })}</span>
        </div>
        <div className="form-grid pack-fields curriculum-fields">
          <label>{t("Textbook edition")}
            <select value={curriculumEdition}
              onChange={(event) => setCurriculumEdition(event.target.value as CurriculumEdition)}>
              {CURRICULUM_EDITIONS.map((edition) => (
                <option key={edition} value={edition}>{t("People's Education Press (PEP)")}</option>
              ))}
            </select>
          </label>
          <label>{t("Grade")}
            <select value={curriculumGrade}
              onChange={(event) => setCurriculumGrade(event.target.value === "all"
                ? "all" : Number(event.target.value) as CurriculumGrade)}>
              <option value="all">{t("All grades")}</option>
              {CURRICULUM_GRADES.map((grade) => (
                <option key={grade} value={grade}>{t("Grade {grade}", { grade })}</option>
              ))}
            </select>
          </label>
          <label>{t("Unit")}
            <select value={curriculumUnit}
              onChange={(event) => setCurriculumUnit(event.target.value === "all"
                ? "all" : Number(event.target.value) as CurriculumUnit)}>
              <option value="all">{t("All units")}</option>
              {CURRICULUM_UNITS.map((unit) => (
                <option key={unit} value={unit}>{t("Unit {unit}", { unit })}</option>
              ))}
            </select>
          </label>
          <label>{t("Subject")}
            <select value={curriculumSubject}
              onChange={(event) => setCurriculumSubject(event.target.value as CurriculumSubject)}>
              <option value="all">{t("All subjects")}</option>
              <option value="english">{t("English")}</option>
              <option value="mathematics">{t("Mathematics")}</option>
              <option value="chinese">{t("Chinese")}</option>
            </select>
          </label>
        </div>
        <div className="row-actions curriculum-summary">
          <span className="badge neutral">{t("English: {count} questions", { count: curriculumCounts.english })}</span>
          <span className="badge neutral">{t("Mathematics: {count} questions", { count: curriculumCounts.mathematics })}</span>
          <span className="badge neutral">{t("Chinese: {count} questions", { count: curriculumCounts.chinese })}</span>
        </div>
        <div className="row-actions">
          <button className="secondary"
            disabled={busy || curriculumGenerated.length === 0 || curriculumGenerated.length > 100}
            onClick={loadCurriculumIntoEditor}>
            {t("Load into editor")}
          </button>
          <button className="primary" disabled={busy || !identity || curriculumGenerated.length === 0}
            onClick={() => void publishCurriculum()}>
            {busy ? t("Signing...") : t("Generate, sign and publish")}
          </button>
        </div>
        {curriculumGenerated.length > 100 && (
          <p className="hint">{t("The full library is published directly to avoid loading hundreds of editor rows. Choose a single grade and unit to preview or edit individual questions.")}</p>
        )}
        <p className="hint">{t("After publishing, paired child devices download and activate this pack automatically on their next 15-minute sync. No action is needed on the child device.")}</p>
      </section>

      <section className="card">
        <div className="pack-heading">
          <div>
            <h2>{t("Pack editor")}</h2>
            <p className="muted">{t("Publishing requires a version newer than the currently active pack.")}</p>
          </div>
          <span className="badge ok">{t(questions.length === 1 ? "{count} question" : "{count} questions",
            { count: questions.length })}</span>
        </div>
        <div className="form-grid pack-fields">
          <label>{t("Title")}
            <input value={title} maxLength={120} onChange={(event) => setTitle(event.target.value)} />
          </label>
          <label>{t("Version")}
            <input type="number" min={1} max={9_999_999_999} value={version}
              onChange={(event) => setVersion(Number(event.target.value))} />
          </label>
        </div>

        <div className="question-list">
          {questions.map((question, index) => (
            <QuestionEditor key={question.id || index} question={question} index={index}
              busy={busy} onChange={updateQuestion} onRemove={removeQuestion} onImage={setQuestionImage} />
          ))}
        </div>

        <div className="row-actions add-question-actions">
          <span className="hint">{t("Add question:")}</span>
          {Object.entries(TYPE_LABELS).map(([type, label]) =>
            <button key={type} className="secondary" disabled={busy}
              onClick={() => addQuestion(type as QuizQuestionType)}>{t(label)}</button>)}
        </div>

        <div className="row-actions publish-actions">
          <span className="hint">{t("Publish as:")}</span>
          <label className="toggle">
            <input type="radio" name="publish-mode" checked={publishMode === "full"}
              disabled={busy} onChange={() => setPublishMode("full")} />
            {t("Complete pack")}
          </label>
          <label className="toggle">
            <input type="radio" name="publish-mode" checked={publishMode === "delta"}
              disabled={busy || !basePack} onChange={() => setPublishMode("delta")} />
            {basePack ? t("Delta from version {version}", { version: basePack.version }) : t("Delta (load active pack first)")}
          </label>
        </div>
        {publishMode === "delta" && basePack && (
          <p className={deltaPreview.error ? "error" : "hint"} role={deltaPreview.error ? "alert" : undefined}>
            {deltaPreview.error
              ? t(deltaPreview.error)
              : t("Delta preview: {operations} operations, {size} KiB signed payload. A complete version {version} pack will also be stored as the recovery baseline.", {
                operations: deltaPreview.plan?.operations.length ?? 0,
                size: Math.ceil((deltaPreview.plan?.byteLength ?? 0) / 1024),
                version
              })}
          </p>
        )}
        <div className="row-actions publish-actions">
          <button className="primary" disabled={busy || !identity
            || (publishMode === "delta" && (!basePack || !deltaPreview.plan
              || basePack.keyId !== identity.keyId))}
            onClick={() => void (publishMode === "delta" ? uploadDelta() : uploadEnvelope())}>
            {busy ? t("Signing...") : publishMode === "delta"
              ? t("Sign delta, upload and activate") : t("Sign full pack, upload and activate")}
          </button>
          <button className="secondary" disabled={busy || !identity} onClick={() => void downloadEnvelope()}>
            {t("Download signed envelope")}
          </button>
          <input ref={fileRef} type="file" accept="application/json,.json" hidden onChange={(event) => void loadFile(event)} />
          <button className="secondary" disabled={busy || !identity} onClick={() => fileRef.current?.click()}>
            {t("Import signed pack for editing")}
          </button>
        </div>
        {signed && (
          <p className="hint">
            {t("Last signed payload: version {version}, {count} questions, envelope {size} KiB, key {key}...", {
              version: signed.version,
              count: signed.questionCount,
              size: Math.ceil(signed.envelope.length / 1024),
              key: signed.keyId.slice(0, 16)
            })}
          </p>
        )}
      </section>

      <section className="card">
        <h2>{t("Hosted packs")}</h2>
        <p className="muted">{t("The active pack is downloaded automatically by paired child devices on their next sync.")}</p>
        {packs.length === 0 ? <p className="empty">{t("No signed packs have been uploaded.")}</p> : (
          <div className="table-wrap"><table>
            <thead><tr><th>{t("Version")}</th><th>{t("Title")}</th><th>{t("Questions")}</th><th>{t("Delivery")}</th><th>{t("Published")}</th><th>{t("Status")}</th><th /></tr></thead>
            <tbody>{packs.map((pack) => (
              <tr key={pack.id}>
                <td>{pack.version}</td>
                <td>{pack.title}</td>
                <td>{pack.question_count}</td>
                <td>{pack.has_delta ? t("Delta from v{version}", { version: pack.base_version ?? "" }) : t("Complete")}</td>
                <td>{formatDate(pack.created_at)}</td>
                <td><span className={`badge ${pack.active ? "ok" : "warn"}`}>{t(pack.active ? "Active" : "Inactive")}</span></td>
                <td><div className="row-actions compact-actions">
                  <button className="link-button" disabled={busy} onClick={() => void loadServerPack(pack)}>{t("Load")}</button>
                  <button className="link-button" disabled={busy} onClick={() => void downloadServerEnvelope(pack)}>{t("Envelope")}</button>
                </div></td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </section>
    </section>
  );
}

function QuestionEditor({ question, index, busy, onChange, onRemove, onImage }: {
  question: EditableQuestion;
  index: number;
  busy: boolean;
  onChange: (index: number, patch: Partial<EditableQuestion>) => void;
  onRemove: (index: number) => void;
  onImage: (index: number, file: File | null) => Promise<void>;
}) {
  const { t } = useI18n();
  const update = (patch: Partial<EditableQuestion>) => onChange(index, patch);

  function updateOption(optionIndex: number, value: string) {
    update({ options: question.options.map((option, itemIndex) => itemIndex === optionIndex ? value : option) });
  }

  function removeOption(optionIndex: number) {
    if (question.options.length <= 2) return;
    const options = question.options.filter((_, itemIndex) => itemIndex !== optionIndex);
    const answerIndex = question.answerIndex === optionIndex ? 0 : question.answerIndex > optionIndex ? question.answerIndex - 1 : question.answerIndex;
    const answerIndices = question.answerIndices.filter((answer) => answer !== optionIndex)
      .map((answer) => answer > optionIndex ? answer - 1 : answer);
    update({ options, answerIndex, answerIndices });
  }

  function toggleMultiple(optionIndex: number) {
    const selected = new Set(question.answerIndices);
    if (selected.has(optionIndex)) selected.delete(optionIndex);
    else selected.add(optionIndex);
    update({ answerIndices: [...selected].sort((left, right) => left - right) });
  }

  return (
    <article className="question-card">
      <div className="question-heading">
        <strong>{t("Question {number}", { number: index + 1 })}</strong>
        <div className="row-actions compact-actions">
          <select value={question.type} disabled={busy}
            onChange={(event) => onChange(index, changeQuestionType(question, event.target.value as QuizQuestionType))}>
            {Object.entries(TYPE_LABELS).map(([type, label]) => <option key={type} value={type}>{t(label)}</option>)}
          </select>
          <button className="danger-button" disabled={busy} onClick={() => onRemove(index)}>{t("Remove")}</button>
        </div>
      </div>
      <div className="form-grid">
        <label>{t("Question id")}
          <input value={question.id} maxLength={128} onChange={(event) => update({ id: event.target.value })} />
        </label>
        <label>{t("Prompt")}
          <input value={question.prompt} maxLength={2_000} onChange={(event) => update({ prompt: event.target.value })} />
        </label>
        <label>{t("Subject")}
          <input placeholder={t("mathematics")} value={question.subject} maxLength={64}
            onChange={(event) => update({ subject: event.target.value })} />
        </label>
        <label>{t("Grade (0 = unspecified)")}
          <input type="number" min={0} max={12} value={question.grade}
            onChange={(event) => update({ grade: Number(event.target.value) })} />
        </label>
        <label>{t("Difficulty")}
          <select value={question.difficulty} onChange={(event) => update({ difficulty: event.target.value })}>
            <option value="">{t("Unspecified")}</option><option value="easy">{t("Easy")}</option>
            <option value="medium">{t("Medium")}</option><option value="hard">{t("Hard")}</option>
          </select>
        </label>
        <label>{t("Language")}
          <input placeholder={t("en or zh-CN")} value={question.language} maxLength={32}
            onChange={(event) => update({ language: event.target.value })} />
        </label>
        <label className="wide">{t("Tags (comma separated)")}
          <input placeholder={t("arithmetic, fractions")} value={question.tags}
            onChange={(event) => update({ tags: event.target.value })} />
        </label>
      </div>

      {(question.type === "single_choice" || question.type === "multiple_choice") && (
        <div className="answer-editor">
          <div className="answer-editor-title">
            <strong>{t("Options")}</strong>
            <span className="hint">{t(question.type === "single_choice" ? "Mark one correct answer." : "Mark two or more, but not all, correct.")}</span>
          </div>
          {question.options.map((option, optionIndex) => (
            <div className="option-row" key={optionIndex}>
              <input type={question.type === "single_choice" ? "radio" : "checkbox"}
                name={`answer-${question.id || index}`}
                checked={question.type === "single_choice"
                  ? question.answerIndex === optionIndex : question.answerIndices.includes(optionIndex)}
                onChange={() => question.type === "single_choice"
                  ? update({ answerIndex: optionIndex }) : toggleMultiple(optionIndex)} />
              <input value={option} maxLength={300} placeholder={t("Option {number}", { number: optionIndex + 1 })}
                onChange={(event) => updateOption(optionIndex, event.target.value)} />
              <button className="link-button" disabled={question.options.length <= 2 || busy}
                onClick={() => removeOption(optionIndex)}>{t("Remove")}</button>
            </div>
          ))}
          <button className="secondary" disabled={question.options.length >= 8 || busy}
            onClick={() => update({ options: [...question.options, ""] })}>{t("Add option")}</button>
        </div>
      )}

      {question.type === "true_false" && (
        <label className="answer-select">{t("Correct answer")}
          <select value={question.booleanAnswer ? "true" : "false"}
            onChange={(event) => update({ booleanAnswer: event.target.value === "true" })}>
            <option value="true">{t("True")}</option><option value="false">{t("False")}</option>
          </select>
        </label>
      )}

      {question.type === "short_answer" && (
        <label>{t("Accepted answers (one per line)")}
          <textarea rows={3} value={question.acceptedAnswers}
            onChange={(event) => update({ acceptedAnswers: event.target.value })} />
        </label>
      )}

      <div className="image-editor">
        <div>
          <strong>{t("Question image")}</strong>
          <p className="hint">{t("Optional JPEG or PNG, up to 512 KiB. Total pack images are limited to 5 MiB.")}</p>
        </div>
        {question.imageBase64 ? (
          <div className="question-image-preview">
            <img src={`data:${question.imageMime};base64,${question.imageBase64}`} alt={question.imageName || t("Question")} />
            <div className="row-actions">
              <span className="mono">{question.imageName || t("embedded image")}</span>
              <button className="danger-button" disabled={busy}
                onClick={() => update({ imageBase64: "", imageMime: "", imageName: "" })}>{t("Remove image")}</button>
            </div>
          </div>
        ) : (
          <input type="file" accept="image/jpeg,image/png" disabled={busy}
            onChange={(event) => void onImage(index, event.target.files?.[0] ?? null).finally(() => { event.target.value = ""; })} />
        )}
      </div>
    </article>
  );
}

function upstreamStatusClass(status: string): string {
  if (status === "failed") return "danger";
  if (status === "updated" || status === "not_modified" || status === "no_update") return "ok";
  return "warn";
}
