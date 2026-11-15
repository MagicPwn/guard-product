package com.example.lockdowndpc.guard;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

public final class GuardScheduler {
    public static final int JOB_ID = 8201;
    public static final int SYNC_JOB_ID = 8202;
    private static final int SYNC_NOW_JOB_ID = 8203;
    private GuardScheduler() {}
    public static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, GuardEnforcementJobService.class))
                .setPersisted(true).setPeriodic(15 * 60 * 1000L).build();
        scheduler.schedule(job);
        JobInfo sync = new JobInfo.Builder(SYNC_JOB_ID,
                new ComponentName(context, GuardSyncJobService.class))
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L).build();
        scheduler.schedule(sync);
    }

    public static void syncNow(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(SYNC_NOW_JOB_ID,
                new ComponentName(context, GuardSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(1_000L).build();
        scheduler.schedule(job);
    }
}
