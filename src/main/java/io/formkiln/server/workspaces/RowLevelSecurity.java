package io.formkiln.server.workspaces;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * PostgreSQL row-level security under the application's workspace checks (policies in
 * migration V14).
 *
 * <p>Connections handed out while a request acts in a workspace ({@link WorkspaceContext}) run as
 * the role {@value #ROLE} with {@code formkiln.workspace_id} set to that workspace, so the database
 * itself hides other workspaces' rows and refuses to write them. Every other connection
 * (migrations, background jobs, sign-in, instance administration) keeps the login role, which
 * owns the tables and is not subject to the policies. Both settings are reset before a connection
 * goes back to the pool.
 *
 * <p>{@code formkiln.rls}: {@code enforce} (default) or {@code off}. With {@code enforce} the server
 * creates the role and grants it the data tables at start, and refuses to start when the database
 * user may not do that, naming the SQL a DBA has to run once.
 */
@Configuration(proxyBeanMethods = false)
public class RowLevelSecurity {

    public static final String ROLE = "formkiln_app";
    static final String SETTING = "formkiln.workspace_id";

    private static final Logger log = LoggerFactory.getLogger(RowLevelSecurity.class);

    static boolean enforced(Environment environment) {
        String mode = environment.getProperty("formkiln.rls", "enforce").trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "enforce" -> true;
            case "off" -> false;
            default -> throw new IllegalStateException("FORMKILN_RLS must be enforce or off, not " + mode);
        };
    }

    /** Wraps the application's DataSource so that workspace requests run under the policies. */
    @Bean
    static BeanPostProcessor workspaceDataSourcePostProcessor(Environment environment) {
        boolean enforced = enforced(environment);
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                return enforced && bean instanceof DataSource dataSource && !(bean instanceof WorkspaceDataSource)
                        ? new WorkspaceDataSource(dataSource)
                        : bean;
            }
        };
    }

    /** Creates the role and grants it the data tables, after the migrations ran. */
    @Bean
    @DependsOn("flywayInitializer")
    static Object rowLevelSecuritySetup(DataSource dataSource, Environment environment) throws SQLException {
        if (!enforced(environment)) {
            log.warn("Row-level security is off (FORMKILN_RLS=off): workspaces are kept apart by the application's checks only.");
            return new Object();
        }
        DataSource owner = dataSource instanceof WorkspaceDataSource wrapped ? wrapped.getTargetDataSource() : dataSource;
        try (Connection connection = owner.getConnection()) {
            setUp(connection);
        }
        log.info("Row-level security enforced: workspace requests run as {}", ROLE);
        return new Object();
    }

    static void setUp(Connection connection) throws SQLException {
        String user = single(connection, "SELECT current_user");
        String schema = single(connection, "SELECT current_schema()");
        if (!roleExists(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE ROLE " + ROLE + " NOLOGIN");
            } catch (SQLException e) {
                if (!roleExists(connection)) { // Another instance may have created it meanwhile.
                    throw cannotSetUp(user, e);
                }
            }
        }
        if (!canSetRole(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("GRANT " + ROLE + " TO " + quote(user));
            } catch (SQLException e) {
                throw cannotSetUp(user, e);
            }
            if (!canSetRole(connection)) {
                throw cannotSetUp(user, null);
            }
        }
        String quotedSchema = quote(schema);
        try (Statement statement = connection.createStatement()) {
            statement.execute("GRANT USAGE ON SCHEMA " + quotedSchema + " TO " + ROLE);
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " + quotedSchema + " TO " + ROLE);
            statement.execute("GRANT USAGE ON ALL SEQUENCES IN SCHEMA " + quotedSchema + " TO " + ROLE);
            statement.execute("REVOKE ALL ON " + quotedSchema + ".flyway_schema_history FROM " + ROLE);
        }
    }

    private static boolean roleExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
            statement.setString(1, ROLE);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Whether the login role may switch to the application role. */
    private static boolean canSetRole(Connection connection) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL ROLE " + ROLE);
            return true;
        } catch (SQLException e) {
            return false;
        } finally {
            connection.rollback();
            connection.setAutoCommit(autoCommit);
        }
    }

    private static String single(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static IllegalStateException cannotSetUp(String user, SQLException cause) {
        return new IllegalStateException("""
                Row-level security needs the database role %1$s, and the database user %2$s cannot create it or \
                switch to it. Ask your database administrator to run once:

                    CREATE ROLE %1$s NOLOGIN;
                    GRANT %1$s TO %3$s;

                The server grants the role its tables itself. To run without row-level security (the application's \
                workspace checks only), set FORMKILN_RLS=off.""".formatted(ROLE, user, quote(user)), cause);
    }

    /**
     * Hands out connections that act as {@value #ROLE} in the request's workspace while a request
     * acts in one, and resets them before they go back to the pool.
     */
    static final class WorkspaceDataSource extends DelegatingDataSource {

        WorkspaceDataSource(DataSource target) {
            super(target);
        }

        @Override
        public DataSource getTargetDataSource() {
            return super.getTargetDataSource();
        }

        @Override
        public Connection getConnection() throws SQLException {
            return scoped(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return scoped(super.getConnection(username, password));
        }

        private Connection scoped(Connection connection) throws SQLException {
            Optional<WorkspaceContext.Current> workspace = WorkspaceContext.current();
            if (workspace.isEmpty()) {
                return connection;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT set_config('role', '" + ROLE + "', false), set_config('" + SETTING + "', ?, false)")) {
                statement.setString(1, workspace.get().id().toString());
                statement.execute();
            } catch (SQLException e) {
                connection.close();
                throw e;
            }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    new Resetting(connection));
        }

        /** Resets the role and the workspace before the connection is returned to the pool. */
        private record Resetting(Connection target) implements InvocationHandler {
            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                    if (!target.isClosed()) {
                        try (Statement statement = target.createStatement()) {
                            statement.execute("RESET ROLE; RESET " + SETTING);
                        } catch (SQLException e) {
                            // Never let a connection that may still act in a workspace be reused: abort
                            // the physical connection, so that the pool discards it.
                            log.warn("Could not reset a workspace connection; it is discarded", e);
                            target.abort(Runnable::run);
                        }
                    }
                    target.close();
                    return null;
                }
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }
    }
}
