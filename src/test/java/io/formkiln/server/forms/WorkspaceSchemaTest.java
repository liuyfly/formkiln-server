package io.formkiln.server.forms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.formkiln.server.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The V10 workspace schema: backfill, per-workspace keys and the composite foreign keys. */
@SpringBootTest(properties = "formkiln.service-token=test-token")
@Import(TestcontainersConfiguration.class)
class WorkspaceSchemaTest {

    private static final String TOKEN = "Bearer test-token";

    /** Every table that holds form data; each row must carry its form's workspace. */
    private static final List<String> DATA_TABLES = List.of("form", "form_folder", "form_version", "submission",
            "form_draft", "file_object", "webhook_endpoint", "webhook_delivery", "form_working_copy",
            "form_notification_settings", "mail_outbox");

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private DataSource dataSource;

    private MockMvc mvc;
    private UUID defaultWorkspace;

    private static String form(String key) {
        return """
                {"schemaVersion": 1, "key": "%s", "title": "T",
                 "pages": [{"key": "p", "elements": [{"type": "text", "key": "name", "label": "Name"}]}],
                 "submit": {"outcomes": [{"key": "done"}]}}
                """.formatted(key);
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("TRUNCATE form_working_copy, submission, form_version, form, form_folder, audit_log CASCADE").update();
        jdbc.sql("DELETE FROM workspace_access").update();
        jdbc.sql("DELETE FROM workspace WHERE slug <> 'default'").update();
        defaultWorkspace = jdbc.sql("SELECT id FROM workspace WHERE slug = 'default'").query(UUID.class).single();
    }

    private UUID otherWorkspace() {
        return jdbc.sql("INSERT INTO workspace (slug, name) VALUES ('hr', 'HR') RETURNING id").query(UUID.class).single();
    }

    private UUID formId(String key) {
        return jdbc.sql("SELECT id FROM form WHERE key = :key AND workspace_id = :ws")
                .param("key", key).param("ws", defaultWorkspace).query(UUID.class).single();
    }

    @Test
    void newRowsBelongToTheDefaultWorkspace() throws Exception {
        mvc.perform(post("/v1/manage/workspaces/default/forms/{key}/versions", "alpha").header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(form("alpha"))).andExpect(status().isCreated());
        mvc.perform(post("/v1/w/default/forms/{key}/submissions", "alpha").contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\": {\"name\": \"Ada\"}}")).andExpect(status().isCreated());
        mvc.perform(post("/v1/manage/workspaces/default/forms/{key}/webhooks", "alpha").header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\": \"https://example.com/hook\"}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/v1/manage/workspaces/default/forms/{key}/notifications", "alpha").header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"teamRecipients\": [\"team@example.com\"]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/w/default/forms/{key}/submissions", "alpha").contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\": {\"name\": \"Grace\"}}")).andExpect(status().isCreated());

        for (String table : List.of("form", "form_version", "submission", "webhook_endpoint", "webhook_delivery",
                "form_notification_settings", "mail_outbox")) {
            List<UUID> workspaces = jdbc.sql("SELECT DISTINCT workspace_id FROM " + table).query(UUID.class).list();
            assertThat(workspaces).as(table).containsExactly(defaultWorkspace);
        }
    }

    @Test
    void keysAndFolderNamesAreUniquePerWorkspace() {
        UUID hr = otherWorkspace();
        jdbc.sql("INSERT INTO form (key, workspace_id) VALUES ('onboarding', :ws)").param("ws", defaultWorkspace).update();
        jdbc.sql("INSERT INTO form (key, workspace_id) VALUES ('onboarding', :ws)").param("ws", hr).update();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO form (key, workspace_id) VALUES ('onboarding', :ws)")
                .param("ws", hr).update()).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.sql("INSERT INTO form_folder (name, workspace_id) VALUES ('Intake', :ws)").param("ws", defaultWorkspace).update();
        jdbc.sql("INSERT INTO form_folder (name, workspace_id) VALUES ('intake', :ws)").param("ws", hr).update();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO form_folder (name, workspace_id) VALUES ('INTAKE', :ws)")
                .param("ws", hr).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rowsCannotPointAtAnotherWorkspacesForm() {
        UUID hr = otherWorkspace();
        jdbc.sql("INSERT INTO form (key, workspace_id) VALUES ('alpha', :ws)").param("ws", defaultWorkspace).update();
        UUID alpha = formId("alpha");

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO form_version (form_id, workspace_id, version, schema_version, definition)
                        VALUES (:form, :ws, 1, 1, '{}')
                        """).param("form", alpha).param("ws", hr).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID hrFolder = jdbc.sql("INSERT INTO form_folder (name, workspace_id) VALUES ('HR only', :ws) RETURNING id")
                .param("ws", hr).query(UUID.class).single();
        assertThatThrownBy(() -> jdbc.sql("UPDATE form SET folder_id = :folder WHERE id = :id")
                .param("folder", hrFolder).param("id", alpha).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID version = jdbc.sql("""
                        INSERT INTO form_version (form_id, workspace_id, version, schema_version, definition)
                        VALUES (:form, :ws, 1, 1, '{}') RETURNING id
                        """).param("form", alpha).param("ws", defaultWorkspace).query(UUID.class).single();
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO submission (form_version_id, workspace_id, data) VALUES (:version, :ws, '{}')
                        """).param("version", version).param("ws", hr).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** A database at V9 with data in every table moves into the default workspace. */
    @Test
    void migrationMovesExistingDataIntoTheDefaultWorkspace() {
        String schema = "upgrade_" + Long.toHexString(System.nanoTime());
        Flyway.configure().dataSource(dataSource).schemas(schema).target("9").load().migrate();
        JdbcClient db = JdbcClient.create(dataSource);
        String s = schema + ".";

        UUID admin = db.sql("INSERT INTO " + s + "admin_user (email, password_hash, role) VALUES ('a@example.com', 'x', 'admin') RETURNING id")
                .query(UUID.class).single();
        UUID viewer = db.sql("INSERT INTO " + s + "admin_user (email, password_hash, role) VALUES ('v@example.com', 'x', 'viewer') RETURNING id")
                .query(UUID.class).single();
        UUID folder = db.sql("INSERT INTO " + s + "form_folder (name) VALUES ('Intake') RETURNING id").query(UUID.class).single();
        UUID form = db.sql("INSERT INTO " + s + "form (key, folder_id) VALUES ('alpha', :folder) RETURNING id")
                .param("folder", folder).query(UUID.class).single();
        UUID version = db.sql("INSERT INTO " + s + "form_version (form_id, version, schema_version, definition) VALUES (:f, 1, 1, '{}') RETURNING id")
                .param("f", form).query(UUID.class).single();
        UUID submission = db.sql("INSERT INTO " + s + "submission (form_version_id, data) VALUES (:v, '{}') RETURNING id")
                .param("v", version).query(UUID.class).single();
        db.sql("INSERT INTO " + s + "form_draft (form_id, token_hash, data, expires_at) VALUES (:f, '\\x01', '\\x01', now())")
                .param("f", form).update();
        db.sql("INSERT INTO " + s + "file_object (form_id, field_key, name, size, content_type, storage_key) VALUES (:f, 'doc', 'a.pdf', 1, 'application/pdf', 'k1')")
                .param("f", form).update();
        UUID endpoint = db.sql("INSERT INTO " + s + "webhook_endpoint (form_id, url, secret) VALUES (:f, 'https://example.com', '\\x01') RETURNING id")
                .param("f", form).query(UUID.class).single();
        db.sql("INSERT INTO " + s + "webhook_delivery (endpoint_id, submission_id, event, payload) VALUES (:e, :s, 'submission.created', '\\x01')")
                .param("e", endpoint).param("s", submission).update();
        db.sql("INSERT INTO " + s + "form_working_copy (form_id, document, revision) VALUES (:f, '{}', 1)").param("f", form).update();
        db.sql("INSERT INTO " + s + "form_notification_settings (form_id) VALUES (:f)").param("f", form).update();
        db.sql("INSERT INTO " + s + "mail_outbox (form_id, kind, recipient_hint, payload) VALUES (:f, 'team', 't***@example.com', '\\x01')")
                .param("f", form).update();
        db.sql("INSERT INTO " + s + "mail_outbox (kind, recipient_hint, payload) VALUES ('resume', 'r***@example.com', '\\x01')").update();
        db.sql("INSERT INTO " + s + "audit_log (actor_type, action, target) VALUES ('user', 'form.published', 'alpha')").update();
        db.sql("INSERT INTO " + s + "audit_log (actor_type, action, target) VALUES ('user', 'login.failed', 'a@example.com')").update();

        Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();

        UUID workspace = db.sql("SELECT id FROM " + s + "workspace WHERE slug = 'default'").query(UUID.class).single();
        for (String table : DATA_TABLES) {
            List<UUID> workspaces = db.sql("SELECT DISTINCT workspace_id FROM " + s + table).query(UUID.class).list();
            assertThat(workspaces).as(table).containsExactly(workspace);
        }
        assertThat(db.sql("SELECT user_id, role FROM " + s + "workspace_member ORDER BY role")
                .query((rs, n) -> rs.getObject("user_id", UUID.class) + ":" + rs.getString("role")).list())
                .containsExactly(admin + ":admin", viewer + ":viewer");
        assertThat(db.sql("SELECT email FROM " + s + "admin_user WHERE instance_admin").query(String.class).list())
                .containsExactly("a@example.com");
        assertThat(db.sql("SELECT action FROM " + s + "audit_log WHERE workspace_id IS NOT NULL").query(String.class).list())
                .containsExactly("form.published");

        db.sql("DROP SCHEMA " + schema + " CASCADE").update();
    }
}
