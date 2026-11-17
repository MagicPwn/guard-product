import { useEffect, useRef, useState } from "react";
import {
  createLocalTracks,
  Room,
  RoomEvent,
  Track,
  type LocalTrack,
  type RemoteTrack
} from "livekit-client";
import type { CallRequest, LiveKitCredentials } from "../api";
import { errorText, useI18n } from "../i18n";

interface Props {
  credentials: LiveKitCredentials;
  kind: CallRequest["kind"];
  onEnd: () => Promise<void>;
}

export function CallPanel({ credentials, kind, onEnd }: Props) {
  const { t } = useI18n();
  const stage = useRef<HTMLDivElement>(null);
  const [status, setStatus] = useState("Connecting...");
  const [error, setError] = useState<string | null>(null);
  const [ending, setEnding] = useState(false);

  async function end() {
    setEnding(true);
    setError(null);
    try {
      await onEnd();
    } catch (problem) {
      setError(errorText(problem, "Unable to end and revoke this call", t));
    } finally {
      setEnding(false);
    }
  }

  useEffect(() => {
    let cancelled = false;
    let room: Room | null = null;
    const localTracks: LocalTrack[] = [];

    async function start() {
      try {
        room = new Room({ adaptiveStream: true, dynacast: true });
        room.on(RoomEvent.TrackSubscribed, (track: RemoteTrack) => {
          const element = track.attach();
          if (track.kind === Track.Kind.Video) {
            element.className = "remote-video";
            stage.current?.appendChild(element);
          } else {
            element.className = "remote-audio";
            stage.current?.appendChild(element);
          }
          if (element instanceof HTMLMediaElement) {
            void element.play().catch(() => undefined);
          }
          setStatus("Connected");
        });
        room.on(RoomEvent.Disconnected, () => setStatus("Call ended"));
        await room.connect(credentials.url, credentials.token);
        if (cancelled) return;
        if (kind !== "screen") {
          const tracks = await createLocalTracks({ audio: true, video: kind === "video" });
          localTracks.push(...tracks);
          for (const track of tracks) {
            await room.localParticipant.publishTrack(track);
          }
        }
        setStatus("Connected - waiting for the other side");
      } catch (problem) {
        if (!cancelled) {
          setError(errorText(problem, "Unable to join the call", t));
          setStatus("Not connected");
        }
      }
    }
    void start();

    return () => {
      cancelled = true;
      localTracks.forEach((track) => track.stop());
      void room?.disconnect();
      stage.current?.replaceChildren();
    };
  }, [credentials.url, credentials.token, kind]);

  return (
    <section className="card call-card">
      <div className="page-title">
        <div>
          <h2>{t(kind === "screen" ? "Screen share" : kind === "video" ? "Video call" : "Voice call")}</h2>
          <p className="muted">{t(status)}</p>
        </div>
        <button className="secondary" onClick={() => void end()} disabled={ending}>
          {ending ? t("Ending...") : t("End")}
        </button>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="call-stage" ref={stage} />
      <p className="hint">{t("The child device controls its camera, microphone, and screen sharing. Nothing is recorded by Guard.")}</p>
    </section>
  );
}
