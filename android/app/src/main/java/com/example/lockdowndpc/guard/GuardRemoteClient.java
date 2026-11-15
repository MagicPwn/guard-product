package com.example.lockdowndpc.guard;

import android.content.Context;
import android.os.Build;

import com.example.lockdowndpc.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** HTTPS client for the parent service. It deliberately has no fallback that weakens local policy. */
public final class GuardRemoteClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String PROTOCOL = "1";
    private static final int MAX_RESPONSE_CHARS = 9_000_000;
    private final Context context;
    private final OkHttpClient http;

    public GuardRemoteClient(Context context) {
        this.context = context.getApplicationContext();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    public static final class RemoteException extends IOException {
        public final int status;

        RemoteException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static final class PairingInfo {
        public final String code;
        public final long expiresAt;
        PairingInfo(String code, long expiresAt) { this.code = code; this.expiresAt = expiresAt; }
    }

    public static final class Metadata {
        public final boolean paired;
        public final boolean confirmed;
        public final String parentEmail;
        public final long latestRevision;
        public final String policyKeyId;
        public final String policyPublicKey;
        Metadata(boolean paired, boolean confirmed, String parentEmail, long latestRevision,
                String policyKeyId, String policyPublicKey) {
            this.paired = paired;
            this.confirmed = confirmed;
            this.parentEmail = parentEmail;
            this.latestRevision = latestRevision;
            this.policyKeyId = policyKeyId;
            this.policyPublicKey = policyPublicKey;
        }
    }

    public static final class CallInfo {
        public final String id;
        public final String kind;
        public final String expiresAt;
        public final String status;
        public final String endedAt;
        CallInfo(String id, String kind, String expiresAt, String status, String endedAt) {
            this.id = id; this.kind = kind; this.expiresAt = expiresAt;
            this.status = status; this.endedAt = endedAt;
        }
    }

    public static final class LiveKitCredentials {
        public final String url;
        public final String token;
        public final String room;
        public final long expiresAt;
        LiveKitCredentials(String url, String token, String room, long expiresAt) {
            this.url = url; this.token = token; this.room = room; this.expiresAt = expiresAt;
        }
    }

    public static final class AppInfo {
        public final String packageName;
        public final String label;
        public final int category;
        public final boolean enabled;
        AppInfo(String packageName, String label, int category, boolean enabled) {
            this.packageName = packageName;
            this.label = label;
            this.category = category;
            this.enabled = enabled;
        }
    }

    public static final class GrantInfo {
        public final String id;
        public final String packageName;
        public final int minutes;
        GrantInfo(String id, String packageName, int minutes) {
            this.id = id;
            this.packageName = packageName;
            this.minutes = minutes;
        }
    }

    public static final class NoticeInfo {
        public final String id;
        public final String kind;
        public final String message;
        public final String expiresAt;

        NoticeInfo(String id, String kind, String message, String expiresAt) {
            this.id = id;
            this.kind = kind;
            this.message = message;
            this.expiresAt = expiresAt;
        }
    }

    public static final class ManualQuizTriggerInfo {
        public final String id;
        public final String packageName;
        public final String expiresAt;

        ManualQuizTriggerInfo(String id, String packageName, String expiresAt) {
            this.id = id;
            this.packageName = packageName;
            this.expiresAt = expiresAt;
        }
    }

    public static final class HelpRequestInfo {
        public final String id;
        public final String clientRequestId;
        public final String targetPackage;
        public final String triggerId;
        public final String reason;
        public final String message;
        public final String status;
        public final String parentReply;
        public final String createdAt;
        public final String expiresAt;

        HelpRequestInfo(String id, String clientRequestId, String targetPackage, String triggerId,
                String reason, String message, String status, String parentReply,
                String createdAt, String expiresAt) {
            this.id = id;
            this.clientRequestId = clientRequestId;
            this.targetPackage = targetPackage;
            this.triggerId = triggerId;
            this.reason = reason;
            this.message = message;
            this.status = status;
            this.parentReply = parentReply;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }

    public static final class QuizKeyTransitionInfo {
        public final String id;
        public final String oldKeyId;
        public final String newKeyId;
        public final String newPublicKey;
        public final String transition;
        public final String revokedAt;

        QuizKeyTransitionInfo(String id, String oldKeyId, String newKeyId,
                String newPublicKey, String transition, String revokedAt) {
            this.id = id;
            this.oldKeyId = oldKeyId;
            this.newKeyId = newKeyId;
            this.newPublicKey = newPublicKey;
            this.transition = transition;
            this.revokedAt = revokedAt;
        }
    }

    public static final class QuizPackBundle {
        public final long version;
        public final String keyId;
        public final String publicKey;
        public final String envelope;
        public final String publishedAt;
        public final String deltaEnvelope;
        public final Long baseVersion;
        QuizPackBundle(long version, String keyId, String publicKey, String envelope,
                String publishedAt, String deltaEnvelope, Long baseVersion) {
            this.version = version;
            this.keyId = keyId;
            this.publicKey = publicKey;
            this.envelope = envelope;
            this.publishedAt = publishedAt;
            this.deltaEnvelope = deltaEnvelope;
            this.baseVersion = baseVersion;
        }
    }

    public boolean isConfigured() {
        return !GuardRemoteStore.serverUrl(context).isEmpty();
    }

    public PairingInfo registerDevice() throws IOException, JSONException {
        String deviceId = GuardRemoteStore.deviceId(context);
        String name = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        if (name.length() > 80) name = name.substring(0, 80);
        JSONObject body = new JSONObject().put("device_id", deviceId)
                .put("public_key", GuardDeviceIdentity.publicKeyBase64(context))
                .put("name", name).put("platform", "android");
        HttpResult response = execute("POST", "/api/v1/device/register",
                body.toString(), null).requireSuccess();
        JSONObject parsed = new JSONObject(response.body);
        String token = parsed.getString("device_token");
        long expiresAt = parsed.getLong("pairing_expires_at");
        if (!GuardRemoteStore.setDeviceToken(context, token,
                System.currentTimeMillis() + 29L * 24 * 60 * 60 * 1000)) {
            throw new IOException("Unable to protect the device token");
        }
        String code = parsed.getString("pairing_code");
        GuardRemoteStore.setPairingCode(context, code, expiresAt);
        return new PairingInfo(code, expiresAt);
    }

    public Metadata me() throws IOException, JSONException {
        JSONObject parsed = new JSONObject(authorized("GET", "/api/v1/device/me", null).requireSuccess().body);
        return new Metadata(parsed.optBoolean("paired"), parsed.optBoolean("confirmed"),
                parsed.optString("parent_email", ""), parsed.optLong("latest_revision", 0L),
                parsed.optString("policy_key_id", ""), parsed.optString("policy_public_key", ""));
    }

    public void confirmPairing() throws IOException {
        authorized("POST", "/api/v1/device/confirm-pairing", "{}").requireSuccess();
    }

    public String fetchPolicy(long afterRevision) throws IOException {
        HttpResult result = authorized("GET", "/api/v1/device/policy?after=" + Math.max(0L, afterRevision), null);
        if (result.status == 204) return null;
        return result.requireSuccess().body;
    }

    public QuizPackBundle fetchQuizPack() throws IOException, JSONException {
        return fetchQuizPack(0L);
    }

    public QuizPackBundle fetchQuizPack(long baseVersion) throws IOException, JSONException {
        String path = baseVersion > 0
                ? "/api/v1/device/quiz-pack?base_version=" + baseVersion
                : "/api/v1/device/quiz-pack";
        HttpResult result = authorized("GET", path, null);
        if (result.status == 204) return null;
        JSONObject response = new JSONObject(result.requireSuccess().body);
        String keyId = response.getString("key_id");
        String publicKey = response.getString("public_key");
        String envelope = response.optString("envelope", "");
        String deltaEnvelope = response.optString("delta_envelope", "");
        Long deltaBase = response.has("base_version") && !response.isNull("base_version")
                ? response.getLong("base_version") : null;
        if (keyId.isEmpty() || publicKey.isEmpty()
                || (envelope.isEmpty() && deltaEnvelope.isEmpty())
                || envelope.length() > 8_000_000 || deltaEnvelope.length() > 8_000_000
                || (deltaBase != null && deltaBase < 1L)) {
            throw new IOException("Quiz pack response is invalid");
        }
        return new QuizPackBundle(response.getLong("version"), keyId, publicKey, envelope,
                response.optString("published_at", ""), deltaEnvelope, deltaBase);
    }

    public void acknowledgePolicy(long revision, boolean verified, List<String> errors) throws IOException {
        try {
            JSONArray messages = new JSONArray();
            for (String error : errors == null ? Collections.<String>emptyList() : errors) {
                if (messages.length() >= 20) break;
                messages.put(error == null ? "" : error.substring(0, Math.min(200, error.length())));
            }
            JSONObject body = new JSONObject().put("revision", revision)
                    .put("verified", verified).put("errors", messages);
            authorized("POST", "/api/v1/device/policy-ack", body.toString()).requireSuccess();
        } catch (JSONException exception) {
            throw new IOException("Unable to encode policy acknowledgement", exception);
        }
    }

    public void uploadUsage(LocalDate day, Map<String, Integer> appMinutes, JSONObject health)
            throws IOException {
        try {
            JSONObject apps = new JSONObject();
            for (Map.Entry<String, Integer> entry : appMinutes.entrySet()) {
                if (apps.length() >= 500) break;
                apps.put(entry.getKey(), Math.max(0, Math.min(1440, entry.getValue())));
            }
            JSONObject body = new JSONObject().put("day", day.toString())
                    .put("app_minutes", apps)
                    .put("health", health == null ? new JSONObject() : health);
            authorized("POST", "/api/v1/device/usage", body.toString()).requireSuccess();
        } catch (JSONException exception) {
            throw new IOException("Unable to encode usage report", exception);
        }
    }

    public void uploadAudit(String event, String detail) throws IOException {
        try {
            JSONObject body = new JSONObject().put("event", event)
                    .put("detail", detail == null ? "" : detail);
            authorized("POST", "/api/v1/device/audit", body.toString()).requireSuccess();
        } catch (JSONException exception) {
            throw new IOException("Unable to encode audit event", exception);
        }
    }

    public void uploadApps(List<AppInfo> apps) throws IOException {
        try {
            JSONArray values = new JSONArray();
            int count = 0;
            for (AppInfo app : apps) {
                if (count++ >= 2000) break;
                values.put(new JSONObject().put("package_name", app.packageName)
                        .put("label", app.label).put("category", app.category)
                        .put("enabled", app.enabled));
            }
            authorized("POST", "/api/v1/device/apps",
                    new JSONObject().put("apps", values).toString()).requireSuccess();
        } catch (JSONException exception) {
            throw new IOException("Unable to encode app inventory", exception);
        }
    }

    public List<GrantInfo> pendingGrants() throws IOException, JSONException {
        JSONArray array = new JSONArray(authorized("GET", "/api/v1/device/grants", null)
                .requireSuccess().body);
        List<GrantInfo> grants = new ArrayList<>();
        for (int index = 0; index < array.length() && index < 50; index++) {
            JSONObject grant = array.getJSONObject(index);
            grants.add(new GrantInfo(grant.getString("id"), grant.getString("package_name"),
                    grant.getInt("minutes")));
        }
        return grants;
    }

    public List<GrantInfo> revokedGrants() throws IOException, JSONException {
        JSONArray array = new JSONArray(authorized("GET", "/api/v1/device/grants/revocations", null)
                .requireSuccess().body);
        List<GrantInfo> grants = new ArrayList<>();
        for (int index = 0; index < array.length() && index < 1000; index++) {
            JSONObject grant = array.getJSONObject(index);
            grants.add(new GrantInfo(grant.getString("id"), grant.getString("package_name"),
                    grant.getInt("minutes")));
        }
        return grants;
    }

    public void applyGrant(String grantId) throws IOException {
        authorized("POST", "/api/v1/device/grants/" + encode(grantId) + "/apply", "{}")
                .requireSuccess();
    }

    public void uploadQuizResult(JSONObject result) throws IOException {
        if (result == null || result.length() == 0) throw new IOException("Quiz result is empty");
        authorized("POST", "/api/v1/device/quiz-results", result.toString()).requireSuccess();
    }

    public List<NoticeInfo> pendingNotices() throws IOException, JSONException {
        JSONArray array = new JSONArray(authorized("GET", "/api/v1/device/notices", null)
                .requireSuccess().body);
        List<NoticeInfo> notices = new ArrayList<>();
        for (int index = 0; index < array.length() && index < 50; index++) {
            JSONObject notice = array.getJSONObject(index);
            String id = notice.getString("id");
            String kind = notice.getString("kind");
            if (!id.matches("^[A-Za-z0-9._:-]{1,128}$")
                    || !kind.matches("^(callback|task)$")) {
                throw new IOException("Parent notice is invalid");
            }
            String message = notice.optString("message", "");
            if (message.length() > 200) throw new IOException("Parent notice is too long");
            notices.add(new NoticeInfo(id, kind, message,
                    notice.optString("expires_at", "")));
        }
        return notices;
    }

    public void acknowledgeNotice(String noticeId) throws IOException {
        authorized("POST", "/api/v1/device/notices/" + encode(noticeId) + "/ack", "{}")
                .requireSuccess();
    }

    public HelpRequestInfo createHelpRequest(String clientRequestId, String targetPackage,
            String triggerId, String reason, String message) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("client_request_id", clientRequestId)
                .put("target_package", targetPackage == null ? "" : targetPackage)
                .put("trigger_id", triggerId == null ? "" : triggerId)
                .put("reason", reason == null ? "blocked" : reason)
                .put("message", message == null ? "" : message);
        return parseHelpRequest(new JSONObject(authorized("POST", "/api/v1/device/help-requests",
                body.toString()).requireSuccess().body));
    }

    public List<HelpRequestInfo> helpRequests() throws IOException, JSONException {
        JSONArray array = new JSONArray(authorized("GET", "/api/v1/device/help-requests", null)
                .requireSuccess().body);
        List<HelpRequestInfo> requests = new ArrayList<>();
        for (int index = 0; index < array.length() && index < 100; index++) {
            requests.add(parseHelpRequest(array.getJSONObject(index)));
        }
        return requests;
    }

    public void acknowledgeHelpRequest(String requestId) throws IOException {
        authorized("POST", "/api/v1/device/help-requests/" + encode(requestId) + "/ack", "{}")
                .requireSuccess();
    }

    public List<QuizKeyTransitionInfo> quizKeyTransitions() throws IOException, JSONException {
        JSONArray array = new JSONArray(authorized("GET", "/api/v1/device/quiz-key-transitions", null)
                .requireSuccess().body);
        List<QuizKeyTransitionInfo> transitions = new ArrayList<>();
        for (int index = 0; index < array.length() && index < 32; index++) {
            JSONObject value = array.getJSONObject(index);
            String id = value.getString("id");
            String oldKeyId = value.getString("old_key_id");
            String newKeyId = value.getString("new_key_id");
            String newPublicKey = value.getString("new_public_key");
            String transition = value.getString("transition");
            if (!id.matches("^quiz-key-transition-[a-f0-9-]{36}$")
                    || !oldKeyId.matches("^[a-f0-9]{64}$")
                    || !newKeyId.matches("^[a-f0-9]{64}$")
                    || newPublicKey.isEmpty() || newPublicKey.length() > 2_000
                    || transition.isEmpty() || transition.length() > 8_000) {
                throw new IOException("Quiz key transition response is invalid");
            }
            transitions.add(new QuizKeyTransitionInfo(id, oldKeyId, newKeyId,
                    newPublicKey, transition, value.optString("revoked_at", "")));
        }
        return transitions;
    }

    private static HelpRequestInfo parseHelpRequest(JSONObject value) throws IOException {
        try {
            String id = value.getString("id");
            String clientId = value.getString("client_request_id");
            String status = value.getString("status");
            if (!id.matches("^help-[a-f0-9-]{36}$")
                    || !clientId.matches("^[A-Za-z0-9._:-]{8,128}$")
                    || !status.matches("^(pending|answered|acknowledged|expired)$")) {
                throw new IOException("Help request response is invalid");
            }
            String target = value.optString("target_package", "");
            if (!target.isEmpty()) GuardPolicy.requirePackage(target);
            String trigger = value.optString("trigger_id", "");
            if (!trigger.isEmpty()
                    && !trigger.matches("(quiz-trigger|quiz-manual|quiz-budget)-[a-f0-9]{24}")) {
                throw new IOException("Help request trigger is invalid");
            }
            String reason = value.optString("reason", "");
            if (!reason.matches("^[a-z][a-z0-9_]{0,31}$")) {
                throw new IOException("Help request reason is invalid");
            }
            String message = value.optString("message", "");
            String reply = value.optString("parent_reply", "");
            if (message.length() > 500 || reply.length() > 1_000) {
                throw new IOException("Help request text is too long");
            }
            return new HelpRequestInfo(id, clientId, target, trigger, reason, message,
                    status, reply, value.optString("created_at", ""),
                    value.optString("expires_at", ""));
        } catch (JSONException exception) {
            throw new IOException("Help request response is invalid", exception);
        }
    }

    public List<ManualQuizTriggerInfo> pendingManualQuizTriggers() throws IOException, JSONException {
        JSONArray array = new JSONArray(authorized("GET", "/api/v1/device/quiz-triggers", null)
                .requireSuccess().body);
        List<ManualQuizTriggerInfo> triggers = new ArrayList<>();
        for (int index = 0; index < array.length() && index < 20; index++) {
            JSONObject trigger = array.getJSONObject(index);
            String id = trigger.getString("id");
            String packageName = trigger.getString("package_name");
            if (!id.matches("quiz-manual-[a-f0-9]{24}")) {
                throw new IOException("Parent quiz trigger id is invalid");
            }
            try {
                GuardPolicy.requirePackage(packageName);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Parent quiz trigger package is invalid", exception);
            }
            triggers.add(new ManualQuizTriggerInfo(id, packageName,
                    trigger.optString("expires_at", "")));
        }
        return triggers;
    }

    public void acknowledgeManualQuizTrigger(String triggerId, String status, String detail)
            throws IOException, JSONException {
        if (status == null || !status.matches("^(accepted|rejected|completed)$")) {
            throw new IOException("Quiz trigger acknowledgement status is invalid");
        }
        JSONObject body = new JSONObject().put("status", status)
                .put("detail", detail == null ? "" : detail);
        authorized("POST", "/api/v1/device/quiz-triggers/" + encode(triggerId) + "/ack",
                body.toString()).requireSuccess();
    }

    public void uploadScreenshot(String callId, byte[] jpeg, int width, int height)
            throws IOException {
        try {
            JSONObject body = new JSONObject().put("call_id", encode(callId))
                    .put("image_base64", Base64.getEncoder().encodeToString(jpeg))
                    .put("width", width).put("height", height);
            authorized("POST", "/api/v1/device/screenshots", body.toString()).requireSuccess();
        } catch (JSONException exception) {
            throw new IOException("Unable to encode screenshot", exception);
        }
    }

    public List<CallInfo> pendingCalls() throws IOException, JSONException {
        JSONArray array = new JSONArray(authorized("GET", "/api/v1/device/call-requests", null)
                .requireSuccess().body);
        List<CallInfo> calls = new ArrayList<>();
        for (int index = 0; index < array.length() && index < 20; index++) {
            calls.add(callInfo(array.getJSONObject(index)));
        }
        return calls;
    }

    public CallInfo respondToCall(String callId, boolean accept) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("accept", accept);
        JSONObject call = new JSONObject(authorized("POST",
                "/api/v1/device/call-requests/" + encode(callId) + "/respond",
                body.toString()).requireSuccess().body);
        return callInfo(call);
    }

    public CallInfo callStatus(String callId) throws IOException, JSONException {
        JSONObject call = new JSONObject(authorized("GET",
                "/api/v1/device/call-requests/" + encode(callId), null)
                .requireSuccess().body);
        return callInfo(call);
    }

    public CallInfo endCall(String callId) throws IOException, JSONException {
        JSONObject call = new JSONObject(authorized("POST",
                "/api/v1/device/call-requests/" + encode(callId) + "/end", "{}")
                .requireSuccess().body);
        return callInfo(call);
    }

    public LiveKitCredentials callCredentials(String callId) throws IOException, JSONException {
        JSONObject response = new JSONObject(authorized("GET",
                "/api/v1/device/call-requests/" + encode(callId) + "/token", null)
                .requireSuccess().body);
        return new LiveKitCredentials(response.getString("url"), response.getString("token"),
                response.getString("room"), response.getLong("expires_at"));
    }

    private static CallInfo callInfo(JSONObject call) throws JSONException {
        String endedAt = call.isNull("ended_at") ? "" : call.optString("ended_at", "");
        return new CallInfo(call.getString("id"), call.getString("kind"),
                call.optString("expires_at", ""), call.optString("status", "pending"), endedAt);
    }

    private String token() throws IOException {
        String existing = GuardRemoteStore.deviceToken(context);
        long expiresAt = GuardRemoteStore.deviceTokenExpiresAt(context);
        if (existing != null && expiresAt > System.currentTimeMillis() + 60_000L) return existing;
        return renewToken();
    }

    private String renewToken() throws IOException {
        String deviceId = GuardRemoteStore.deviceId(context);
        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String message = "guard-device-token-v1\n" + deviceId + "\n" + timestamp + "\n" + nonce;
        try {
            JSONObject body = new JSONObject().put("device_id", deviceId)
                    .put("timestamp", timestamp).put("nonce", nonce)
                    .put("signature", GuardDeviceIdentity.signBase64(message));
            HttpResult response = execute("POST", "/api/v1/device/token",
                    body.toString(), null).requireSuccess();
            JSONObject parsed = new JSONObject(response.body);
            String token = parsed.getString("device_token");
            long expiresAt = parsed.getLong("expires_at");
            if (!GuardRemoteStore.setDeviceToken(context, token, expiresAt)) {
                throw new IOException("Unable to protect the device token");
            }
            return token;
        } catch (JSONException exception) {
            throw new IOException("Device token response is invalid", exception);
        }
    }

    private HttpResult authorized(String method, String path, String body) throws IOException {
        String token = token();
        HttpResult result = execute(method, path, body, token);
        if (result.status == 401 || result.status == 403) {
            GuardRemoteStore.clearDeviceToken(context);
            token = renewToken();
            result = execute(method, path, body, token);
        }
        return result;
    }

    private HttpResult execute(String method, String path, String body, String token)
            throws IOException {
        String base = GuardRemoteStore.serverUrl(context);
        if (base.isEmpty()) throw new IOException("Guard server is not configured");
        Request.Builder builder = new Request.Builder().url(base + path)
                .header("Accept", "application/json")
                .header("X-Guard-Protocol", PROTOCOL)
                .header("User-Agent", "Guard-Android/" + BuildConfig.VERSION_NAME);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if ("GET".equals(method)) {
            builder.get();
        } else {
            builder.method(method, RequestBody.create(body == null ? "{}" : body, JSON));
        }
        try (Response response = http.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            if (text.length() > MAX_RESPONSE_CHARS) throw new IOException("Server response is too large");
            return new HttpResult(response.code(), text);
        }
    }

    private static String encode(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9._:-]{1,128}$")) {
            throw new IllegalArgumentException("Invalid resource id");
        }
        return value;
    }

    private static final class HttpResult {
        final int status;
        final String body;
        HttpResult(int status, String body) { this.status = status; this.body = body; }

        HttpResult requireSuccess() throws RemoteException {
            if (status >= 200 && status < 300) return this;
            String message = "Guard server returned HTTP " + status;
            try {
                JSONObject parsed = new JSONObject(body);
                if (parsed.has("error")) message = parsed.getString("error");
            } catch (Exception ignored) {
            }
            throw new RemoteException(status, message);
        }
    }
}
