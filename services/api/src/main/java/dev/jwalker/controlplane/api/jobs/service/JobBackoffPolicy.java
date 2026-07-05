package dev.jwalker.controlplane.api.jobs.service;

import java.time.Duration;

// Exponential backoff for job retries. Formula: base * 2^attemptNumber,
// capped at MAX_DELAY. So attempt 1 → 20s, 2 → 40s, 3 → 80s, ... eventually
// hitting the 1-hour cap. Kept as a static utility rather than a bean —
// pure math, no dependencies, easy to unit test.
public final class JobBackoffPolicy {

    static final Duration BASE_DELAY = Duration.ofSeconds(10);
    static final Duration MAX_DELAY = Duration.ofHours(1);

    private JobBackoffPolicy() {}

    public static Duration computeBackoff(int attemptNumber) {
        // Guard against pathological inputs; treat everything <=0 as attempt 1.
        int safeAttempt = Math.max(attemptNumber, 1);
        // Cap the shift at 30 to avoid overflow at absurdly high attempt
        // numbers; we clamp to MAX_DELAY anyway so the effective cap fires
        // well before this matters.
        int shift = Math.min(safeAttempt, 30);
        long seconds = BASE_DELAY.getSeconds() * (1L << shift);
        return Duration.ofSeconds(Math.min(seconds, MAX_DELAY.getSeconds()));
    }
}
