package dev.jwalker.controlplane.api.audit.web;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.jwalker.controlplane.api.audit.model.AuditEvent;
import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.repository.AuditEventRepository;
import dev.jwalker.controlplane.api.auth.web.dto.LoginRequest;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "app.security.jwt-secret=test-secret-must-be-at-least-32-bytes-long-xx",
        "app.security.access-token-minutes=15",
        "app.security.refresh-token-days=7"
})
class AuditControllerIntegrationTest {

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
    private AuditEventRepository auditEventRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        auditEventRepository.deleteAll();
        userRepository.deleteAll();

        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_returnsEmptyPage_whenNoEvents() throws Exception {
        seedUser("alice@example.com", "password");
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        // Login itself emits LOGIN_SUCCEEDED; clear so we start from a blank slate.
        auditEventRepository.deleteAll();

        mockMvc.perform(get("/api/audit")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void list_filtersByEventType() throws Exception {
        seedUser("alice@example.com", "password");
        User alice = userRepository.findByEmail("alice@example.com").orElseThrow();
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        auditEventRepository.deleteAll();

        seedEvent(alice, AuditEventType.JOB_CREATED, "Job", UUID.randomUUID());
        seedEvent(alice, AuditEventType.JOB_CANCELLED, "Job", UUID.randomUUID());
        seedEvent(alice, AuditEventType.JOB_CREATED, "Job", UUID.randomUUID());

        mockMvc.perform(get("/api/audit")
                        .param("eventType", "JOB_CREATED")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void list_filtersByActor() throws Exception {
        seedUser("alice@example.com", "password");
        seedUser("bob@example.com", "password");
        User alice = userRepository.findByEmail("alice@example.com").orElseThrow();
        User bob = userRepository.findByEmail("bob@example.com").orElseThrow();
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        auditEventRepository.deleteAll();

        seedEvent(alice, AuditEventType.JOB_CREATED, "Job", UUID.randomUUID());
        seedEvent(alice, AuditEventType.JOB_CREATED, "Job", UUID.randomUUID());
        seedEvent(bob, AuditEventType.JOB_CREATED, "Job", UUID.randomUUID());

        mockMvc.perform(get("/api/audit")
                        .param("actorUserId", alice.getId().toString())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void listForTarget_returnsEventsForResource() throws Exception {
        seedUser("alice@example.com", "password");
        User alice = userRepository.findByEmail("alice@example.com").orElseThrow();
        String accessToken = loginAndExtractAccess("alice@example.com", "password");
        auditEventRepository.deleteAll();

        UUID jobA = UUID.randomUUID();
        UUID jobB = UUID.randomUUID();
        seedEvent(alice, AuditEventType.JOB_CREATED, "Job", jobA);
        seedEvent(alice, AuditEventType.JOB_CANCELLED, "Job", jobA);
        seedEvent(alice, AuditEventType.JOB_CREATED, "Job", jobB);

        mockMvc.perform(get("/api/audit/target/Job/" + jobA)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void list_loginFlowAuditsAreVisible() throws Exception {
        seedUser("alice@example.com", "password");
        // Don't clear after login — assert the LOGIN_SUCCEEDED audit is visible.
        String accessToken = loginAndExtractAccess("alice@example.com", "password");

        mockMvc.perform(get("/api/audit")
                        .param("eventType", "LOGIN_SUCCEEDED")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actorEmail").value("alice@example.com"));
    }

    private void seedUser(String email, String rawPassword) {
        Role role = roleRepository.findByName("USER")
                .orElseGet(() -> roleRepository.saveAndFlush(new Role(null, "USER")));
        User user = new User(null, email, passwordEncoder.encode(rawPassword), UserStatus.ACTIVE);
        user.addRole(role);
        userRepository.saveAndFlush(user);
    }

    private void seedEvent(User actor, AuditEventType type, String targetType, UUID targetId) {
        AuditEvent event = new AuditEvent(
                null, actor, type, targetType, targetId, null, null, null);
        auditEventRepository.saveAndFlush(event);
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
