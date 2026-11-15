package com.example.lockdowndpc.guard;

import android.content.Context;

import com.example.lockdowndpc.policy.AuditLog;

import java.io.IOException;
import java.util.List;

/** Sends queued child help requests and caches parent replies. */
public final class GuardHelpCoordinator {
    private GuardHelpCoordinator() {}

    public static final class Result {
        public final String clientRequestId;
        public final boolean sent;
        public final String message;

        Result(String clientRequestId, boolean sent, String message) {
            this.clientRequestId = clientRequestId;
            this.sent = sent;
            this.message = message;
        }
    }

    public static Result sendNow(Context context, String targetPackage, String triggerId,
            String reason, String message) {
        Context app = context.getApplicationContext();
        String clientRequestId = GuardHelpRequestStore.enqueue(app, targetPackage, triggerId,
                reason, message);
        AuditLog.append(app, "guard-help-queued:" + (targetPackage == null ? "" : targetPackage));
        try {
            flush(app);
            boolean stillQueued = false;
            for (GuardHelpRequestStore.Pending item : GuardHelpRequestStore.pending(app)) {
                if (clientRequestId.equals(item.clientRequestId)) {
                    stillQueued = true;
                    break;
                }
            }
            return new Result(clientRequestId, !stillQueued,
                    stillQueued ? "Help request is still queued"
                            : "Help request sent to the parent");
        } catch (Exception exception) {
            return new Result(clientRequestId, false,
                    "Help request saved and will be sent when the parent service is available");
        }
    }

    public static void flush(Context context) throws IOException {
        Context app = context.getApplicationContext();
        List<GuardHelpRequestStore.Pending> pending = GuardHelpRequestStore.pending(app);
        if (pending.isEmpty()) return;
        GuardRemoteClient client = new GuardRemoteClient(app);
        if (!client.isConfigured()) throw new IOException("Guard server is not configured");
        for (GuardHelpRequestStore.Pending item : pending) {
            GuardRemoteClient.HelpRequestInfo saved;
            try {
                saved = client.createHelpRequest(item.clientRequestId, item.targetPackage,
                        item.triggerId, item.reason, item.message);
            } catch (org.json.JSONException exception) {
                throw new IOException("Help request response is invalid", exception);
            }
            GuardHelpRequestStore.markSent(app, item.clientRequestId, saved);
            AuditLog.append(app, "guard-help-sent:" + saved.id);
        }
    }

    public static void poll(Context context) throws IOException {
        Context app = context.getApplicationContext();
        GuardRemoteClient client = new GuardRemoteClient(app);
        if (!client.isConfigured()) return;
        try {
            List<GuardRemoteClient.HelpRequestInfo> requests = client.helpRequests();
            GuardHelpRequestStore.recordServer(app, requests);
            int unread = 0;
            for (GuardRemoteClient.HelpRequestInfo request : requests) {
                if (!request.parentReply.isEmpty() && !"pending".equals(request.status)) unread++;
            }
            if (unread > 0) AuditLog.append(app, "guard-help-reply-available:" + unread);
        } catch (org.json.JSONException exception) {
            throw new IOException("Help request response is invalid", exception);
        }
    }

    public static boolean acknowledge(Context context, String requestId) {
        Context app = context.getApplicationContext();
        if (!GuardHelpRequestStore.markRead(app, requestId)) return false;
        try {
            new GuardRemoteClient(app).acknowledgeHelpRequest(requestId);
            AuditLog.append(app, "guard-help-acknowledged:" + requestId);
        } catch (Exception exception) {
            AuditLog.append(app, "guard-help-ack-pending:" + requestId);
        }
        return true;
    }
}
