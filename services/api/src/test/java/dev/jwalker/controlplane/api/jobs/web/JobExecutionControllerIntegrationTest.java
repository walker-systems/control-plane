package dev.jwalker.controlplane.api.jobs.web;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.jwalker.controlplane.api.auth.web.dto.LoginRequest;
import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobExecution;
import dev.jwalker.controlplane.api.jobs.model.JobExecutionStatus;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.jobs.repository.JobExecutionRepository;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository;
import dev.jwalker.controlplane.api.users.model.Role;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.RoleRepository;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "app.security.jwt-secret=test-secret-must-be-at-least-32-bytes-long-xx",
        "app.security.access-token-minutes=15",
        "app.security.refresh-token-days=7",
        // Ticks off so the executor doesn't materialize/process jobs in the
        // background while we're seeding fixtures.
        "app.scheduling.enabled=false",
        "app.executor.enabled=false"
})
class JobExecutionControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JobRepository jobRepository;
    @Autowired private JobExecutionRepository jobExecutionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // FK order: job_executions → jobs → users. Schedules use raw SQL to
        // bypass @SQLDelete so their FK to users can be cleared too.
        jobExecutionRepository.deleteAll();
        jobRepository.deleteAll();
        jdbcTemplate.execute("DELETE FROM job_schedules");
        userRepository.deleteAll();

        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/jobs/" + UUID.randomUUID() + "/executions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_forNonExistentJob_returns404() throws Exception {
        seedUser("alice@example.com", "password");
        String token = loginAndExtractAccess("alice@example.com", "password");

        mockMvc.perform(get("/api/jobs/" + UUID.randomUUID() + "/executions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_returnsEmptyArray_forJobWithNoExecutions() throws Exception {
        seedUser("alice@example.com", "password");
        String token = loginAndExtractAccess("alice@example.com", "password");
        Job job = seedJob("alice@example.com", JobStatus.PENDING);

        mockMvc.perform(get("/api/jobs/" + job.getId() + "/executions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void list_returnsExecutionsOrderedByAttemptNumber() throws Exception {
        seedUser("alice@example.com", "password");
        String token = loginAndExtractAccess("alice@example.com", "password");
        Job job = seedJob("alice@example.com", JobStatus.SUCCEEDED);
        // Seed in mixed order so ordering isn't just insertion order.
        seedExecution(job, 3, JobExecutionStatus.SUCCEEDED);
        seedExecution(job, 1, JobExecutionStatus.FAILED);
        seedExecution(job, 2, JobExecutionStatus.FAILED);

        mockMvc.perform(get("/api/jobs/" + job.getId() + "/executions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].attemptNumber").value(1))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[1].attemptNumber").value(2))
                .andExpect(jsonPath("$[2].attemptNumber").value(3))
                .andExpect(jsonPath("$[2].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].jobId").value(job.getId().toString()));
    }

    @Test
    void list_capsResponseAt100Executions() throws Exception {
        seedUser("alice@example.com", "password");
        String token = loginAndExtractAccess("alice@example.com", "password");
        Job job = seedJob("alice@example.com", JobStatus.SUCCEEDED);
        // Seed 105 executions — over the 100-row response cap. This can
        // happen when a job has been through many /retry cycles.
        for (int attempt = 1; attempt <= 105; attempt++) {
            seedExecution(job, attempt, JobExecutionStatus.SUCCEEDED);
        }

        mockMvc.perform(get("/api/jobs/" + job.getId() + "/executions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(100))
                // Cap keeps the earliest attempts — the first 100 by
                // attemptNumber ASC. Attempt 100 is included; 101–105 aren't.
                .andExpect(jsonPath("$[0].attemptNumber").value(1))
                .andExpect(jsonPath("$[99].attemptNumber").value(100));
    }

    @Test
    void list_byNonOwnerUnprivileged_returns404() throws Exception {
        seedUser("alice@example.com", "password");
        seedUser("bob@example.com", "password");
        String bobToken = loginAndExtractAccess("bob@example.com", "password");
        Job aliceJob = seedJob("alice@example.com", JobStatus.SUCCEEDED);
        seedExecution(aliceJob, 1, JobExecutionStatus.SUCCEEDED);

        // Bob can't see Alice's job — surface as 404 (not 403) so existence
        // isn't leaked, matching the /api/jobs/{id} convention.
        mockMvc.perform(get("/api/jobs/" + aliceJob.getId() + "/executions")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_byOperator_seesAnyOwnersJob() throws Exception {
        seedUser("alice@example.com", "password");
        seedUserWithRole("ops@example.com", "password", "OPERATOR");
        String opsToken = loginAndExtractAccess("ops@example.com", "password");
        Job aliceJob = seedJob("alice@example.com", JobStatus.SUCCEEDED);
        seedExecution(aliceJob, 1, JobExecutionStatus.SUCCEEDED);

        mockMvc.perform(get("/api/jobs/" + aliceJob.getId() + "/executions")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void getByAttempt_returnsExecution_whenExists() throws Exception {
        seedUser("alice@example.com", "password");
        String token = loginAndExtractAccess("alice@example.com", "password");
        Job job = seedJob("alice@example.com", JobStatus.SUCCEEDED);
        JobExecution exec = seedExecution(job, 2, JobExecutionStatus.SUCCEEDED);

        mockMvc.perform(get("/api/jobs/" + job.getId() + "/executions/2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(exec.getId().toString()))
                .andExpect(jsonPath("$.jobId").value(job.getId().toString()))
                .andExpect(jsonPath("$.attemptNumber").value(2))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void getByAttempt_returns404_whenAttemptDoesntExist() throws Exception {
        seedUser("alice@example.com", "password");
        String token = loginAndExtractAccess("alice@example.com", "password");
        Job job = seedJob("alice@example.com", JobStatus.SUCCEEDED);
        seedExecution(job, 1, JobExecutionStatus.SUCCEEDED);

        mockMvc.perform(get("/api/jobs/" + job.getId() + "/executions/99")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByAttempt_returns404_whenJobDoesntExist() throws Exception {
        seedUser("alice@example.com", "password");
        String token = loginAndExtractAccess("alice@example.com", "password");

        mockMvc.perform(get("/api/jobs/" + UUID.randomUUID() + "/executions/1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByAttempt_byNonOwnerUnprivileged_returns404() throws Exception {
        seedUser("alice@example.com", "password");
        seedUser("bob@example.com", "password");
        String bobToken = loginAndExtractAccess("bob@example.com", "password");
        Job aliceJob = seedJob("alice@example.com", JobStatus.SUCCEEDED);
        seedExecution(aliceJob, 1, JobExecutionStatus.SUCCEEDED);

        mockMvc.perform(get("/api/jobs/" + aliceJob.getId() + "/executions/1")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    private Job seedJob(String ownerEmail, JobStatus status) {
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        Job job = new Job(null, owner, null, JobType.CRM_SYNC, "{}",
                status, JobPriority.MEDIUM, null, 3);
        return jobRepository.saveAndFlush(job);
    }

    private JobExecution seedExecution(Job job, int attemptNumber, JobExecutionStatus status) {
        JobExecution exec = new JobExecution(null, job, "worker-1", attemptNumber, status);
        exec.setStartedAt(OffsetDateTime.now());
        if (status == JobExecutionStatus.SUCCEEDED) {
            exec.setFinishedAt(OffsetDateTime.now());
            exec.setOutputSummary("ok");
        } else if (status == JobExecutionStatus.FAILED) {
            exec.setFinishedAt(OffsetDateTime.now());
            exec.setErrorMessage("boom");
        }
        return jobExecutionRepository.saveAndFlush(exec);
    }

    private void seedUser(String email, String rawPassword) {
        seedUserWithRole(email, rawPassword, "USER");
    }

    private void seedUserWithRole(String email, String rawPassword, String roleName) {
        Role role = roleRepository.findByName(roleName)
                .orElseGet(() -> roleRepository.saveAndFlush(new Role(null, roleName)));
        User user = new User(null, email, passwordEncoder.encode(rawPassword), UserStatus.ACTIVE);
        user.addRole(role);
        userRepository.saveAndFlush(user);
    }

    private String loginAndExtractAccess(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asString();
    }
}
