package com.example.lockdowndpc.guard;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Periodic network work kept separate from the serialized DevicePolicyManager thread. */
public final class GuardSyncJobService extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile JobParameters running;

    @Override
    public boolean onStartJob(JobParameters parameters) {
        running = parameters;
        executor.execute(() -> {
            if (running != parameters) return;
            GuardSyncManager.Outcome outcome = GuardSyncManager.sync(getApplicationContext());
            boolean retry = outcome.status == GuardSyncManager.Status.ERROR;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (running == parameters) {
                    running = null;
                    jobFinished(parameters, retry);
                }
            });
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters parameters) {
        running = null;
        return true;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
