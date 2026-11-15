import { useEffect, useState, type FormEvent } from "react";
import { api, type Parent, type ParentSession } from "../api";

export function AccountPage({ parent, onSignedOut }: { parent: Parent; onSignedOut: () => void }) {
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
      setSessionError(failure instanceof Error ? failure.message : "Cannot load browser sessions");
    } finally {
      setSessionsLoading(false);
    }
  }

  async function revokeSession(session: ParentSession) {
    const prompt = session.current
      ? "Revoke this browser session and sign out now?"
      : "Revoke this browser session? It will need to sign in again.";
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
      setSessionNotice("Browser session revoked.");
      await loadSessions();
    } catch (failure) {
      setSessionError(failure instanceof Error ? failure.message : "Cannot revoke the browser session");
    } finally {
      setSessionBusy(null);
    }
  }

  async function revokeOthers() {
    if (!window.confirm("Revoke every other browser session for this account?")) return;
    setSessionBusy("others");
    setSessionError(null);
    setSessionNotice(null);
    try {
      const result = await api.revokeOtherParentSessions();
      setSessionNotice(`Revoked ${result.revoked} other browser session${result.revoked === 1 ? "" : "s"}.`);
      await loadSessions();
    } catch (failure) {
      setSessionError(failure instanceof Error ? failure.message : "Cannot revoke other browser sessions");
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
      setError(failure instanceof Error ? failure.message : "The account could not be deleted");
      setBusy(false);
    }
  }

  return (
    <section>
      <div className="page-title">
        <div>
          <h2>Browser sessions</h2>
          <p className="muted">Each sign-in has its own revocable server-side session. Revoking one does not expose or change your password.</p>
        </div>
        <button className="secondary" onClick={() => void revokeOthers()}
          disabled={sessionBusy !== null || sessions.every((session) => session.current || session.status !== "active")}>
          {sessionBusy === "others" ? "Revoking..." : "Revoke other sessions"}
        </button>
      </div>
      <div className="card">
        {sessionError && <p className="error" role="alert">{sessionError}</p>}
        {sessionNotice && <p className="notice" role="status">{sessionNotice}</p>}
        {sessionsLoading ? <p className="muted">Loading sessions...</p> : sessions.length === 0
          ? <p className="empty">No browser sessions recorded.</p>
          : <ul className="call-list">
            {sessions.map((session) => (
              <li key={session.id}>
                <span className={`badge ${session.status === "active" ? "ok" : session.status === "revoked" ? "danger" : "neutral"}`}>
                  {session.status}
                </span>
                {session.current && <span className="badge neutral">this browser</span>}
                <span className="muted">Signed in {new Date(session.created_at).toLocaleString()}</span>
                <span className="muted">Last seen {new Date(session.last_seen_at).toLocaleString()}</span>
                <span className="muted">Expires {new Date(session.expires_at).toLocaleString()}</span>
                {session.status === "active" && <button className="secondary"
                  onClick={() => void revokeSession(session)}
                  disabled={sessionBusy !== null}>
                  {session.current ? "Revoke and sign out" : "Revoke"}
                </button>}
              </li>
            ))}
          </ul>}
        <p className="hint">Revocation is checked by the server on every authenticated request. Existing tokens cannot bypass a revoked session.</p>
      </div>

      <div className="page-title">
        <div>
          <h2>Account and data deletion</h2>
          <p className="muted">Permanently remove this parent account and all data managed through it.</p>
        </div>
      </div>

      <div className="card danger-card">
        <h3>This action cannot be undone</h3>
        <p>Deletion removes the account, paired devices, signed policies, usage reports, audit events,
          quiz packs, quiz results, help requests, notices, screenshots and trusted upstream configuration.</p>
        <ul className="danger-list">
          <li>Every paired child device token stops working immediately.</li>
          <li>Existing sign-in tokens for this account stop working.</li>
          <li>Data cannot be recovered from the Guard application after deletion.</li>
        </ul>

        {error && <p className="error" role="alert">{error}</p>}

        <form className="account-delete-form" onSubmit={submit}>
          <label>
            Account email
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
            Current password
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              required
            />
          </label>
          <label>
            Type DELETE to confirm
            <input
              value={confirmation}
              onChange={(event) => setConfirmation(event.target.value)}
              autoComplete="off"
              spellCheck={false}
              required
            />
          </label>
          <p className="hint">The email must exactly match {parent.email}. The server verifies your password again.</p>
          <button className="danger-button" type="submit" disabled={!matches || busy}>
            {busy ? "Deleting account and data…" : "Permanently delete account and data"}
          </button>
        </form>
      </div>
    </section>
  );
}
