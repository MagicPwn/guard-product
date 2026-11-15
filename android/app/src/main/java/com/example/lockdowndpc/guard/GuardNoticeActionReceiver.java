package com.example.lockdowndpc.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class GuardNoticeActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!GuardNoticeCoordinator.ACTION_ACK.equals(intent.getAction())) return;
        String noticeId = intent.getStringExtra(GuardNoticeCoordinator.EXTRA_NOTICE_ID);
        PendingResult pending = goAsync();
        GuardNoticeCoordinator.acknowledgeAsync(context, noticeId, pending::finish);
    }
}
