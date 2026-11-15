package com.example.lockdowndpc.recovery;

/**
 * Pure lifetime rules for a short emergency-access window.
 *
 * <p>The window is deliberately tied to one boot and to the monotonic clock.
 * A restart, a clock moved backwards, or an unreadable boot identity ends the
 * exception rather than extending it. The wall-clock deadline is retained only
 * so the administrator can see when the window would end.
 */
public final class EmergencyAccessWindow {
    public static final long DEFAULT_DURATION_MILLIS = 15L * 60L * 1000L;
    public static final long MAX_DURATION_MILLIS = 30L * 60L * 1000L;
    private static final long CLOCK_BACKWARD_TOLERANCE_MILLIS = 2L * 60L * 1000L;

    private EmergencyAccessWindow() {}

    public record Window(
            long startedElapsedMillis,
            long expiresElapsedMillis,
            long startedWallMillis,
            int bootCount
    ) {}

    public enum Status {
        ACTIVE,
        MISSING,
        EXPIRED,
        REBOOTED,
        CLOCK_ANOMALY,
        INVALID
    }

    public record Evaluation(Status status, long remainingMillis) {
        public boolean active() {
            return status == Status.ACTIVE;
        }
    }

    public static Window start(
            long nowElapsedMillis,
            long nowWallMillis,
            int bootCount,
            long durationMillis
    ) {
        if (durationMillis <= 0L || durationMillis > MAX_DURATION_MILLIS) {
            throw new IllegalArgumentException("Emergency duration must be between 1 and 30 minutes");
        }
        if (nowElapsedMillis < 0L || nowWallMillis <= 0L) {
            throw new IllegalArgumentException("Clock readings are not usable");
        }
        return new Window(
                nowElapsedMillis,
                nowElapsedMillis + durationMillis,
                nowWallMillis,
                bootCount
        );
    }

    public static Evaluation evaluate(
            Window window,
            long nowElapsedMillis,
            long nowWallMillis,
            int currentBootCount
    ) {
        if (window == null || window.expiresElapsedMillis() <= window.startedElapsedMillis()) {
            return new Evaluation(Status.MISSING, 0L);
        }
        if (window.bootCount() >= 0 && currentBootCount >= 0
                && window.bootCount() != currentBootCount) {
            return new Evaluation(Status.REBOOTED, 0L);
        }
        if (window.bootCount() >= 0 && currentBootCount < 0) {
            // The identity of this boot cannot be proved. Fail closed rather than
            // carrying an exception across an unknown restart.
            return new Evaluation(Status.INVALID, 0L);
        }
        if (nowElapsedMillis < window.startedElapsedMillis()) {
            return new Evaluation(Status.REBOOTED, 0L);
        }
        if (nowWallMillis + CLOCK_BACKWARD_TOLERANCE_MILLIS < window.startedWallMillis()) {
            return new Evaluation(Status.CLOCK_ANOMALY, 0L);
        }
        if (nowElapsedMillis >= window.expiresElapsedMillis()) {
            return new Evaluation(Status.EXPIRED, 0L);
        }
        return new Evaluation(
                Status.ACTIVE,
                window.expiresElapsedMillis() - nowElapsedMillis
        );
    }
}
