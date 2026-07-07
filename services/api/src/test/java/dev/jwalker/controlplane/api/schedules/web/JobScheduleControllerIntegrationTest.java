package dev.jwalker.controlplane.api.schedules.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.jwalker.controlplane.api.auth.web.dto.LoginRequest;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
import dev.jwalker.controlplane.api.schedules.repository.JobScheduleRepository;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleCreateRequest;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleUpdateRequest;
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
        "app.security.refresh-token-days=7"
})
class JobScheduleControllerIntegrationTest {

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
    private JobScheduleRepository jobScheduleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Raw SQL bypasses both @SQLDelete and @SQLRestriction so
        // soft-deleted rows from prior tests don't block the user delete
        // that follows via the schedules->users FK.
        jdbcTemplate.execute("DELETE FROM job_schedules");
        userRepository.deleteAll();

        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void create_withoutToken_returns401() throws Exception {
        JobScheduleCreateRequest body = new JobScheduleCreateRequest(
                "S1", JobType.CRM_SYNC, "{}", null, null, "0 0 * * * *", "UTC");
        mockMvc.perform(post("/api/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withValidToken_returns201_andPersists() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        JobScheduleCreateRequest body = new JobScheduleCreateRequest(
                "Daily CRM Sync", JobType.CRM_SYNC, "{\"k\":1}",
                JobPriority.HIGH, 5, "0 0 0 * * *", "America/Los_Angeles");

        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Daily CRM Sync"))
                .andExpect(jsonPath("$.ownerEmail").value("alice@example.com"))
                .andExpect(jsonPath("$.cron").value("0 0 0 * * *"))
                .andExpect(jsonPath("$.timezone").value("America/Los_Angeles"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.maxRetries").value(5))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty());

        assertThat(jobScheduleRepository.count()).isEqualTo(1);
    }

    @Test
    void create_withDefaults_appliesMediumPriorityAndThreeRetries() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        JobScheduleCreateRequest body = new JobScheduleCreateRequest(
                "Defaults", JobType.CRM_SYNC, "{}", null, null, "0 0 * * * *", "UTC");

        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("MEDIUM"))
                .andExpect(jsonPath("$.maxRetries").value(3));
    }

    @Test
    void create_withInvalidCron_returns400() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        JobScheduleCreateRequest body = new JobScheduleCreateRequest(
                "Bad", JobType.CRM_SYNC, "{}", null, null, "not-a-cron", "UTC");

        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_CRON"));
    }

    @Test
    void create_withInvalidTimezone_returns400() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        JobScheduleCreateRequest body = new JobScheduleCreateRequest(
                "Bad", JobType.CRM_SYNC, "{}", null, null, "0 0 * * * *", "Mars/Olympus");

        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_TIMEZONE"));
    }

    @Test
    void create_withDuplicateName_returns409() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        JobScheduleCreateRequest body = new JobScheduleCreateRequest(
                "Daily Sync", JobType.CRM_SYNC, "{}", null, null, "0 0 * * * *", "UTC");

        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("DUPLICATE_NAME"));
    }

    @Test
    void create_withMaxRetriesAbove20_returns400() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        JobScheduleCreateRequest body = new JobScheduleCreateRequest(
                "Retry Abuse", JobType.CRM_SYNC, "{}", JobPriority.MEDIUM, 21,
                "0 0 * * * *", "UTC");

        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_withMaxRetriesAbove20_returns400() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        JobScheduleCreateRequest createBody = new JobScheduleCreateRequest(
                "Retry Update", JobType.CRM_SYNC, "{}", JobPriority.MEDIUM, 3,
                "0 0 * * * *", "UTC");
        MvcResult created = mockMvc.perform(post("/api/schedules")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody)))
                .andExpect(status().isCreated())
                .andReturn();
        String scheduleId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asString();

        String patchBody = "{\"maxRetries\":21}";

        mockMvc.perform(patch("/api/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_withMissingName_returns400() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        String bodyJson = "{\"type\":\"CRM_SYNC\",\"payloadJson\":\"{}\",\"cron\":\"0 0 * * * *\",\"timezone\":\"UTC\"}";

        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/schedules/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_withValidToken_returnsSchedule() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        JobScheduleCreateRequest body = new JobScheduleCreateRequest(
                "My Sync", JobType.CRM_SYNC, "{}", JobPriority.HIGH, 5,
                "0 0 0 * * *", "UTC");

        MvcResult created = mockMvc.perform(post("/api/schedules")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        String scheduleId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asString();

        mockMvc.perform(get("/api/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(scheduleId))
                .andExpect(jsonPath("$.name").value("My Sync"))
                .andExpect(jsonPath("$.ownerEmail").value("alice@example.com"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void get_whenScheduleMissing_returns404() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        mockMvc.perform(get("/api/schedules/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_withMalformedId_returns400() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        mockMvc.perform(get("/api/schedules/not-a-uuid")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/schedules"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_returnsEmptyPage_whenNoSchedules() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        mockMvc.perform(get("/api/schedules")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void list_returnsAllSchedules_byDefault() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        createSchedule(accessToken, "Sync 1", JobType.CRM_SYNC, JobPriority.LOW);
        createSchedule(accessToken, "Export 1", JobType.CUSTOMER_EXPORT, JobPriority.HIGH);
        createSchedule(accessToken, "Sync 2", JobType.CRM_SYNC, JobPriority.MEDIUM);

        mockMvc.perform(get("/api/schedules")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void list_filtersByType() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        createSchedule(accessToken, "Sync 1", JobType.CRM_SYNC, JobPriority.LOW);
        createSchedule(accessToken, "Export 1", JobType.CUSTOMER_EXPORT, JobPriority.HIGH);
        createSchedule(accessToken, "Sync 2", JobType.CRM_SYNC, JobPriority.MEDIUM);

        mockMvc.perform(get("/api/schedules")
                        .param("type", "CRM_SYNC")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].type").value("CRM_SYNC"))
                .andExpect(jsonPath("$.content[1].type").value("CRM_SYNC"));
    }

    @Test
    void list_filtersByEnabled() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        createSchedule(accessToken, "Enabled One", JobType.CRM_SYNC, JobPriority.LOW);
        seedDisabledSchedule("alice@example.com", "Disabled One");

        mockMvc.perform(get("/api/schedules")
                        .param("enabled", "false")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Disabled One"))
                .andExpect(jsonPath("$.content[0].enabled").value(false));
    }

    @Test
    void list_paginatesResults() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        createSchedule(accessToken, "S1", JobType.CRM_SYNC, JobPriority.LOW);
        createSchedule(accessToken, "S2", JobType.CRM_SYNC, JobPriority.MEDIUM);
        createSchedule(accessToken, "S3", JobType.CRM_SYNC, JobPriority.HIGH);

        mockMvc.perform(get("/api/schedules")
                        .param("page", "0")
                        .param("size", "2")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void pause_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/schedules/" + UUID.randomUUID() + "/pause"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pause_enabledSchedule_returnsDisabledSchedule() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        String scheduleId = createScheduleAndReturnId(accessToken, "Sync 1");

        mockMvc.perform(post("/api/schedules/" + scheduleId + "/pause")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(scheduleId))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.nextRunAt").doesNotExist());
    }

    @Test
    void pause_alreadyDisabledSchedule_isIdempotent() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        JobSchedule seeded = seedDisabledSchedule("alice@example.com", "Already Off");

        mockMvc.perform(post("/api/schedules/" + seeded.getId() + "/pause")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void pause_missingSchedule_returns404() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        mockMvc.perform(post("/api/schedules/" + UUID.randomUUID() + "/pause")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void resume_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/schedules/" + UUID.randomUUID() + "/resume"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resume_disabledSchedule_returnsEnabledScheduleWithNextRunAt() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        JobSchedule seeded = seedDisabledSchedule("alice@example.com", "Resume Me");

        mockMvc.perform(post("/api/schedules/" + seeded.getId() + "/resume")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(seeded.getId().toString()))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty());
    }

    @Test
    void resume_alreadyEnabledSchedule_isIdempotent() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        String scheduleId = createScheduleAndReturnId(accessToken, "Already On");

        mockMvc.perform(post("/api/schedules/" + scheduleId + "/resume")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void resume_missingSchedule_returns404() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        mockMvc.perform(post("/api/schedules/" + UUID.randomUUID() + "/resume")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_withoutToken_returns401() throws Exception {
        JobScheduleUpdateRequest body = new JobScheduleUpdateRequest(
                "Anything", null, null, null, null, null);
        mockMvc.perform(patch("/api/schedules/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void update_withPartialUpdate_returnsUpdatedSchedule() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        String scheduleId = createScheduleAndReturnId(accessToken, "Original");

        JobScheduleUpdateRequest body = new JobScheduleUpdateRequest(
                "Renamed", null, JobPriority.HIGH, null, null, null);

        mockMvc.perform(patch("/api/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(scheduleId))
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.priority").value("HIGH"));
    }

    @Test
    void update_withInvalidCron_returns400() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        String scheduleId = createScheduleAndReturnId(accessToken, "Sched");

        JobScheduleUpdateRequest body = new JobScheduleUpdateRequest(
                null, null, null, null, "not-a-cron", null);

        mockMvc.perform(patch("/api/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_CRON"));
    }

    @Test
    void update_withDuplicateName_returns409() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        createScheduleAndReturnId(accessToken, "Existing");
        String scheduleId = createScheduleAndReturnId(accessToken, "ToRename");

        JobScheduleUpdateRequest body = new JobScheduleUpdateRequest(
                "Existing", null, null, null, null, null);

        mockMvc.perform(patch("/api/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("DUPLICATE_NAME"));
    }

    @Test
    void update_missingSchedule_returns404() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        JobScheduleUpdateRequest body = new JobScheduleUpdateRequest(
                "Nope", null, null, null, null, null);

        mockMvc.perform(patch("/api/schedules/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_withoutToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/schedules/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void delete_existingSchedule_returns204_andHidesFromGet() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        String scheduleId = createScheduleAndReturnId(accessToken, "ToDelete");

        mockMvc.perform(delete("/api/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_thenRecreateWithSameName_succeeds() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        String scheduleId = createScheduleAndReturnId(accessToken, "Reusable Name");

        mockMvc.perform(delete("/api/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        // Partial unique index excludes the soft-deleted row, so reuse is fine.
        String newId = createScheduleAndReturnId(accessToken, "Reusable Name");
        assertThat(newId).isNotEqualTo(scheduleId);
    }

    @Test
    void delete_missingSchedule_returns404() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        mockMvc.perform(delete("/api/schedules/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    private String createScheduleAndReturnId(String accessToken, String name) throws Exception {
        JobScheduleCreateRequest body = new JobScheduleCreateRequest(
                name, JobType.CRM_SYNC, "{}", null, null, "0 0 * * * *", "UTC");
        MvcResult result = mockMvc.perform(post("/api/schedules")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asString();
    }

    private void createSchedule(String accessToken, String name, JobType type, JobPriority priority) throws Exception {
        JobScheduleCreateRequest body = new JobScheduleCreateRequest(
                name, type, "{}", priority, null, "0 0 * * * *", "UTC");
        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void get_byNonOwnerUser_returns404() throws Exception {
        seedUser("alice@example.com", "password");
        seedUser("bob@example.com", "password");
        String aliceToken = loginAndExtractAccess("alice@example.com", "password");
        String bobToken = loginAndExtractAccess("bob@example.com", "password");
        String scheduleId = createScheduleAndReturnId(aliceToken, "Alice's Sched");

        mockMvc.perform(get("/api/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_byOperator_returns200() throws Exception {
        seedUser("alice@example.com", "password");
        seedUserWithRole("ops@example.com", "password", "OPERATOR");
        String aliceToken = loginAndExtractAccess("alice@example.com", "password");
        String opsToken = loginAndExtractAccess("ops@example.com", "password");
        String scheduleId = createScheduleAndReturnId(aliceToken, "Alice's Sched");

        mockMvc.perform(get("/api/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(scheduleId));
    }

    @Test
    void list_byUser_returnsOnlyOwnSchedules() throws Exception {
        seedUser("alice@example.com", "password");
        seedUser("bob@example.com", "password");
        String aliceToken = loginAndExtractAccess("alice@example.com", "password");
        String bobToken = loginAndExtractAccess("bob@example.com", "password");
        createScheduleAndReturnId(aliceToken, "A1");
        createScheduleAndReturnId(aliceToken, "A2");
        createScheduleAndReturnId(bobToken, "B1");

        mockMvc.perform(get("/api/schedules")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/schedules")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void list_byOperator_returnsAllSchedules() throws Exception {
        seedUser("alice@example.com", "password");
        seedUser("bob@example.com", "password");
        seedUserWithRole("ops@example.com", "password", "OPERATOR");
        String aliceToken = loginAndExtractAccess("alice@example.com", "password");
        String bobToken = loginAndExtractAccess("bob@example.com", "password");
        String opsToken = loginAndExtractAccess("ops@example.com", "password");
        createScheduleAndReturnId(aliceToken, "A1");
        createScheduleAndReturnId(bobToken, "B1");

        mockMvc.perform(get("/api/schedules")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void update_byNonOwnerUser_returns404() throws Exception {
        seedUser("alice@example.com", "password");
        seedUser("bob@example.com", "password");
        String aliceToken = loginAndExtractAccess("alice@example.com", "password");
        String bobToken = loginAndExtractAccess("bob@example.com", "password");
        String scheduleId = createScheduleAndReturnId(aliceToken, "Alice's Sched");

        JobScheduleUpdateRequest body = new JobScheduleUpdateRequest(
                "Bob Tried", null, null, null, null, null);

        mockMvc.perform(patch("/api/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_byNonOwnerUser_returns404() throws Exception {
        seedUser("alice@example.com", "password");
        seedUser("bob@example.com", "password");
        String aliceToken = loginAndExtractAccess("alice@example.com", "password");
        String bobToken = loginAndExtractAccess("bob@example.com", "password");
        String scheduleId = createScheduleAndReturnId(aliceToken, "Alice's Sched");

        mockMvc.perform(delete("/api/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void pause_byOperator_succeeds() throws Exception {
        seedUser("alice@example.com", "password");
        seedUserWithRole("ops@example.com", "password", "OPERATOR");
        String aliceToken = loginAndExtractAccess("alice@example.com", "password");
        String opsToken = loginAndExtractAccess("ops@example.com", "password");
        String scheduleId = createScheduleAndReturnId(aliceToken, "Alice's Sched");

        mockMvc.perform(post("/api/schedules/" + scheduleId + "/pause")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    private JobSchedule seedDisabledSchedule(String ownerEmail, String name) {
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        JobSchedule s = new JobSchedule(null, owner, name, JobType.CRM_SYNC, "{}",
                JobPriority.MEDIUM, 3, "0 0 * * * *", "UTC");
        s.setEnabled(false);
        return jobScheduleRepository.saveAndFlush(s);
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
