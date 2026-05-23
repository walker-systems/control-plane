package dev.jwalker.controlplane.api.schedules.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.jwalker.controlplane.api.auth.web.dto.LoginRequest;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.schedules.repository.JobScheduleRepository;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleCreateRequest;
import dev.jwalker.controlplane.api.users.model.Role;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.RoleRepository;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
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
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // deleteAllInBatch issues a bulk DELETE that bypasses @SQLDelete,
        // hard-deleting rows so the user delete that follows is not blocked
        // by FK references to soft-deleted schedules.
        jobScheduleRepository.deleteAllInBatch();
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
