package dev.jwalker.controlplane.api.jobs.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.jwalker.controlplane.api.auth.web.dto.LoginRequest;
import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository;
import dev.jwalker.controlplane.api.jobs.web.dto.JobCreateRequest;
import dev.jwalker.controlplane.api.users.model.Role;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.RoleRepository;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "app.security.jwt-secret=test-secret-must-be-at-least-32-bytes-long-xx",
        "app.security.access-token-minutes=15",
        "app.security.refresh-token-days=7"
})
class JobControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        userRepository.deleteAll();

        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void create_withoutToken_returns401() throws Exception {
        JobCreateRequest body = new JobCreateRequest(JobType.CRM_SYNC, "{}", null, null, null);
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withValidToken_returns201_andPersistsJob() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        JobCreateRequest body = new JobCreateRequest(
                JobType.CUSTOMER_EXPORT, "{\"region\":\"us-west\"}", JobPriority.HIGH, 5, "idem-42");

        MvcResult result = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.ownerEmail").value("alice@example.com"))
                .andExpect(jsonPath("$.type").value("CUSTOMER_EXPORT"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.maxRetries").value(5))
                .andExpect(jsonPath("$.idempotencyKey").value("idem-42"))
                .andExpect(jsonPath("$.payloadJson").value("{\"region\":\"us-west\"}"))
                .andExpect(jsonPath("$.sourceScheduleId").doesNotExist())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/jobs/" + response.get("id").asString());
        assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test
    void create_withDefaults_appliesMediumPriorityAndThreeRetries() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        JobCreateRequest body = new JobCreateRequest(JobType.CRM_SYNC, "{}", null, null, null);

        mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("MEDIUM"))
                .andExpect(jsonPath("$.maxRetries").value(3))
                .andExpect(jsonPath("$.status").value(JobStatus.PENDING.name()));
    }

    @Test
    void create_withMissingType_returns400() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        String bodyJson = "{\"payloadJson\":\"{}\"}";

        mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/jobs/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_withValidToken_returnsJob() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        JobCreateRequest body = new JobCreateRequest(
                JobType.CUSTOMER_EXPORT, "{\"region\":\"us-west\"}", JobPriority.HIGH, 5, "idem-99");

        MvcResult created = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        String jobId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asString();

        mockMvc.perform(get("/api/jobs/" + jobId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.ownerEmail").value("alice@example.com"))
                .andExpect(jsonPath("$.type").value("CUSTOMER_EXPORT"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.idempotencyKey").value("idem-99"));
    }

    @Test
    void get_whenJobMissing_returns404() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        mockMvc.perform(get("/api/jobs/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_withMalformedId_returns400() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        mockMvc.perform(get("/api/jobs/not-a-uuid")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_returnsEmptyPage_whenNoJobs() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        mockMvc.perform(get("/api/jobs")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void list_returnsAllJobs_byDefault() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        createJob(accessToken, JobType.CRM_SYNC, JobPriority.LOW);
        createJob(accessToken, JobType.CUSTOMER_EXPORT, JobPriority.HIGH);
        createJob(accessToken, JobType.CRM_SYNC, JobPriority.MEDIUM);

        mockMvc.perform(get("/api/jobs")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(3));
    }

    @Test
    void list_filtersByType() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        createJob(accessToken, JobType.CRM_SYNC, JobPriority.LOW);
        createJob(accessToken, JobType.CUSTOMER_EXPORT, JobPriority.HIGH);
        createJob(accessToken, JobType.CRM_SYNC, JobPriority.MEDIUM);

        mockMvc.perform(get("/api/jobs")
                        .param("type", "CRM_SYNC")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].type").value("CRM_SYNC"))
                .andExpect(jsonPath("$.content[1].type").value("CRM_SYNC"));
    }

    @Test
    void list_filtersByPriority() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        createJob(accessToken, JobType.CRM_SYNC, JobPriority.LOW);
        createJob(accessToken, JobType.CUSTOMER_EXPORT, JobPriority.HIGH);
        createJob(accessToken, JobType.CRM_SYNC, JobPriority.MEDIUM);

        mockMvc.perform(get("/api/jobs")
                        .param("priority", "HIGH")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].priority").value("HIGH"));
    }

    @Test
    void list_paginatesResults() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        createJob(accessToken, JobType.CRM_SYNC, JobPriority.LOW);
        createJob(accessToken, JobType.CUSTOMER_EXPORT, JobPriority.HIGH);
        createJob(accessToken, JobType.CRM_SYNC, JobPriority.MEDIUM);

        mockMvc.perform(get("/api/jobs")
                        .param("page", "0")
                        .param("size", "2")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    private void createJob(String accessToken, JobType type, JobPriority priority) throws Exception {
        JobCreateRequest body = new JobCreateRequest(type, "{}", priority, null, null);
        mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void cancel_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/jobs/" + UUID.randomUUID() + "/cancel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cancel_pendingJob_returnsCancelledJob() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        String jobId = createJobAndReturnId(accessToken);

        mockMvc.perform(post("/api/jobs/" + jobId + "/cancel")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancel_nonExistentJob_returns404() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        mockMvc.perform(post("/api/jobs/" + UUID.randomUUID() + "/cancel")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancel_alreadyCancelledJob_returns409() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        String jobId = createJobAndReturnId(accessToken);

        mockMvc.perform(post("/api/jobs/" + jobId + "/cancel")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/jobs/" + jobId + "/cancel")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CANNOT_CANCEL"));
    }

    @Test
    void retry_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/jobs/" + UUID.randomUUID() + "/retry"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void retry_failedJob_returnsPendingJob() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        Job seeded = seedJob("alice@example.com", JobStatus.FAILED);

        mockMvc.perform(post("/api/jobs/" + seeded.getId() + "/retry")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(seeded.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void retry_pendingJob_returns409() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        String jobId = createJobAndReturnId(accessToken);

        mockMvc.perform(post("/api/jobs/" + jobId + "/retry")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CANNOT_RETRY"));
    }

    @Test
    void retry_nonExistentJob_returns404() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        mockMvc.perform(post("/api/jobs/" + UUID.randomUUID() + "/retry")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    private String createJobAndReturnId(String accessToken) throws Exception {
        JobCreateRequest body = new JobCreateRequest(JobType.CRM_SYNC, "{}", null, null, null);
        MvcResult result = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asString();
    }

    private Job seedJob(String ownerEmail, JobStatus status) {
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        Job job = new Job(null, owner, null, JobType.CRM_SYNC, "{}",
                status, JobPriority.MEDIUM, null, 3);
        return jobRepository.saveAndFlush(job);
    }

    @Test
    void create_withBlankPayload_returns400() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        String bodyJson = "{\"type\":\"CRM_SYNC\",\"payloadJson\":\"\"}";

        mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isBadRequest());
    }

    private void seedUser(String email, String rawPassword) {
        Role role = roleRepository.findByName("USER")
                .orElseGet(() -> roleRepository.saveAndFlush(new Role(null, "USER")));
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
