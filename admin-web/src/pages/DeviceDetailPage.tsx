import { lazy, Suspense, useCallback, useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  api,
  type AuditEvent,
  type CallRequest,
  type Device,
  type DeviceApp,
  type HelpRequest,
  type LiveKitCredentials,
  type ManualQuizTrigger,
  type Notice,
  type PolicyEnvelope,
  type PolicyHistoryItem,
  type QuizAttemptResult,
  type Screenshot,
  type TemporaryGrant,
  type UsageReport
} from "../api";
import {
  CATEGORY_LABELS,
  emptyPolicy,
  formFromPolicyPayload,
  policyFromForm,
  type AppPolicy,
  type HelpContactForm,
  type PolicyFormState,
  type QuizTriggerForm,
  type QuizTriggerType
} from "../policy";
import { errorText, useI18n } from "../i18n";

const CallPanel = lazy(() => import("../components/CallPanel")
  .then((module) => ({ default: module.CallPanel })));

export function DeviceDetailPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const { t, formatDate, formatTime } = useI18n();
  const [device, setDevice] = useState<Device | null>(null);
  const [envelope, setEnvelope] = useState<PolicyEnvelope | null>(null);
  const [policyHistory, setPolicyHistory] = useState<PolicyHistoryItem[]>([]);
  const [usage, setUsage] = useState<UsageReport[]>([]);
  const [audit, setAudit] = useState<AuditEvent[]>([]);
  const [calls, setCalls] = useState<CallRequest[]>([]);
  const [catalog, setCatalog] = useState<DeviceApp[]>([]);
  const [grants, setGrants] = useState<TemporaryGrant[]>([]);
  const [screenshots, setScreenshots] = useState<Screenshot[]>([]);
  const [quizResults, setQuizResults] = useState<QuizAttemptResult[]>([]);
  const [quizTriggers, setQuizTriggers] = useState<ManualQuizTrigger[]>([]);
  const [quizPackage, setQuizPackage] = useState("");
  const [notices, setNotices] = useState<Notice[]>([]);
  const [helpRequests, setHelpRequests] = useState<HelpRequest[]>([]);
  const [helpReplies, setHelpReplies] = useState<Record<string, string>>({});
  const [noticeMessage, setNoticeMessage] = useState("");
  const [preview, setPreview] = useState<{ id: string; url: string } | null>(null);
  const [form, setForm] = useState<PolicyFormState>({ ...emptyPolicy });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [credentials, setCredentials] = useState<LiveKitCredentials | null>(null);
  const [activeCallId, setActiveCallId] = useState<string | null>(null);
  const [callKind, setCallKind] = useState<CallRequest["kind"]>("video");
  const [newPackage, setNewPackage] = useState("");
  const [selectedCategory, setSelectedCategory] = useState(0);
  const [categoryMinutes, setCategoryMinutes] = useState(60);
  const [grantPackage, setGrantPackage] = useState("");
  const [grantMinutes, setGrantMinutes] = useState(15);

  const load = useCallback(async () => {
    try {
      const [nextDevice, nextPolicy, nextHistory, nextUsage, nextAudit, nextCalls, nextApps, nextGrants,
        nextScreenshots, nextQuizResults, nextNotices, nextQuizTriggers, nextHelpRequests] = await Promise.all([
        api.getDevice(id),
        api.latestPolicy(id).catch(() => null),
        api.policyHistory(id).catch(() => []),
        api.usage(id).catch(() => []),
        api.audit(id).catch(() => []),
        api.listCalls(id).catch(() => []),
        api.apps(id).catch(() => []),
        api.grants(id).catch(() => []),
        api.screenshots(id).catch(() => []),
        api.quizResults(id).catch(() => []),
        api.notices(id).catch(() => []),
        api.quizTriggers(id).catch(() => []),
        api.helpRequests(id).catch(() => [])
      ]);
      setDevice(nextDevice);
      setEnvelope(nextPolicy);
      setPolicyHistory(nextHistory);
      const lookup = new Map(nextApps.map((app) => [app.package_name, app]));
      if (nextPolicy) {
        const nextForm = formFromPolicyPayload(nextPolicy.payload);
        nextForm.apps = nextForm.apps.map((app) => {
          const installed = lookup.get(app.packageName);
          return installed
            ? { ...app, label: installed.label, category: installed.category }
            : app;
        });
        setForm(nextForm);
      }
      setUsage(nextUsage);
      setAudit(nextAudit);
      setCalls(nextCalls);
      setCatalog(nextApps);
      setGrants(nextGrants);
      setScreenshots(nextScreenshots);
      setQuizResults(nextQuizResults);
      setNotices(nextNotices);
      setQuizTriggers(nextQuizTriggers);
      setHelpRequests(nextHelpRequests);
      setError(null);
    } catch (problem) {
      setError(errorText(problem, "Cannot load this device", t));
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      Promise.all([
        api.listCalls(id).then(setCalls).catch(() => undefined),
        api.screenshots(id).then(setScreenshots).catch(() => undefined),
        api.notices(id).then(setNotices).catch(() => undefined),
        api.quizResults(id).then(setQuizResults).catch(() => undefined),
        api.quizTriggers(id).then(setQuizTriggers).catch(() => undefined)
      ]).catch(() => undefined);
    }, 3000);
    return () => window.clearInterval(timer);
  }, [id]);

  useEffect(() => () => {
    if (preview) URL.revokeObjectURL(preview.url);
  }, [preview]);

  function update<K extends keyof PolicyFormState>(key: K, value: PolicyFormState[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  function updateApp(index: number, patch: Partial<AppPolicy>) {
    setForm((current) => ({
      ...current,
      apps: current.apps.map((app, appIndex) => appIndex === index ? { ...app, ...patch } : app)
    }));
  }

  function removeApp(index: number) {
    setForm((current) => ({ ...current, apps: current.apps.filter((_, appIndex) => appIndex !== index) }));
  }

  function updateTrigger(index: number, patch: Partial<QuizTriggerForm>) {
    setForm((current) => ({
      ...current,
      quizTriggers: current.quizTriggers.map((trigger, triggerIndex) =>
        triggerIndex === index ? { ...trigger, ...patch } : trigger)
    }));
  }

  function addTrigger(type: QuizTriggerType) {
    setForm((current) => {
      const firstManaged = current.apps.find(
        (app) => !app.blocked && !app.alwaysAllowed
      )?.packageName ?? "";
      return {
        ...current,
        quizTriggers: [...current.quizTriggers, {
          type,
          packages: firstManaged,
          minutes: type === "continuous_use" ? 20 : 30,
          delaySeconds: 0,
          days: "1, 2, 3, 4, 5",
          startMinute: 960
        }]
      };
    });
  }

  function updateHelpContact(index: number, patch: Partial<HelpContactForm>) {
    setForm((current) => ({
      ...current,
      helpContacts: current.helpContacts.map((contact, contactIndex) =>
        contactIndex === index ? { ...contact, ...patch } : contact)
    }));
  }

  function addHelpContact() {
    setForm((current) => current.helpContacts.length >= 3
      ? current
      : { ...current, helpContacts: [...current.helpContacts, { label: "", phone: "" }] });
  }

  function removeHelpContact(index: number) {
    setForm((current) => ({
      ...current,
      helpContacts: current.helpContacts.filter((_, contactIndex) => contactIndex !== index)
    }));
  }

  function removeTrigger(index: number) {
    setForm((current) => ({
      ...current,
      quizTriggers: current.quizTriggers.filter((_, triggerIndex) => triggerIndex !== index)
    }));
  }

  async function sendNotice(kind: Notice["kind"]) {
    setError(null);
    setNotice(null);
    try {
      if (noticeMessage.length > 200) throw new Error("Notice message must be at most 200 characters");
      const created = await api.sendNotice(id, kind, noticeMessage.trim());
      setNotices((current) => [created, ...current]);
      setNoticeMessage("");
      setNotice(t(kind === "callback"
        ? "Callback request queued. The child sees it on the next notice poll."
        : "Task reminder queued. The child sees it on the next notice poll."));
    } catch (problem) {
      setError(errorText(problem, "Cannot send the notice", t));
    }
  }

  async function replyToHelp(request: HelpRequest) {
    const reply = (helpReplies[request.id] ?? "").trim();
    if (!reply) return;
    setError(null);
    setNotice(null);
    try {
      const updated = await api.respondToHelpRequest(id, request.id, reply);
      setHelpRequests((current) => current.map((item) => item.id === updated.id ? updated : item));
      setHelpReplies((current) => ({ ...current, [request.id]: "" }));
      setNotice(t("Reply queued for the child's next help-request poll."));
    } catch (problem) {
      setError(errorText(problem, "Cannot reply to the help request", t));
    }
  }

  function addPackage(packageName: string) {
    const name = packageName.trim();
    if (!name) return;
    setForm((current) => {
      if (current.apps.some((app) => app.packageName === name)) return current;
      const installed = catalog.find((app) => app.package_name === name);
      return {
        ...current,
        apps: [...current.apps, {
          packageName: name,
          label: installed?.label ?? name,
          category: installed?.category ?? 0,
          dailyMinutes: 60,
          blocked: false,
          alwaysAllowed: false
        }]
      };
    });
    setNewPackage("");
  }

  function applyToCategory(mode: "minutes" | "blocked" | "always") {
    setForm((current) => {
      const byPackage = new Map(current.apps.map((app) => [app.packageName, app]));
      for (const installed of catalog) {
        if (installed.category !== selectedCategory || byPackage.has(installed.package_name)) continue;
        byPackage.set(installed.package_name, {
          packageName: installed.package_name,
          label: installed.label,
          category: installed.category,
          dailyMinutes: 60,
          blocked: false,
          alwaysAllowed: false
        });
      }
      const apps = [...byPackage.values()].map((app) => app.category === selectedCategory
        ? mode === "blocked"
          ? { ...app, blocked: true, alwaysAllowed: false, dailyMinutes: null }
          : mode === "always"
            ? { ...app, blocked: false, alwaysAllowed: true }
            : { ...app, blocked: false, alwaysAllowed: false, dailyMinutes: categoryMinutes }
        : app);
      return { ...current, apps };
    });
  }

  async function grantTemporaryAllowance() {
    if (!grantPackage) return;
    setError(null);
    setNotice(null);
    try {
      const grant = await api.grantApp(id, grantPackage, grantMinutes);
      setGrants((current) => [grant, ...current]);
      setNotice(t("Allowance queued. The child device applies it on its next sync, then reports it back."));
    } catch (problem) {
      setError(errorText(problem, "Cannot create the temporary allowance", t));
    }
  }

  async function revokeTemporaryAllowance(grantId: string) {
    setError(null);
    setNotice(null);
    try {
      const grant = await api.revokeGrant(id, grantId);
      setGrants((current) => current.map((item) => item.id === grant.id ? grant : item));
      setNotice(t("Allowance revoked. The child device removes it on its next sync."));
    } catch (problem) {
      setError(errorText(problem, "Cannot revoke the temporary allowance", t));
    }
  }

  async function triggerQuizNow() {
    if (!quizPackage) return;
    setError(null);
    setNotice(null);
    try {
      const created = await api.triggerQuiz(id, quizPackage);
      setQuizTriggers((current) => [created, ...current.filter((item) => item.id !== created.id)]);
      setNotice(t("Quiz request queued. The child sees it on the next policy sync or remote poll."));
    } catch (problem) {
      setError(errorText(problem, "Cannot trigger the quiz", t));
    }
  }

  async function publish(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const policy = policyFromForm(form);
      const published = await api.publishPolicy(id, policy);
      setEnvelope(published);
      api.policyHistory(id).then(setPolicyHistory).catch(() => undefined);
      setForm(formFromPolicyPayload(published.payload));
      setNotice(t("Published signed revision {revision}. The device will pick it up on its next sync.",
        { revision: published.revision }));
    } catch (problem) {
      setError(errorText(problem, "Cannot publish the policy", t));
    } finally {
      setSaving(false);
    }
  }

  async function rollbackPolicy(revision: number) {
    if (!window.confirm(t("Roll back to policy revision {revision}? The server will publish it as a new, higher revision.",
      { revision }))) return;
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const rolledBack = await api.rollbackPolicy(id, revision);
      setEnvelope(rolledBack);
      setForm(formFromPolicyPayload(rolledBack.payload));
      setPolicyHistory(await api.policyHistory(id));
      setNotice(t("Revision {revision} was restored as signed revision {restored}.",
        { revision, restored: rolledBack.revision }));
    } catch (problem) {
      setError(errorText(problem, "Cannot roll back the policy", t));
    } finally {
      setSaving(false);
    }
  }

  async function revoke() {
    if (!window.confirm(t("Revoke this device? Its token stops working and cached policy remains until the device re-syncs or is wiped."))) return;
    try {
      await api.revokeDevice(id);
      navigate("/");
    } catch (problem) {
      setError(errorText(problem, "Cannot revoke the device", t));
    }
  }

  async function requestCall(kind: CallRequest["kind"]) {
    try {
      const request = await api.requestCall(id, kind);
      setCalls((current) => [request, ...current]);
      setNotice(t("Request sent. The child device must accept it before a LiveKit token is issued."));
    } catch (problem) {
      setError(errorText(problem, "Cannot request a call", t));
    }
  }

  async function join(call: CallRequest) {
    if (call.kind === "screenshot") return;
    try {
      const value = await api.callToken(call.id);
      setCallKind(call.kind);
      setActiveCallId(call.id);
      setCredentials(value);
    } catch (problem) {
      setError(errorText(problem, "The child has not accepted or LiveKit is not configured", t));
    }
  }

  async function endRequest(call: CallRequest) {
    const ended = await api.endCall(call.id);
    setCalls((current) => current.map((item) => item.id === ended.id ? ended : item));
    if (activeCallId === call.id) {
      setActiveCallId(null);
      setCredentials(null);
    }
    setNotice(t(call.kind === "screenshot"
      ? "Screenshot request ended. The child device can no longer upload against it."
      : "Call ended and the LiveKit room was revoked."));
  }

  async function endActiveCall() {
    const call = calls.find((item) => item.id === activeCallId);
    if (call) {
      await endRequest(call);
    } else {
      setActiveCallId(null);
      setCredentials(null);
    }
  }

  async function viewScreenshot(screenshot: Screenshot) {
    try {
      const blob = await api.screenshotImage(screenshot.id);
      setPreview({ id: screenshot.id, url: URL.createObjectURL(blob) });
    } catch (problem) {
      setError(errorText(problem, "Cannot load the screenshot", t));
    }
  }

  if (loading) return <p>{t("Loading device...")}</p>;
  if (!device) return <p className="error">{error ?? t("Device not found")}</p>;

  return (
    <section className="detail-layout">
      <div className="page-title">
        <div>
          <Link to="/">{t("Devices")}</Link>
          <h1>{device.name}</h1>
          <p className="mono">{device.device_id}</p>
        </div>
        <div className="row-actions">
          <span className={`badge ${device.revoked ? "danger" : device.confirmed ? "ok" : "warn"}`}>
            {device.revoked ? t("Revoked") : device.confirmed ? t("Protected") : t("Awaiting confirmation")}
          </span>
          {!device.revoked && <button className="danger-button" onClick={() => void revoke()}>{t("Revoke")}</button>}
        </div>
      </div>

      {error && <p className="error" role="alert">{error}</p>}
      {notice && <p className="notice" role="status">{notice}</p>}

      <form className="card" onSubmit={publish}>
        <div className="page-title">
          <div>
            <h2>{t("Policy")}</h2>
            <p className="muted">
              {envelope
                ? t("Latest signed revision {revision} · key {key}...", {
                  revision: envelope.revision, key: envelope.key_id.slice(0, 12)
                })
                : t("No policy has been published yet.")}
            </p>
          </div>
          <label className="toggle">
            <input type="checkbox" checked={form.enabled}
              onChange={(event) => update("enabled", event.target.checked)} />
            {t("Enable Guard")}
          </label>
        </div>
        <div className="form-grid">
          <label>{t("Timezone (IANA)")}
            <input value={form.timezone} onChange={(event) => update("timezone", event.target.value)} />
          </label>
          <label>{t("Daily quiz attempts")}
            <input type="number" min={0} max={1000} value={form.maxAttemptsPerDay}
              onChange={(event) => update("maxAttemptsPerDay", Number(event.target.value))} />
          </label>
          <label>{t("Reward minutes")}
            <input type="number" min={0} max={1440} value={form.rewardMinutes}
              onChange={(event) => update("rewardMinutes", Number(event.target.value))} />
          </label>
          <label>{t("Wrong-answer cooldown (seconds)")}
            <input type="number" min={0} max={86400} value={form.cooldownSeconds}
              onChange={(event) => update("cooldownSeconds", Number(event.target.value))} />
          </label>
          <label>{t("Budget warning (minutes remaining)")}
            <input type="number" min={0} max={1440} value={form.budgetWarningMinutes}
              onChange={(event) => update("budgetWarningMinutes", Number(event.target.value))} />
          </label>
          <label>{t("Continuous-use reminder (minutes)")}
            <input type="number" min={0} max={1440} value={form.continuousUseReminderMinutes}
              onChange={(event) => update("continuousUseReminderMinutes", Number(event.target.value))} />
          </label>
          <p className="hint wide">
            {t("Set either reminder to 0 to disable it. The child device shows a local notification once per app per day when a daily budget is within the warning threshold, and once per foreground session when continuous use reaches the reminder threshold. Always-allowed, always-blocked and protected apps are never reminded.")}
          </p>
          <label className="toggle wide">
            <input type="checkbox" checked={form.launcherEnabled}
              onChange={(event) => update("launcherEnabled", event.target.checked)} />
            {t("Use Guard as the managed HOME launcher on the child device")}
          </label>
          <label className="toggle wide">
            <input type="checkbox" checked={form.quizGateEnabled}
              onChange={(event) => update("quizGateEnabled", event.target.checked)} />
            {t("Allow a signed quiz reward after the daily budget is exhausted")}
          </label>
          <div className="wide quiz-filter-editor">
            <h3>{t("Quiz pack filters")}</h3>
            <p className="hint">
              {t("Blank lists mean no restriction. Comma-separated values are matched against signed question metadata. The child device must already trust and import a signed pack whose questions carry that metadata.")}
            </p>
            <div className="form-grid">
              <label>{t("Questions per reward attempt")}
                <input type="number" min={1} max={10} value={form.quizQuestionCount}
                  onChange={(event) => update("quizQuestionCount", Number(event.target.value))} />
              </label>
              <label>{t("Subjects")}
                <input placeholder={t("mathematics, science")} value={form.quizSubjects}
                  onChange={(event) => update("quizSubjects", event.target.value)} />
              </label>
              <label>{t("Grades (0 = unspecified)")}
                <input placeholder={t("3, 4, 5")} value={form.quizGrades}
                  onChange={(event) => update("quizGrades", event.target.value)} />
              </label>
              <label>{t("Difficulties")}
                <input placeholder={t("easy, medium")} value={form.quizDifficulties}
                  onChange={(event) => update("quizDifficulties", event.target.value)} />
              </label>
              <label>{t("Languages")}
                <input placeholder={t("en, zh-CN")} value={form.quizLanguages}
                  onChange={(event) => update("quizLanguages", event.target.value)} />
              </label>
              <label>{t("Tags")}
                <input placeholder={t("arithmetic, fractions")} value={form.quizTags}
                  onChange={(event) => update("quizTags", event.target.value)} />
              </label>
            </div>
          </div>
          <div className="wide quiz-filter-editor">
            <h3>{t("Task-page emergency contacts")}</h3>
            <p className="hint">
              {t("These contacts appear on the child task page and open the system dialer after a tap. Guard never places a call or sends a message automatically. Leave the list empty if no trusted numbers should be shown.")}
            </p>
            <div className="row-actions">
              <button type="button" className="secondary" disabled={form.helpContacts.length >= 3}
                onClick={addHelpContact}>{t("Add contact")}</button>
              <span className="hint">{t("{count} of 3 contacts", { count: form.helpContacts.length })}</span>
            </div>
            {form.helpContacts.length === 0
              ? <p className="empty">{t("No task-page contacts configured.")}</p>
              : form.helpContacts.map((contact, index) => (
                <article className="question-card" key={index}>
                  <div className="form-grid">
                    <label>{t("Label")}
                      <input maxLength={40} placeholder={t("Parent, school office, trusted adult")}
                        value={contact.label}
                        onChange={(event) => updateHelpContact(index, { label: event.target.value })} />
                    </label>
                    <label>{t("Phone number")}
                      <input maxLength={24} inputMode="tel" placeholder="+86 138 0000 0000"
                        value={contact.phone}
                        onChange={(event) => updateHelpContact(index, { phone: event.target.value })} />
                    </label>
                  </div>
                  <button type="button" className="link-button"
                    onClick={() => removeHelpContact(index)}>{t("Remove contact")}</button>
                </article>
              ))}
          </div>
          <div className="wide quiz-filter-editor">
            <h3>{t("Automatic quiz triggers")}</h3>
            <p className="hint">
              {t("Trigger targets must also have a daily budget or allowed window. Trigger rules are part of the signed policy; always-blocked, system, dialer, HOME and Guard apps are rejected.")}
            </p>
            <div className="row-actions">
              <button type="button" className="secondary" onClick={() => addTrigger("total_usage")}>{t("Add total-usage trigger")}</button>
              <button type="button" className="secondary" onClick={() => addTrigger("app_enter")}>{t("Add app-entry trigger")}</button>
              <button type="button" className="secondary" onClick={() => addTrigger("continuous_use")}>{t("Add continuous-use trigger")}</button>
              <button type="button" className="secondary" onClick={() => addTrigger("schedule_start")}>{t("Add schedule-start trigger")}</button>
            </div>
            {form.quizTriggers.length === 0
              ? <p className="empty">{t("No automatic quiz triggers. The child can still open the quiz manually.")}</p>
              : form.quizTriggers.map((trigger, index) => (
                <article className="question-card trigger-card" key={index}>
                  <div className="page-title">
                    <strong>{t("Trigger {number}", { number: index + 1 })}</strong>
                    <select value={trigger.type}
                      onChange={(event) => updateTrigger(index, { type: event.target.value as QuizTriggerType })}>
                      <option value="total_usage">{t("Total usage")}</option>
                      <option value="app_enter">{t("App entry")}</option>
                      <option value="continuous_use">{t("Continuous foreground use")}</option>
                      <option value="schedule_start">{t("Schedule start")}</option>
                    </select>
                  </div>
                  <div className="form-grid">
                    <label className="wide">{t("Target packages (comma separated)")}
                      <input value={trigger.packages}
                        onChange={(event) => updateTrigger(index, { packages: event.target.value })} />
                    </label>
                    {(trigger.type === "total_usage" || trigger.type === "continuous_use") && (
                      <label>{t(trigger.type === "total_usage" ? "Combined usage threshold (minutes)" : "Continuous use threshold (minutes)")}
                        <input type="number" min={1} max={1440} value={trigger.minutes}
                          onChange={(event) => updateTrigger(index, { minutes: Number(event.target.value) })} />
                      </label>
                    )}
                    {trigger.type === "app_enter" && (
                      <label>{t("Delay after entry (seconds)")}
                        <input type="number" min={0} max={3600} value={trigger.delaySeconds}
                          onChange={(event) => updateTrigger(index, { delaySeconds: Number(event.target.value) })} />
                      </label>
                    )}
                    {trigger.type === "schedule_start" && (
                      <>
                        <label>{t("Days (1 = Monday, 7 = Sunday)")}
                          <input value={trigger.days}
                            onChange={(event) => updateTrigger(index, { days: event.target.value })} />
                        </label>
                        <label>{t("Start minute (0-1439)")}
                          <input type="number" min={0} max={1439} value={trigger.startMinute}
                            onChange={(event) => updateTrigger(index, { startMinute: Number(event.target.value) })} />
                        </label>
                      </>
                    )}
                  </div>
                  <button type="button" className="link-button" onClick={() => removeTrigger(index)}>{t("Remove trigger")}</button>
                </article>
              ))}
          </div>
          <div className="wide app-policy-editor">
            <h3>{t("App policies")}</h3>
            <p className="hint">{t("Installed apps are reported by the device on sync. A blocked app stays blocked and cannot be temporarily allowed. An always-allowed app bypasses daily budgets, allowed windows and quiz gates.")}</p>
            <div className="row-actions category-actions">
              <select value={selectedCategory} onChange={(event) => setSelectedCategory(Number(event.target.value))}>
                {Object.entries(CATEGORY_LABELS).map(([value, label]) =>
                  <option key={value} value={value}>{t(label)}</option>)}
              </select>
              <input type="number" min={0} max={1440} value={categoryMinutes}
                onChange={(event) => setCategoryMinutes(Number(event.target.value))} />
              <button type="button" className="secondary" onClick={() => applyToCategory("minutes")}>{t("Apply minutes to category")}</button>
              <button type="button" className="secondary" onClick={() => applyToCategory("blocked")}>{t("Block category")}</button>
              <button type="button" className="secondary" onClick={() => applyToCategory("always")}>{t("Always allow category")}</button>
            </div>
            <div className="table-wrap"><table>
              <thead><tr><th>{t("App")}</th><th>{t("Category")}</th><th>{t("Daily minutes")}</th><th>{t("Blocked")}</th><th>{t("Always allowed")}</th><th /></tr></thead>
              <tbody>
                {form.apps.length === 0 ? <tr><td colSpan={6} className="empty">{t("No app policies yet. Add an installed app below.")}</td></tr> :
                  form.apps.map((app, index) => (
                    <tr key={app.packageName}>
                      <td><strong>{app.label}</strong><div className="mono">{app.packageName}</div></td>
                      <td>{CATEGORY_LABELS[app.category] ? t(CATEGORY_LABELS[app.category]) : t("Category {category}", { category: app.category })}</td>
                      <td><input type="number" min={0} max={1440} disabled={app.blocked || app.alwaysAllowed}
                        value={app.dailyMinutes ?? ""}
                        onChange={(event) => updateApp(index, {
                          dailyMinutes: event.target.value === "" ? null : Number(event.target.value)
                        })} /></td>
                      <td><input type="checkbox" checked={app.blocked}
                        onChange={(event) => updateApp(index, {
                          blocked: event.target.checked,
                          alwaysAllowed: event.target.checked ? false : app.alwaysAllowed
                        })} /></td>
                      <td><input type="checkbox" checked={app.alwaysAllowed}
                        onChange={(event) => updateApp(index, {
                          alwaysAllowed: event.target.checked,
                          blocked: event.target.checked ? false : app.blocked
                        })} /></td>
                      <td><button type="button" className="link-button" onClick={() => removeApp(index)}>{t("Remove")}</button></td>
                    </tr>
                  ))}
              </tbody>
            </table></div>
            <div className="row-actions">
              <select value="" onChange={(event) => addPackage(event.target.value)}>
                <option value="">{t("Add installed app...")}</option>
                {catalog.filter((app) => !form.apps.some((row) => row.packageName === app.package_name))
                  .map((app) => <option key={app.package_name} value={app.package_name}>{app.label} ({app.package_name})</option>)}
              </select>
              <input placeholder={t("or enter package name")} value={newPackage}
                onChange={(event) => setNewPackage(event.target.value)} />
              <button type="button" className="secondary" onClick={() => addPackage(newPackage)}>{t("Add package")}</button>
            </div>
          </div>
          <label className="wide">{t("Allowed windows (JSON; day 1 = Monday, start in minutes)")}
            <textarea rows={8} value={form.schedulesText}
              onChange={(event) => update("schedulesText", event.target.value)} />
          </label>
        </div>
        <div className="row-actions">
          <button className="primary" disabled={saving}>{saving ? t("Signing and publishing...") : t("Publish signed policy")}</button>
          <span className="hint">{t("The server replaces revision with a strictly increasing value.")}</span>
        </div>
        {envelope && <details className="wide">
          <summary>{t("Signed envelope")}</summary>
          <pre>{JSON.stringify(envelope, null, 2)}</pre>
        </details>}
        <details className="wide">
          <summary>{t("Revision history ({count})", { count: policyHistory.length })}</summary>
          {policyHistory.length === 0 ? <p className="empty">{t("No signed revisions yet.")}</p> :
            <ul className="call-list">
              {policyHistory.map((revision) => (
                <li key={revision.revision}>
                  <span className="mono">{t("Revision {revision}", { revision: revision.revision })}</span>
                  {envelope?.revision === revision.revision && <span className="badge ok">{t("current")}</span>}
                  <span className="muted">{t("key {key}...", { key: revision.key_id.slice(0, 12) })}</span>
                  <span className="muted">{formatDate(revision.created_at)}</span>
                  {envelope?.revision !== revision.revision && <button type="button" className="secondary"
                    onClick={() => void rollbackPolicy(revision.revision)} disabled={saving}>
                    {t("Roll back to this revision")}
                  </button>}
                </li>
              ))}
            </ul>}
          <p className="hint">{t("Rollback validates the historical payload again and signs it as a new revision, so devices never move backwards.")}</p>
        </details>
      </form>

      <section className="card">
        <h2>{t("Usage summary")}</h2>
        {usage.length === 0 ? <p className="empty">{t("No usage summaries uploaded yet.")}</p> :
          <div className="table-wrap"><table>
            <thead><tr><th>{t("Day")}</th><th>{t("Apps")}</th><th>{t("Health")}</th></tr></thead>
            <tbody>
              {usage.map((report) => (
                <tr key={report.day}>
                  <td>{report.day}</td>
                  <td>{Object.entries(report.app_minutes)
                    .sort((a, b) => b[1] - a[1])
                    .map(([name, minutes]) => <div key={name}><span className="mono">{name}</span> · {t("{minutes} min", { minutes })}</div>)}</td>
                  <td><code>{JSON.stringify(report.health)}</code></td>
                </tr>
              ))}
            </tbody>
          </table></div>}
      </section>

      <section className="card">
        <h2>{t("Child help requests")}</h2>
        <p className="muted">
          {t("These are requests the child explicitly sent from the task page. A request never changes time limits or unlocks an app. Replies are delivered on the child&apos;s next sync.")}
        </p>
        {helpRequests.length === 0 ? <p className="empty">{t("No help requests have been sent.")}</p> : (
          <ul className="call-list">
            {helpRequests.map((request) => (
              <li key={request.id}>
                <div>
                  <strong>{t(request.reason.replaceAll("_", " "))}</strong>
                  {request.target_package && <span className="mono"> {request.target_package}</span>}
                  <div className="muted">{formatDate(request.created_at)}</div>
                </div>
                <span className={`badge ${request.status === "answered" || request.status === "acknowledged"
                  ? "ok" : request.status === "pending" ? "warn" : "danger"}`}>{t(request.status)}</span>
                {request.message && <span>{request.message}</span>}
                {request.parent_reply && <span className="notice">{t("Your reply: {reply}", { reply: request.parent_reply })}</span>}
                {request.status !== "expired" && (
                  <span className="row-actions">
                    <input maxLength={1000} placeholder={t("Write a short reply")}
                      value={helpReplies[request.id] ?? ""}
                      onChange={(event) => setHelpReplies((current) =>
                        ({ ...current, [request.id]: event.target.value }))} />
                    <button className="secondary" disabled={!(helpReplies[request.id] ?? "").trim()}
                      onClick={() => void replyToHelp(request)}>{t("Reply")}</button>
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card">
        <div className="page-title">
          <div>
            <h2>{t("Parent-triggered quiz")}</h2>
            <p className="muted">
              {t("The child device still applies the signed quiz gate, reward, cooldown, daily-attempt and quiz-pack checks. It picks the request up on its next sync or remote poll.")}
            </p>
          </div>
          <div className="row-actions">
            <select value={quizPackage} onChange={(event) => setQuizPackage(event.target.value)}>
              <option value="">{t("Choose managed app...")}</option>
              {form.apps
                .filter((app) => !app.blocked && !app.alwaysAllowed
                  && (app.dailyMinutes !== null || form.schedulesText.includes(`"${app.packageName}"`)))
                .map((app) => <option key={app.packageName} value={app.packageName}>{app.label}</option>)}
            </select>
            <button className="secondary" disabled={!quizPackage || !form.quizGateEnabled}
              onClick={() => void triggerQuizNow()}>{t("Trigger quiz now")}</button>
          </div>
        </div>
        {!form.quizGateEnabled && <p className="hint">{t("Enable the quiz gate and publish the policy first.")}</p>}
        {quizTriggers.length === 0 ? <p className="empty">{t("No parent-triggered quizzes.")}</p> : <ul className="call-list">
          {quizTriggers.map((item) => (
            <li key={item.id}>
              <span className="mono">{item.package_name}</span>
              <span className={`badge ${item.status === "completed" ? "ok"
                : item.status === "pending" || item.status === "accepted" ? "warn" : "danger"}`}>
                {t(item.status)}
              </span>
              {item.detail && <span className="muted">{item.detail}</span>}
              <span className="muted">{formatDate(item.created_at)}</span>
            </li>
          ))}
        </ul>}
      </section>

      <section className="card">
        <h2>{t("Quiz results")}</h2>
        <p className="muted">{t("Only answer metadata is uploaded: question ids, correctness, filters and the rewarded outcome. Free-text answers are not sent to the parent service.")}</p>
        {quizResults.length === 0 ? <p className="empty">{t("No quiz attempts have been uploaded yet.")}</p> :
          <div className="quiz-result-list">
            {quizResults.map((result) => (
              <article className="question-card" key={result.id}>
                <div className="page-title">
                  <div>
                    <strong className="mono">{result.target_package}</strong>
                    <div className="muted">{formatDate(result.occurred_at)}</div>
                  </div>
                  <div className="row-actions">
                    <span className={`badge ${result.correct_count === result.question_count ? "ok" : "warn"}`}>
                      {t("{correct}/{total} correct", { correct: result.correct_count, total: result.question_count })}
                    </span>
                    {result.reward_granted && <span className="badge ok">{t("Reward granted")}</span>}
                  </div>
                </div>
                <p className="hint">
                  {t("Policy revision {revision} · quiz version {version}{trigger}", {
                    revision: result.policy_revision,
                    version: result.quiz_version,
                    trigger: result.trigger_id ? t(" · {trigger}", { trigger: result.trigger_id }) : t(" · manual attempt")
                  })}
                </p>
                <div className="row-actions">
                  {result.questions.map((question) => (
                    <span className={`badge ${question.correct ? "ok" : "danger"}`} key={question.question_id}>
                      {question.question_id} {t(question.correct ? "correct" : "wrong")}
                    </span>
                  ))}
                </div>
                <p className="hint">
                  {[...new Set(result.questions.map((question) =>
                    [t(question.subject), question.grade ? t("grade {grade}", { grade: question.grade }) : "",
                      t(question.difficulty), question.language].filter(Boolean).join(" · ")))].join(" · ")}
                </p>
              </article>
            ))}
          </div>}
      </section>

      <section className="card">
        <div className="page-title">
          <div>
            <h2>{t("Temporary allowance")}</h2>
            <p className="muted">{t("The child device applies one queued allowance after its next successful sync. It cannot override an always-blocked package.")}</p>
          </div>
          <div className="row-actions">
            <select value={grantPackage} onChange={(event) => setGrantPackage(event.target.value)}>
              <option value="">{t("Choose app...")}</option>
              {form.apps.filter((app) => !app.blocked && !app.alwaysAllowed).map((app) =>
                <option key={app.packageName} value={app.packageName}>{app.label}</option>)}
            </select>
            <input type="number" min={1} max={1440} value={grantMinutes}
              onChange={(event) => setGrantMinutes(Number(event.target.value))} />
            <button className="secondary" disabled={!grantPackage}
              onClick={() => void grantTemporaryAllowance()}>{t("Grant once")}</button>
          </div>
        </div>
        {grants.length === 0 ? <p className="empty">{t("No temporary allowances.")}</p> : <ul className="call-list">
          {grants.map((grant) => {
            const expired = !grant.revoked && new Date(grant.expires_at).getTime() <= Date.now();
            const status = grant.revoked ? "Revoked" : grant.applied ? "Applied" : expired ? "Expired" : "Queued";
            const badge = grant.revoked ? "danger" : grant.applied ? "ok" : expired ? "neutral" : "warn";
            return (
              <li key={grant.id}>
                <span className="mono">{grant.package_name}</span>
                <span>{t("{minutes} min", { minutes: grant.minutes })}</span>
                <span className={`badge ${badge}`}>{t(status)}</span>
                <span className="muted">{formatDate(grant.created_at)}</span>
                {grant.revoked && grant.revoked_at
                  ? <span className="muted">{t("Revoked {date}", { date: formatDate(grant.revoked_at) })}</span>
                  : null}
                {!grant.revoked && !expired
                  ? <button className="secondary" onClick={() => void revokeTemporaryAllowance(grant.id)}>{t("Revoke")}</button>
                  : null}
              </li>
            );
          })}
        </ul>}
      </section>

      <section className="card">
        <div className="page-title">
          <div>
            <h2>{t("Authorized contact")}</h2>
            <p className="muted">{t("A request is not a call. The child device must explicitly accept it, and media is not recorded.")}</p>
          </div>
          <div className="row-actions">
            <button className="secondary" onClick={() => void requestCall("audio")}>{t("Voice")}</button>
            <button className="secondary" onClick={() => void requestCall("video")}>{t("Video")}</button>
            <button className="secondary" onClick={() => void requestCall("screen")}>{t("Screen share")}</button>
            <button className="secondary" onClick={() => void requestCall("screenshot")}>{t("Screenshot")}</button>
          </div>
        </div>
        {calls.length === 0 ? <p className="empty">{t("No call requests.")}</p> : <ul className="call-list">
          {calls.map((call) => (
            <li key={call.id}>
              <span>{t(call.kind)}</span>
              <span className={`badge ${call.status === "accepted" ? "ok" : call.status === "pending" ? "warn" : call.status === "ended" ? "neutral" : "danger"}`}>{t(call.status)}</span>
              <span className="muted">{formatTime(call.created_at)}</span>
              {call.ended_at && <span className="muted">{t("ended {time}", { time: formatTime(call.ended_at) })}</span>}
              {call.status === "accepted" && call.kind !== "screenshot" &&
                <button className="secondary" onClick={() => void join(call)}>{t("Join")}</button>}
              {call.status === "accepted" && call.kind === "screenshot" &&
                <span className="muted">{t("Waiting for the child to tap capture")}</span>}
              {call.status === "accepted" && call.id !== activeCallId &&
                <button className="danger-button" onClick={() => void endRequest(call).catch((problem) => {
                  setError(errorText(problem, "Cannot end this request", t));
                })}>{t("End")}</button>}
            </li>
          ))}
        </ul>}
        {credentials && <Suspense fallback={<p className="muted">{t("Loading media client...")}</p>}>
          <CallPanel credentials={credentials} kind={callKind} onEnd={endActiveCall} />
        </Suspense>}
      </section>

      <section className="card">
        <div className="page-title">
          <div>
            <h2>{t("Visible notices")}</h2>
            <p className="muted">{t("These are visible child-device notifications, not background monitoring. Acknowledgements are recorded.")}</p>
          </div>
          <div className="row-actions">
            <input maxLength={200} placeholder={t("Optional message (200 characters)")}
              value={noticeMessage} onChange={(event) => setNoticeMessage(event.target.value)} />
            <button className="secondary" onClick={() => void sendNotice("callback")}>{t("Ask to call back")}</button>
            <button className="secondary" onClick={() => void sendNotice("task")}>{t("Ask to do a task")}</button>
          </div>
        </div>
        {notices.length === 0 ? <p className="empty">{t("No notices sent yet.")}</p> : <ul className="call-list">
          {notices.map((item) => (
            <li key={item.id}>
              <span>{t(item.kind === "callback" ? "Please call back" : "Please complete the task")}</span>
              <span className={`badge ${item.status === "acknowledged" ? "ok" : item.status === "pending" ? "warn" : "danger"}`}>{t(item.status)}</span>
              {item.message && <span>{item.message}</span>}
              <span className="muted">{formatDate(item.created_at)}</span>
            </li>
          ))}
        </ul>}
        {notices.some((item) => item.status === "acknowledged") && <p className="hint">{t("The latest acknowledged notice remains in this history until retention cleanup.")}</p>}
      </section>

      <section className="card">
        <div className="page-title">
          <div>
            <h2>{t("Consented screenshots")}</h2>
            <p className="muted">{t("One-shot captures expire 15 minutes after upload. The child must approve Android's screen-capture dialog for every request.")}</p>
          </div>
        </div>
        {screenshots.length === 0 ? <p className="empty">{t("No screenshots available.")}</p> : <ul className="call-list">
          {screenshots.map((screenshot) => (
            <li key={screenshot.id}>
              <span className="mono">{screenshot.width} x {screenshot.height}</span>
              <span className="muted">{formatDate(screenshot.created_at)}</span>
              <span className="badge warn">{t("Expires {time}", { time: formatTime(screenshot.expires_at) })}</span>
              <button className="secondary" onClick={() => void viewScreenshot(screenshot)}>{t("View")}</button>
            </li>
          ))}
        </ul>}
        {preview && <div className="screenshot-preview">
          <button className="secondary" onClick={() => setPreview(null)}>{t("Close image")}</button>
          <img src={preview.url} alt={t("Consented screenshot {id}", { id: preview.id })} />
        </div>}
      </section>

      <section className="card">
        <h2>{t("Audit")}</h2>
        {audit.length === 0 ? <p className="empty">{t("No audit events.")}</p> : <ul className="audit-list">
          {audit.map((event) => (
            <li key={event.id}>
              <span className="muted">{formatDate(event.created_at)}</span>
              <strong>{event.event}</strong>
              <span className="mono">{event.detail}</span>
            </li>
          ))}
        </ul>}
      </section>
    </section>
  );
}
