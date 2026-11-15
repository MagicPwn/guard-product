package com.example.lockdowndpc.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Notification actions are explicit child decisions; neither action is silent. */
public final class GuardCallActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String callId = intent.getStringExtra(GuardCallCoordinator.EXTRA_CALL_ID);
        String kind = intent.getStringExtra(GuardCallCoordinator.EXTRA_CALL_KIND);
        if (callId == null || !callId.matches("^[A-Za-z0-9._:-]{1,128}$")) return;
        if (GuardCallCoordinator.ACTION_ACCEPT.equals(intent.getAction())) {
            GuardCallCoordinator.cancel(context, callId);
            context.startActivity(GuardCallCoordinator.callIntent(
                    context, callId, kind == null ? "audio" : kind, true));
        } else if (GuardCallCoordinator.ACTION_DECLINE.equals(intent.getAction())) {
            GuardCallCoordinator.respond(context, callId, false, null);
        }
    }
}
