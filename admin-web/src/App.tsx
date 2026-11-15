import { useEffect, useState } from "react";
import { BrowserRouter, Link, Navigate, Route, Routes } from "react-router-dom";
import { api, type Parent, setToken } from "./api";
import { AuthPage } from "./pages/AuthPage";
import { DevicesPage } from "./pages/DevicesPage";
import { DeviceDetailPage } from "./pages/DeviceDetailPage";
import { QuizPacksPage } from "./pages/QuizPacksPage";
import { AccountPage } from "./pages/AccountPage";

export function App() {
  const [parent, setParent] = useState<Parent | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.me()
      .then(setParent)
      .catch(() => setToken(null))
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return <main className="centered"><p>Checking session?</p></main>;
  }
  if (!parent) {
    return <AuthPage onAuthenticated={(value) => setParent(value.parent)} />;
  }
  return (
    <BrowserRouter>
      <Shell parent={parent} onLogout={() => { setToken(null); setParent(null); }} />
    </BrowserRouter>
  );
}

function Shell({ parent, onLogout }: { parent: Parent; onLogout: () => void }) {
  const [signingOut, setSigningOut] = useState(false);

  async function signOut() {
    if (signingOut) return;
    setSigningOut(true);
    try {
      const sessions = await api.parentSessions();
      const current = sessions.find((session) => session.current && session.status === "active");
      if (current) await api.revokeParentSession(current.id);
    } catch {
      // Local sign-out must still work when the network or the server is unavailable.
    } finally {
      onLogout();
    }
  }

  return (
    <>
      <header className="app-header">
        <Link className="brand" to="/">
          <img src="/icon.svg" alt="" width="34" height="34" />
          <span>Guard</span>
        </Link>
        <nav>
          <Link to="/">Devices</Link>
          <Link to="/quiz-packs">Quiz packs</Link>
          <Link to="/account">Account</Link>
          <span className="muted">{parent.email}</span>
          <button className="link-button" onClick={() => void signOut()} disabled={signingOut}>
            {signingOut ? "Signing out..." : "Sign out"}
          </button>
        </nav>
      </header>
      <main className="app-main">
        <Routes>
          <Route path="/" element={<DevicesPage />} />
          <Route path="/devices/:id" element={<DeviceDetailPage />} />
          <Route path="/quiz-packs" element={<QuizPacksPage />} />
          <Route path="/account" element={<AccountPage parent={parent} onSignedOut={onLogout} />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </>
  );
}
