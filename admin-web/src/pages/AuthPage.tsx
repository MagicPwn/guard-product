import { useState, type FormEvent } from "react";
import { api, setToken, type AuthResponse } from "../api";

export function AuthPage({ onAuthenticated }: { onAuthenticated: (value: AuthResponse) => void }) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [working, setWorking] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setWorking(true);
    setError(null);
    try {
      const result = mode === "login"
        ? await api.login(email.trim(), password)
        : await api.register(email.trim(), password);
      setToken(result.token);
      onAuthenticated(result);
    } catch (problem) {
      setError(problem instanceof Error ? problem.message : "Sign-in failed");
    } finally {
      setWorking(false);
    }
  }

  return (
    <main className="auth-layout">
      <section className="auth-hero">
        <img src="/icon.svg" alt="" width="64" height="64" />
        <h1>Guard Parent Console</h1>
        <p>Manage screen time on the devices you have paired, review usage, and request a visible call or screen share.</p>
        <ul>
          <li>Policies are signed before they reach the child device.</li>
          <li>Usage summaries contain app minutes, not messages or keystrokes.</li>
          <li>Camera, microphone, and screen sharing always require the child to accept.</li>
        </ul>
      </section>
      <form className="card auth-card" onSubmit={submit}>
        <h2>{mode === "login" ? "Sign in" : "Create a parent account"}</h2>
        <label>
          Email
          <input type="email" autoComplete="email" required value={email}
            onChange={(event) => setEmail(event.target.value)} />
        </label>
        <label>
          Password
          <input type="password" minLength={10} maxLength={256} required value={password}
            autoComplete={mode === "login" ? "current-password" : "new-password"}
            onChange={(event) => setPassword(event.target.value)} />
        </label>
        {mode === "register" && <p className="hint">Use at least 10 characters. Store the recovery path separately.</p>}
        {error && <p className="error" role="alert">{error}</p>}
        <button className="primary" disabled={working}>
          {working ? "Please wait?" : mode === "login" ? "Sign in" : "Create account"}
        </button>
        <button type="button" className="link-button" onClick={() => {
          setMode(mode === "login" ? "register" : "login");
          setError(null);
        }}>
          {mode === "login" ? "Create an account" : "I already have an account"}
        </button>
      </form>
    </main>
  );
}
