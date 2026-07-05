package dev.jwalker.controlplane.api.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JobBackoffPolicyTest {

    @Test
    void computeBackoff_scalesExponentially() {
        assertThat(JobBackoffPolicy.computeBackoff(1)).isEqualTo(Duration.ofSeconds(20));
        assertThat(JobBackoffPolicy.computeBackoff(2)).isEqualTo(Duration.ofSeconds(40));
        assertThat(JobBackoffPolicy.computeBackoff(3)).isEqualTo(Duration.ofSeconds(80));
        assertThat(JobBackoffPolicy.computeBackoff(4)).isEqualTo(Duration.ofSeconds(160));
        assertThat(JobBackoffPolicy.computeBackoff(5)).isEqualTo(Duration.ofSeconds(320));
    }

    @Test
    void computeBackoff_capsAtMaxDelay() {
        // Formula would give 10 * 2^9 = 5120s but MAX_DELAY caps at 3600s.
        assertThat(JobBackoffPolicy.computeBackoff(9)).isEqualTo(Duration.ofHours(1));
        assertThat(JobBackoffPolicy.computeBackoff(20)).isEqualTo(Duration.ofHours(1));
        assertThat(JobBackoffPolicy.computeBackoff(1000)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void computeBackoff_treatsNonPositiveAsAttemptOne() {
        assertThat(JobBackoffPolicy.computeBackoff(0)).isEqualTo(Duration.ofSeconds(20));
        assertThat(JobBackoffPolicy.computeBackoff(-5)).isEqualTo(Duration.ofSeconds(20));
    }
}
