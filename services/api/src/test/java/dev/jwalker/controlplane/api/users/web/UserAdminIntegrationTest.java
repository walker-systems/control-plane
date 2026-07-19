package dev.jwalker.controlplane.api.users.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.repository.AuditEventRepository;
import dev.jwalker.controlplane.api.auth.web.dto.LoginRequest;
import dev.jwalker.controlplane.api.users.model.Role;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.RoleRepository;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.util.Map;
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
class UserAdminIntegrationTest {

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

    // --- authorization ------------------------------------------------

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_byOperator_returns403() throws Exception {
        seedUserWithRole("op@example.com", "password-123456", "OPERATOR");
        String token = login("op@example.com", "password-123456");

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_byUserRole_returns403() throws Exception {
        seedUserWithRole("user@example.com", "password-123456", "USER");
        String token = login("user@example.com", "password-123456");

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "new@example.com", "password", "password-123456"))))
                .andExpect(status().isForbidden());
    }

    // --- create -------------------------------------------------------

    @Test
    void create_byAdmin_createsUserWithDefaultRole_andAudits() throws Exception {
        String token = seedAdminAndLogin();

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "new@example.com", "password", "password-123456"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles[0]").value("USER"));

        assertThat(auditEventRepository.findAll())
                .anyMatch(e -> e.getEventType() == AuditEventType.USER_CREATED);
    }

    @Test
    void create_normalizesEmailCase() throws Exception {
        String token = seedAdminAndLogin();

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "MiXeD@Example.COM", "password", "password-123456"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("mixed@example.com"));
    }

    @Test
    void create_duplicateEmail_returns409WithReason() throws Exception {
        String token = seedAdminAndLogin();
        seedUserWithRole("taken@example.com", "password-123456", "USER");

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "taken@example.com", "password", "password-123456"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("DUPLICATE_EMAIL"));
    }

    @Test
    void create_unknownRole_returns400WithReason() throws Exception {
        String token = seedAdminAndLogin();

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "new@example.com",
                                "password", "password-123456",
                                "roles", new String[]{"SUPERUSER"}))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("UNKNOWN_ROLE"));
    }

    @Test
    void create_shortPassword_returns400() throws Exception {
        String token = seedAdminAndLogin();

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "new@example.com", "password", "short"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createdUser_canLogin() throws Exception {
        String token = seedAdminAndLogin();

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "fresh@example.com", "password", "password-123456"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("fresh@example.com", "password-123456"))))
                .andExpect(status().isOk());
    }

    // --- list ---------------------------------------------------------

    @Test
    void list_byAdmin_returnsUsersWithRoles() throws Exception {
        String token = seedAdminAndLogin();
        seedUserWithRole("a@example.com", "password-123456", "USER");
        seedUserWithRole("b@example.com", "password-123456", "OPERATOR");

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].roles").exists());
    }

    // --- update -------------------------------------------------------

    @Test
    void update_lockUser_blocksLogin_andAudits() throws Exception {
        String token = seedAdminAndLogin();
        seedUserWithRole("victim@example.com", "password-123456", "USER");
        User victim = userRepository.findByEmail("victim@example.com").orElseThrow();

        mockMvc.perform(patch("/api/users/" + victim.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "LOCKED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"));

        // guardStatus in AuthService rejects LOCKED at login — 403, not
        // 401: credentials were right, the account is blocked
        // (AuthController maps ACCOUNT_LOCKED/DISABLED to FORBIDDEN).
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("victim@example.com", "password-123456"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("ACCOUNT_LOCKED"));

        assertThat(auditEventRepository.findAll())
                .anyMatch(e -> e.getEventType() == AuditEventType.USER_STATUS_CHANGED);
    }

    @Test
    void update_lockUser_revokesRefreshTokens() throws Exception {
        String adminToken = seedAdminAndLogin();
        seedUserWithRole("victim@example.com", "password-123456", "USER");
        User victim = userRepository.findByEmail("victim@example.com").orElseThrow();

        // Victim logs in and holds a refresh token.
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("victim@example.com", "password-123456"))))
                .andExpect(status().isOk())
                .andReturn();
        String refreshToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("refreshToken").asString();

        mockMvc.perform(patch("/api/users/" + victim.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "LOCKED"))))
                .andExpect(status().isOk());

        // The outstanding refresh token is dead, not just the status flag.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void update_roles_promotesToOperator_andAudits() throws Exception {
        String token = seedAdminAndLogin();
        seedUserWithRole("promotee@example.com", "password-123456", "USER");
        User promotee = userRepository.findByEmail("promotee@example.com").orElseThrow();

        mockMvc.perform(patch("/api/users/" + promotee.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("roles", new String[]{"OPERATOR"}))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("OPERATOR"));

        assertThat(auditEventRepository.findAll())
                .anyMatch(e -> e.getEventType() == AuditEventType.USER_ROLE_CHANGED);
    }

    @Test
    void update_ownAccount_returns409SelfModification() throws Exception {
        String token = seedAdminAndLogin();
        User admin = userRepository.findByEmail("admin@example.com").orElseThrow();

        mockMvc.perform(patch("/api/users/" + admin.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "LOCKED"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("SELF_MODIFICATION"));
    }

    @Test
    void update_unknownUser_returns404() throws Exception {
        String token = seedAdminAndLogin();

        mockMvc.perform(patch("/api/users/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "LOCKED"))))
                .andExpect(status().isNotFound());
    }

    // --- helpers ------------------------------------------------------

    private String seedAdminAndLogin() throws Exception {
        seedUserWithRole("admin@example.com", "password-123456", "ADMIN");
        return login("admin@example.com", "password-123456");
    }

    private void seedUserWithRole(String email, String rawPassword, String roleName) {
        Role role = roleRepository.findByName(roleName)
                .orElseGet(() -> roleRepository.saveAndFlush(new Role(null, roleName)));
        User user = new User(null, email, passwordEncoder.encode(rawPassword), UserStatus.ACTIVE);
        user.addRole(role);
        userRepository.saveAndFlush(user);
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asString();
    }
}
