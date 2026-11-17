import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { api, type Device } from "../api";
import { errorText, useI18n } from "../i18n";

export function DevicesPage() {
  const { t, formatDate } = useI18n();
  const [devices, setDevices] = useState<Device[]>([]);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [claiming, setClaiming] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setDevices(await api.listDevices());
      setError(null);
    } catch (problem) {
      setError(errorText(problem, "Cannot load devices", t));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  async function claim(event: FormEvent) {
    event.preventDefault();
    setClaiming(true);
    setError(null);
    try {
      await api.claimPairing(code.trim().toUpperCase());
      setCode("");
      await refresh();
    } catch (problem) {
      setError(errorText(problem, "Pairing failed", t));
    } finally {
      setClaiming(false);
    }
  }

  return (
    <section>
      <div className="page-title">
        <div>
          <h1>{t("Devices")}</h1>
          <p className="muted">{t("Only devices you have paired appear here.")}</p>
        </div>
        <button className="secondary" onClick={() => void refresh()}>{t("Refresh")}</button>
      </div>

      <form className="card pairing-card" onSubmit={claim}>
        <div>
          <h2>{t("Pair a child device")}</h2>
          <p className="muted">{t("Enter the one-time code shown by Guard on the child device. The child must then confirm the pairing.")}</p>
        </div>
        <div className="inline-form">
          <input value={code} onChange={(event) => setCode(event.target.value)}
            placeholder="ABCD2345" maxLength={8} autoCapitalize="characters" required />
          <button className="primary" disabled={claiming}>{claiming ? t("Pairing...") : t("Pair device")}</button>
        </div>
      </form>

      {error && <p className="error" role="alert">{error}</p>}
      {loading ? <p>{t("Loading devices...")}</p> : devices.length === 0
        ? <p className="empty">{t("No paired devices yet.")}</p>
        : <div className="device-grid">
            {devices.map((device) => (
              <Link className="card device-card" key={device.id} to={`/devices/${device.id}`}>
                <div className="device-heading">
                  <h2>{device.name}</h2>
                  <span className={`badge ${device.revoked ? "danger" : device.confirmed ? "ok" : "warn"}`}>
                    {device.revoked ? t("Revoked") : device.confirmed ? t("Protected") : t("Awaiting confirmation")}
                  </span>
                </div>
                <p className="mono">{device.device_id}</p>
                <dl>
                  <dt>{t("Policy revision")}</dt><dd>{device.latest_revision}</dd>
                  <dt>{t("Platform")}</dt><dd>{device.platform}</dd>
                  <dt>{t("Last seen")}</dt><dd>{device.last_seen_at ? formatDate(device.last_seen_at) : t("Never")}</dd>
                </dl>
              </Link>
            ))}
          </div>}
    </section>
  );
}
