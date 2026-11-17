import { useEffect, useState, type FormEvent } from "react";
import { api, type Parent, type ParentSession } from "../api";
import { errorText, useI18n } from "../i18n";

export function AccountPage({ parent, onSignedOut }: { parent: Parent; onSignedOut: () => void }) {
  const { t, formatDate } = useI18n();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sessions, setSessions] = useState<ParentSession[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(true);
  const [sessionBusy, setSessionBusy] = useState<string | null>(null);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const [sessionNotice, setSessionNotice] = useState<string | null>(null);

  useEffect(() => {
    void loadSessions();
  }, []);

  async function loadSessions() {
    setSessionsLoading(true);
    setSessionError(null);
    try {
      setSessions(await api.parentSessions());
    } catch (failure) {
      setSessionError(errorText(failure, "Cannot load browser sessions", t));
    } finally {
      setSessionsLoading(false);
    }
  }

  async function revokeSession(session: ParentSession) {
    const prompt = session.current
      ? t("Revoke this browser session and sign out now?")
      : t("Revoke this browser session? It will need to sign in again.");
    if (!window.confirm(prompt)) return;
    setSessionBusy(session.id);
    setSessionError(null);
    setSessionNotice(null);
    try {
      await api.revokeParentSession(session.id);
      if (session.current) {
        onSignedOut();
        return;
      }
      setSessionNotice(t("Browser session revoked."));
      await loadSessions();
    } catch (failure) {
      setSessionError(errorText(failure, "Cannot revoke the browser session", t));
    } finally {
      setSessionBusy(null);
    }
  }

  async function revokeOthers() {
    if (!window.confirm(t("Revoke every other browser session for this account?"))) return;
    setSessionBusy("others");
    setSessionError(null);
    setSessionNotice(null);
    try {
      const result = await api.revokeOtherParentSessions();
      setSessionNotice(t("Revoked {count} other browser sessions.", { count: result.revoked }));
      await loadSessions();
    } catch (failure) {
      setSessionError(errorText(failure, "Cannot revoke other browser sessions", t));
    } finally {
      setSessionBusy(null);
    }
  }

  const matches = email.trim().toLowerCase() === parent.email.toLowerCase()
    && password.length > 0
    && confirmation === "DELETE";

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!matches || busy) return;
    setBusy(true);
    setError(null);
    try {
      await api.deleteAccount(email.trim(), password, confirmation);
      onSignedOut();
    } catch (failure) {
      setError(errorText(failure, "The account could not be deleted", t));
      setBusy(false);
    }
  }

  return (
    <section>
      <div className="page-title">
        <div>
          <h2>{t("Browser sessions")}</h2>
          <p className="muted">{t("Each sign-in has its own revocable server-side session. Revoking one does not expose or change your password.")}</p>
        </div>
        <button className="secondary" onClick={() => void revokeOthers()}
          disabled={sessionBusy !== null || sessions.every((session) => session.current || session.status !== "active")}>
          {sessionBusy === "others" ? t("Revoking...") : t("Revoke other sessions")}
        </button>
      </div>
      <div className="card">
        {sessionError && <p className="error" role="alert">{sessionError}</p>}
        {sessionNotice && <p className="notice" role="status">{sessionNotice}</p>}
        {sessionsLoading ? <p className="muted">{t("Loading sessions...")}</p> : sessions.length === 0
          ? <p className="empty">{t("No browser sessions recorded.")}</p>
          : <ul className="call-list">
            {sessions.map((session) => (
              <li key={session.id}>
                <span className={`badge ${session.status === "active" ? "ok" : session.status === "revoked" ? "danger" : "neutral"}`}>
                  {t(session.status)}
                </span>
                {session.current && <span className="badge neutral">{t("this browser")}</span>}
                <span className="muted">{t("Signed in {date}", { date: formatDate(session.created_at) })}</span>
                <span className="muted">{t("Last seen {date}", { date: formatDate(session.last_seen_at) })}</span>
                <span className="muted">{t("Expires {date}", { date: formatDate(session.expires_at) })}</span>
                {session.status === "active" && <button className="secondary"
                  onClick={() => void revokeSession(session)}
                  disabled={sessionBusy !== null}>
                  {session.current ? t("Revoke and sign out") : t("Revoke")}
                </button>}
              </li>
            ))}
          </ul>}
        <p className="hint">{t("Revocation is checked by the server on every authenticated request. Existing tokens cannot bypass a revoked session.")}</p>
      </div>

      <div className="page-title">
        <div>
          <h2>{t("Account and data deletion")}</h2>
          <p className="muted">{t("Permanently remove this parent account and all data managed through it.")}</p>
        </div>
      </div>

      <div className="card danger-card">
        <h3>{t("This action cannot be undone")}</h3>
        <p>{t("Deletion removes the account, paired devices, signed policies, usage reports, audit events, quiz packs, quiz results, help requests, notices, screenshots and trusted upstream configuration.")}</p>
        <ul className="danger-list">
          <li>{t("Every paired child device token stops working immediately.")}</li>
          <li>{t("Existing sign-in tokens for this account stop working.")}</li>
          <li>{t("Data cannot be recovered from the Guard application after deletion.")}</li>
        </ul>

        {error && <p className="error" role="alert">{error}</p>}

        <form className="account-delete-form" onSubmit={submit}>
          <label>
            {t("Account email")}
            <input
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              placeholder={parent.email}
              autoComplete="email"
              required
            />
          </label>
          <label>
            {t("Current password")}
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              required
            />
          </label>
          <label>
            {t("Type DELETE to confirm")}
            <input
              value={confirmation}
              onChange={(event) => setConfirmation(event.target.value)}
              autoComplete="off"
              spellCheck={false}
              required
            />
          </label>
          <p className="hint">{t("The email must exactly match {email}. The server verifies your password again.", { email: parent.email })}</p>
          <button className="danger-button" type="submit" disabled={!matches || busy}>
            {busy ? t("Deleting account and data...") : t("Permanently delete account and data")}
          </button>
        </form>
      </div>
    </section>
  );
}
