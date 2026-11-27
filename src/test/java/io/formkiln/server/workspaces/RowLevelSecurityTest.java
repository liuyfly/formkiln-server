package io.formkiln.server.workspaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.formkiln.server.TestMembers;
import io.formkiln.server.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/**
 * The database keeps workspaces apart on its own: a query that forgets its workspace condition
 * still only sees the request's workspace.
 */
@SpringBootTest(properties = "formkiln.service-token=test-token")
@Import({TestcontainersConfiguration.class, RowLevelSecurityTest.Probe.class})
class RowLevelSecurityTest {

    /** A deliberately careless endpoint: it reads every form without a workspace condition. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Probe {
        @Bean
        ProbeController probeController(JdbcClient jdbc) {
            return new ProbeController(jdbc);
        }
    }

    @RestController
    static class ProbeController {
        private final JdbcClient jdbc;

        ProbeController(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @GetMapping("/v1/manage/workspaces/{workspace}/rls-probe")
        Map<String, Object> probe() {
            return Map.of(
                    "user", jdbc.sql("SELECT current_user").query(String.class).single(),
                    "keys", jdbc.sql("SELECT key FROM form ORDER BY key").query(String.class).list(),
                    "versions", jdbc.sql("SELECT COUNT(*) FROM form_version").query(Long.class).single());
        }
    }

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private DataSource dataSource;

    private MockMvc mvc;
    private UUID defaultWorkspace;
    private UUID hr;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("TRUNCATE form_working_copy, submission, form_version, form, form_folder, audit_log, service_token CASCADE").update();
        jdbc.sql("DELETE FROM workspace_access").update();
        jdbc.sql("DELETE FROM admin_invitation WHERE workspace_id IN (SELECT id FROM workspace WHERE slug <> 'default')").update();
        jdbc.sql("DELETE FROM workspace_member WHERE workspace_id IN (SELECT id FROM workspace WHERE slug <> 'default')").update();
        jdbc.sql("DELETE FROM workspace WHERE slug <> 'default'").update();
        defaultWorkspace = jdbc.sql("SELECT id FROM workspace WHERE slug = 'default'").query(UUID.class).single();
        hr = jdbc.sql("INSERT INTO workspace (slug, name) VALUES ('hr', 'HR') RETURNING id").query(UUID.class).single();
        for (Object[] form : new Object[][] {{"alpha", defaultWorkspace}, {"beta", defaultWorkspace}, {"payroll", hr}}) {
            UUID id = jdbc.sql("INSERT INTO form (key, workspace_id) VALUES (:key, :ws) RETURNING id")
                    .param("key", form[0]).param("ws", form[1]).query(UUID.class).single();
            jdbc.sql("INSERT INTO form_version (form_id, workspace_id, version, schema_version, definition) VALUES (:id, :ws, 1, 1, '{}')")
                    .param("id", id).param("ws", form[1]).update();
        }
    }

    @Test
    void aCarelessQueryInARequestOnlySeesTheRequestsWorkspace() throws Exception {
        RequestPostProcessor hrMember = TestMembers.member(jdbc, "hr", "viewer");
        mvc.perform(get("/v1/manage/workspaces/hr/rls-probe").with(hrMember)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user").value(RowLevelSecurity.ROLE))
                .andExpect(jsonPath("$.keys").value(org.hamcrest.Matchers.contains("payroll")))
                .andExpect(jsonPath("$.versions").value(1));
        mvc.perform(get("/v1/manage/workspaces/default/rls-probe").header("Authorization", "Bearer test-token"))
                .andExpect(jsonPath("$.keys").value(org.hamcrest.Matchers.contains("alpha", "beta")));
    }

    @Test
    void connectionsReturnToThePoolAsTheLoginRole() throws Exception {
        String login = jdbc.sql("SELECT current_user").query(String.class).single();
        RequestPostProcessor hrMember = TestMembers.member(jdbc, "hr", "viewer");
        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/v1/manage/workspaces/hr/rls-probe").with(hrMember)).andExpect(status().isOk());
        }
        // Outside a request every pooled connection is the owner again, without a workspace.
        for (int i = 0; i < 20; i++) {
            assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo(login);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM form").query(Long.class).single()).isEqualTo(3);
            assertThat(jdbc.sql("SELECT COALESCE(current_setting('formkiln.workspace_id', true), '')").query(String.class).single())
                    .isEmpty();
        }
    }

    /** The policies on their own, as the application role. */
    @Test
    void thePoliciesHideAndRefuseOtherWorkspacesRows() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL ROLE " + RowLevelSecurity.ROLE);
            // Without a workspace nothing is visible (fail closed).
            for (String table : List.of("form", "form_version", "form_folder", "submission", "audit_log", "workspace_member")) {
                assertThat(count(statement, "SELECT COUNT(*) FROM " + table)).as(table).isZero();
            }
            statement.execute("SELECT set_config('formkiln.workspace_id', '" + hr + "', true)");
            assertThat(count(statement, "SELECT COUNT(*) FROM form")).isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM form_version")).isEqualTo(1);
            // Updates and deletes do not reach other workspaces' rows.
            assertThat(statement.executeUpdate("UPDATE form SET access_mode = 'token'")).isEqualTo(1);
            assertThat(statement.executeUpdate("DELETE FROM form_version WHERE workspace_id = '" + defaultWorkspace + "'")).isZero();
            // Writing a row of another workspace is refused.
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO form (key, workspace_id) VALUES ('sneaky', '" + defaultWorkspace + "')"))
                    .hasMessageContaining("row-level security");
            connection.rollback();
        }
    }

    /** A database user that may not create or switch to the role gets the SQL for its DBA. */
    @Test
    void aUserWithoutTheRightsIsToldWhatToAskFor() throws SQLException {
        String url;
        try (Connection connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL();
        }
        jdbc.sql("DROP ROLE IF EXISTS limited_owner").update();
        jdbc.sql("CREATE ROLE limited_owner LOGIN PASSWORD 'limited-password'").update();
        try (Connection limited = java.sql.DriverManager.getConnection(url, "limited_owner", "limited-password")) {
            assertThatThrownBy(() -> RowLevelSecurity.setUp(limited))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CREATE ROLE formkiln_app NOLOGIN;")
                    .hasMessageContaining("GRANT formkiln_app TO \"limited_owner\";")
                    .hasMessageContaining("FORMKILN_RLS=off");
        } finally {
            jdbc.sql("DROP ROLE limited_owner").update();
        }
    }

    private static long count(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
