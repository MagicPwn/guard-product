package com.example.lockdowndpc.guard;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

import com.example.lockdowndpc.policy.PolicyReconciliationCoordinator;

public final class GuardEnforcementJobService extends JobService {
    private volatile JobParameters running;

    @Override
    public boolean onStartJob(JobParameters parameters) {
        running = parameters;
        PolicyReconciliationCoordinator.runOnPolicyThread(() -> {
            if (running != parameters) return;
            GuardPolicyEnforcer.Result result = GuardPolicyEnforcer.enforce(getApplicationContext());
            new Handler(Looper.getMainLooper()).post(() -> {
                if (running == parameters) {
                    running = null;
                    jobFinished(parameters, !result.verified());
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
}
