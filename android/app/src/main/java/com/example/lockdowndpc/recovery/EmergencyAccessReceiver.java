package com.example.lockdowndpc.recovery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class EmergencyAccessReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        EmergencyAccessController.expireIfDue(context.getApplicationContext());
    }
}
