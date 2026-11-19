export interface Parent {
  id: string;
  email: string;
}

export interface AuthResponse {
  token: string;
  expires_at: number;
  parent: Parent;
}

export interface ParentSession {
  id: string;
  created_at: string;
  expires_at: string;
  last_seen_at: string;
  revoked_at: string | null;
  current: boolean;
  status: "active" | "revoked" | "expired";
}

export interface Device {
  id: string;
  device_id: string;
  name: string;
  platform: string;
  paired: boolean;
  confirmed: boolean;
  revoked: boolean;
  created_at: string;
  last_seen_at: string | null;
  latest_revision: number;
}

export interface PolicyEnvelope {
  schema: number;
  key_id: string;
  revision: number;
  payload: string;
  signature: string;
}

export interface PolicyHistoryItem {
  revision: number;
  key_id: string;
  created_at: string;
}

export interface UsageReport {
  day: string;
  app_minutes: Record<string, number>;
  health: Record<string, unknown>;
  received_at: string;
}

export interface DashboardAppUsage {
  package_name: string;
  label: string;
  minutes: number;
  percent: number;
}

export interface DashboardDay {
  day: string;
  minutes: number;
  quiz_attempts: number;
  quiz_questions: number;
  quiz_correct: number;
}

export interface DeviceDashboard {
  day: string;
  today_minutes: number;
  week_minutes: number;
  app_usage: DashboardAppUsage[];
  quiz_attempts: number;
  quiz_questions: number;
  quiz_correct: number;
  quiz_accuracy_percent: number;
  rewards_granted: number;
  current_app: string | null;
  current_app_label: string | null;
  current_app_observed_at: string | null;
  last_usage_at: string | null;
  last_seen_at: string | null;
  policy_revision: number;
  health: Record<string, unknown>;
  days: DashboardDay[];
}

export interface AuditEvent {
  id: string;
  actor: string;
  event: string;
  detail: string;
  created_at: string;
}

export interface CallRequest {
  id: string;
  device_id: string;
  kind: "audio" | "video" | "screen" | "screenshot";
  status: "pending" | "accepted" | "rejected" | "expired" | "ended";
  created_at: string;
  expires_at: string;
  ended_at?: string | null;
}

export interface LiveKitCredentials {
  url: string;
  token: string;
  room: string;
  expires_at: number;
}

export interface DeviceApp {
  package_name: string;
  label: string;
  category: number;
  enabled: boolean;
  updated_at: string;
}

export interface TemporaryGrant {
  id: string;
  package_name: string;
  minutes: number;
  created_at: string;
  expires_at: string;
  applied: boolean;
  applied_at: string | null;
  revoked: boolean;
  revoked_at: string | null;
  revoked_by: string | null;
}

export interface Screenshot {
  id: string;
  device_id: string;
  call_id: string;
  width: number;
  height: number;
  created_at: string;
  expires_at: string;
}

export interface QuizPackSummary {
  id: string;
  title: string;
  version: number;
  key_id: string;
  question_count: number;
  base_version: number | null;
  has_delta: boolean;
  published_at: string;
  active: boolean;
  created_at: string;
}

export interface QuizPackDetail extends Omit<QuizPackSummary, "has_delta"> {
  public_key: string;
  envelope: string;
  delta_envelope: string | null;
}

export interface QuizKeyTransition {
  id: string;
  old_key_id: string;
  new_key_id: string;
  new_public_key: string;
  transition: string;
  created_at: string;
  revoked_at: string | null;
}

export interface QuizUpstreamConfig {
  title: string;
  url: string;
  public_key: string;
  key_id: string;
  enabled: boolean;
  interval_minutes: number;
  last_checked_at: string | null;
  last_success_at: string | null;
  last_error: string;
  last_status: string;
  last_version: number | null;
  created_at: string;
  updated_at: string;
}

export interface QuizUpstreamUpsert {
  title: string;
  url: string;
  public_key: string;
  interval_minutes: number;
  enabled: boolean;
}

export interface QuizUpstreamSyncResult {
  ok: boolean;
  status: string;
  version: number | null;
  message: string;
  checked_at: string;
}

export interface QuizUpstreamEvent {
  id: string;
  event: string;
  detail: string;
  created_at: string;
}

export interface QuizQuestionResult {
  question_id: string;
  correct: boolean;
  type: string;
  subject: string;
  grade: number;
  difficulty: string;
  language: string;
  tags: string[];
}

export interface QuizAttemptResult {
  id: string;
  device_id: string;
  attempt_id: string;
  occurred_at: string;
  target_package: string;
  trigger_id: string | null;
  policy_revision: number;
  quiz_version: number;
  question_count: number;
  correct_count: number;
  reward_granted: boolean;
  questions: QuizQuestionResult[];
  received_at: string;
}

export interface Notice {
  id: string;
  device_id: string;
  kind: "callback" | "task";
  message: string;
  status: "pending" | "acknowledged" | "expired";
  created_at: string;
  expires_at: string;
  acknowledged_at: string | null;
}

export interface HelpRequest {
  id: string;
  device_id: string;
  client_request_id: string;
  target_package: string;
  trigger_id: string;
  reason: string;
  message: string;
  status: "pending" | "answered" | "acknowledged" | "expired";
  parent_reply: string;
  created_at: string;
  expires_at: string;
  answered_at: string | null;
  acknowledged_at: string | null;
}

export interface ManualQuizTrigger {
  id: string;
  device_id: string;
  package_name: string;
  status: "pending" | "accepted" | "completed" | "rejected" | "expired";
  created_at: string;
  expires_at: string;
  delivered_at: string | null;
  finished_at: string | null;
  detail: string;
}

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = "ApiError";
  }
}

const TOKEN_KEY = "guard.parent.token";

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null) {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  else localStorage.removeItem(TOKEN_KEY);
}

const base = (import.meta.env.VITE_API_URL as string | undefined)?.replace(/\/$/, "") ?? "";

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set("Content-Type", "application/json");
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const response = await fetch(`${base}/api/v1${path}`, { ...init, headers });
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  let body: unknown = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  if (!response.ok) {
    const message =
      body && typeof body === "object" && "error" in body
        ? String((body as { error: unknown }).error)
        : `Request failed (${response.status})`;
    throw new ApiError(message, response.status);
  }
  return body as T;
}

async function requestBlob(path: string): Promise<Blob> {
  const headers = new Headers();
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const response = await fetch(`${base}/api/v1${path}`, { headers });
  if (!response.ok) {
    const text = await response.text();
    let message = `Request failed (${response.status})`;
    try {
      const body = JSON.parse(text) as { error?: unknown };
      if (body.error) message = String(body.error);
    } catch {
      // Preserve the status-based error when the response is not JSON.
    }
    throw new ApiError(message, response.status);
  }
  const type = response.headers.get("content-type") ?? "";
  if (!type.startsWith("image/")) throw new ApiError("The screenshot response was not an image", response.status);
  return response.blob();
}

export const api = {
  register: (email: string, password: string) =>
    request<AuthResponse>("/auth/register", {
      method: "POST",
      body: JSON.stringify({ email, password })
    }),
  login: (email: string, password: string) =>
    request<AuthResponse>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password })
    }),
  me: () => request<Parent>("/me"),
  parentSessions: () => request<ParentSession[]>("/me/sessions"),
  revokeParentSession: (sessionId: string) =>
    request<ParentSession>(`/me/sessions/${encodeURIComponent(sessionId)}/revoke`, {
      method: "POST"
    }),
  revokeOtherParentSessions: () =>
    request<{ revoked: number }>("/me/sessions/revoke-others", {
      method: "POST"
    }),
  deleteAccount: (email: string, password: string, confirmation: string) =>
    request<void>("/me/delete", {
      method: "POST",
      body: JSON.stringify({ email, password, confirmation })
    }),
  listDevices: () => request<Device[]>("/devices"),
  getDevice: (id: string) => request<Device>(`/devices/${encodeURIComponent(id)}`),
  claimPairing: (code: string) =>
    request<Device>("/pairing/claim", {
      method: "POST",
      body: JSON.stringify({ code })
    }),
  revokeDevice: (id: string) =>
    request<void>(`/devices/${encodeURIComponent(id)}`, { method: "DELETE" }),
  latestPolicy: (id: string) =>
    request<PolicyEnvelope>(`/devices/${encodeURIComponent(id)}/policy`),
  policyHistory: (id: string) =>
    request<PolicyHistoryItem[]>(`/devices/${encodeURIComponent(id)}/policy/history`),
  rollbackPolicy: (id: string, revision: number) =>
    request<PolicyEnvelope>(`/devices/${encodeURIComponent(id)}/policy/${revision}/rollback`, {
      method: "POST"
    }),
  publishPolicy: (id: string, policy: Record<string, unknown>) =>
    request<PolicyEnvelope>(`/devices/${encodeURIComponent(id)}/policy`, {
      method: "PUT",
      body: JSON.stringify({ policy })
    }),
  dashboard: (id: string, date?: string) => {
    const query = date ? `?date=${encodeURIComponent(date)}` : "";
    return request<DeviceDashboard>(`/devices/${encodeURIComponent(id)}/dashboard${query}`);
  },
  usage: (id: string, from?: string, to?: string) => {
    const params = new URLSearchParams();
    if (from) params.set("from", from);
    if (to) params.set("to", to);
    const suffix = params.size ? `?${params.toString()}` : "";
    return request<UsageReport[]>(`/devices/${encodeURIComponent(id)}/usage${suffix}`);
  },
  audit: (id: string) =>
    request<AuditEvent[]>(`/devices/${encodeURIComponent(id)}/audit`),
  quizResults: (id: string) =>
    request<QuizAttemptResult[]>(`/devices/${encodeURIComponent(id)}/quiz-results`),
  notices: (id: string) =>
    request<Notice[]>(`/devices/${encodeURIComponent(id)}/notices`),
  helpRequests: (id: string) =>
    request<HelpRequest[]>(`/devices/${encodeURIComponent(id)}/help-requests`),
  respondToHelpRequest: (id: string, requestId: string, reply: string) =>
    request<HelpRequest>(`/devices/${encodeURIComponent(id)}/help-requests/${encodeURIComponent(requestId)}/respond`, {
      method: "POST",
      body: JSON.stringify({ reply })
    }),
  sendNotice: (id: string, kind: Notice["kind"], message: string) =>
    request<Notice>(`/devices/${encodeURIComponent(id)}/notices`, {
      method: "POST",
      body: JSON.stringify({ kind, message })
    }),
  quizTriggers: (id: string) =>
    request<ManualQuizTrigger[]>(`/devices/${encodeURIComponent(id)}/quiz-triggers`),
  triggerQuiz: (id: string, packageName: string) =>
    request<ManualQuizTrigger>(`/devices/${encodeURIComponent(id)}/quiz-triggers`, {
      method: "POST",
      body: JSON.stringify({ package_name: packageName, expires_in_minutes: 10 })
    }),
  apps: (id: string) =>
    request<DeviceApp[]>(`/devices/${encodeURIComponent(id)}/apps`),
  grants: (id: string) =>
    request<TemporaryGrant[]>(`/devices/${encodeURIComponent(id)}/grants`),
  grantApp: (id: string, packageName: string, minutes: number) =>
    request<TemporaryGrant>(`/devices/${encodeURIComponent(id)}/grants`, {
      method: "POST",
      body: JSON.stringify({ package_name: packageName, minutes, expires_in_minutes: 1440 })
    }),
  revokeGrant: (id: string, grantId: string) =>
    request<TemporaryGrant>(
      `/devices/${encodeURIComponent(id)}/grants/${encodeURIComponent(grantId)}/revoke`,
      { method: "POST" }
    ),
  screenshots: (id: string) =>
    request<Screenshot[]>(`/devices/${encodeURIComponent(id)}/screenshots`),
  screenshotImage: (screenshotId: string) =>
    requestBlob(`/screenshots/${encodeURIComponent(screenshotId)}`),
  listCalls: (id: string) =>
    request<CallRequest[]>(`/devices/${encodeURIComponent(id)}/call-requests`),
  requestCall: (id: string, kind: CallRequest["kind"]) =>
    request<CallRequest>(`/devices/${encodeURIComponent(id)}/call-requests`, {
      method: "POST",
      body: JSON.stringify({ kind })
    }),
  callToken: (callId: string) =>
    request<LiveKitCredentials>(`/call-requests/${encodeURIComponent(callId)}/token`),
  endCall: (callId: string) =>
    request<CallRequest>(`/call-requests/${encodeURIComponent(callId)}/end`, {
      method: "POST",
      body: "{}"
    }),
  listQuizPacks: () => request<QuizPackSummary[]>("/quiz-packs"),
  getQuizPack: (packId: string) =>
    request<QuizPackDetail>(`/quiz-packs/${encodeURIComponent(packId)}`),
  createQuizPack: (title: string, publicKey: string, envelope: string,
    deltaEnvelope?: string, baseVersion?: number) =>
    request<QuizPackDetail>("/quiz-packs", {
      method: "POST",
      body: JSON.stringify({
        title,
        public_key: publicKey,
        envelope,
        ...(deltaEnvelope ? { delta_envelope: deltaEnvelope, base_version: baseVersion } : {}),
        activate: true
      })
    }),
  activateQuizPack: (packId: string) =>
    request<QuizPackDetail>(`/quiz-packs/${encodeURIComponent(packId)}/activate`, {
      method: "POST"
    }),
  quizKeyTransitions: () => request<QuizKeyTransition[]>("/quiz-keys"),
  rotateQuizKey: (transition: string) =>
    request<QuizKeyTransition>("/quiz-keys/rotate", {
      method: "POST",
      body: JSON.stringify({ transition })
    }),
  revokeQuizKey: (transitionId: string) =>
    request<QuizKeyTransition>(`/quiz-keys/${encodeURIComponent(transitionId)}/revoke`, {
      method: "POST"
    }),
  getQuizUpstream: () =>
    request<QuizUpstreamConfig | undefined>("/quiz-upstream"),
  saveQuizUpstream: (config: QuizUpstreamUpsert) =>
    request<QuizUpstreamConfig>("/quiz-upstream", {
      method: "PUT",
      body: JSON.stringify(config)
    }),
  deleteQuizUpstream: () =>
    request<void>("/quiz-upstream", { method: "DELETE" }),
  syncQuizUpstream: () =>
    request<QuizUpstreamSyncResult>("/quiz-upstream/sync", { method: "POST" }),
  quizUpstreamEvents: () =>
    request<QuizUpstreamEvent[]>("/quiz-upstream/events?limit=20")
};
