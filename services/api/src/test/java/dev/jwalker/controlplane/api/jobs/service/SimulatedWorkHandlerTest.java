package dev.jwalker.controlplane.api.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// Covers SimulatedWorkHandler.handle() behavior in both operating modes.
// Concrete demo subclasses (CrmSyncSimulatedHandler, etc.) are thin
// enough that per-subclass tests would just re-verify the constants —
// this test exercises the base logic via a test-only subclass.
class SimulatedWorkHandlerTest {

    @Test
    void handle_shortCircuitsToSuccess_whenChunkMillisIsZero() throws Exception {
        // Failure rate 100% would guarantee a throw in sim mode; instant
        // mode must skip the roll entirely.
        SimulatedWorkHandler handler = testHandler(1.0);
        ReflectionTestUtils.setField(handler, "chunkMillis", 0L);

        long start = System.currentTimeMillis();
        String summary = handler.handle(dummyJob());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(summary).contains("done in");
        // No sleep in instant mode — pick a generous ceiling to absorb
        // GC or JVM warm-up jitter without being flaky.
        assertThat(elapsed).isLessThan(100);
    }

    @Test
    void handle_throws_whenFailureRateIs100PercentInSimMode() {
        SimulatedWorkHandler handler = testHandler(1.0);
        // 1ms chunks keeps the test fast (min 2 chunks → ~2ms of sleep)
        // while still exercising the sim-mode path.
        ReflectionTestUtils.setField(handler, "chunkMillis", 1L);

        assertThatThrownBy(() -> handler.handle(dummyJob()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated failure");
    }

    @Test
    void handle_succeeds_whenFailureRateIsZeroInSimMode() throws Exception {
        SimulatedWorkHandler handler = testHandler(0.0);
        ReflectionTestUtils.setField(handler, "chunkMillis", 1L);

        String summary = handler.handle(dummyJob());

        assertThat(summary).contains("done in");
    }

    // --- helpers -------------------------------------------------------

    private SimulatedWorkHandler testHandler(double failureRate) {
        return new SimulatedWorkHandler() {
            @Override public JobType getSupportedType() { return JobType.CRM_SYNC; }
            @Override protected int getMinChunks() { return 2; }
            @Override protected int getMaxChunks() { return 2; }
            @Override protected double getFailureRate() { return failureRate; }
            @Override protected String getProgressLabel() { return "unit-test work"; }
            @Override protected String getSuccessSummary(int chunks) {
                return "done in " + chunks + " chunks";
            }
        };
    }

    private Job dummyJob() {
        User owner = new User(UUID.randomUUID(), "u@example.com", "hash", UserStatus.ACTIVE);
        return new Job(
                UUID.randomUUID(), owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.RUNNING, JobPriority.MEDIUM, null, 3);
    }
}
