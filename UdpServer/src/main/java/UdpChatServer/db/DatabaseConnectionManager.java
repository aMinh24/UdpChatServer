package UdpChatServer.db;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Manages the database connection pool using HikariCP.
 * Reads configuration from config.properties.
 * Ensures database tables are created on initialization.
 */
public class DatabaseConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionManager.class);
    private static final String CONFIG_FILE = "config.properties";
    private static final String DB_SETUP_SCRIPT = "db_setup.sql";
    private static HikariDataSource dataSource;

    // Static initializer block to set up the connection pool on class loading
    static {
        try {
            Properties props = loadConfig();
            HikariConfig config = new HikariConfig();

            config.setJdbcUrl(props.getProperty("db.url"));
            config.setUsername(props.getProperty("db.username"));
            config.setPassword(props.getProperty("db.password"));

            // Pool settings from properties file
            config.setMaximumPoolSize(Integer.parseInt(props.getProperty("db.pool.maximumPoolSize", "10")));
            config.setConnectionTimeout(Long.parseLong(props.getProperty("db.pool.connectionTimeout", "30000")));
            config.setIdleTimeout(Long.parseLong(props.getProperty("db.pool.idleTimeout", "600000")));
            config.setMaxLifetime(Long.parseLong(props.getProperty("db.pool.maxLifetime", "1800000")));

            // Recommended settings for MySQL
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");
            config.addDataSourceProperty("useLocalSessionState", "true");
            config.addDataSourceProperty("rewriteBatchedStatements", "true");
            config.addDataSourceProperty("cacheResultSetMetadata", "true");
            config.addDataSourceProperty("cacheServerConfiguration", "true");
            config.addDataSourceProperty("elideSetAutoCommits", "true");
            config.addDataSourceProperty("maintainTimeStats", "false");

            // Set Driver class name explicitly if needed (usually auto-detected)
            // config.setDriverClassName("com.mysql.cj.jdbc.Driver");

            dataSource = new HikariDataSource(config);
            log.info("HikariCP DataSource initialized successfully for URL: {}", config.getJdbcUrl());

            // Ensure database tables exist by running setup script
            ensureTablesExist();

        } catch (IOException e) {
            log.error("Failed to load database configuration file: {}", CONFIG_FILE, e);
            closeDataSourceInternal();
            throw new RuntimeException("Failed to load database configuration", e);
        } catch (NumberFormatException e) {
            log.error("Invalid numeric value in database pool configuration", e);
            closeDataSourceInternal();
            throw new RuntimeException("Invalid pool configuration value", e);
        } catch (Exception e) {
            log.error("Failed during static initialization or table setup.", e);
            closeDataSourceInternal();
            throw new RuntimeException("Failed to initialize DataSource or setup tables", e);
        }
    }

    /**
     * Loads configuration properties from the config file in the classpath.
     *
     * @return Properties object containing the configuration.
     * @throws IOException if the file cannot be read.
     */
    private static Properties loadConfig() throws IOException {
        Properties props = new Properties();
        InputStream inputStream = DatabaseConnectionManager.class.getClassLoader().getResourceAsStream(CONFIG_FILE);

        if (inputStream == null) {
            throw new FileNotFoundException("Property file '" + CONFIG_FILE + "' not found in the classpath");
        }

        try {
            props.load(inputStream);
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
        return props;
    }

    /**
     * Loads the content of an SQL script file from the classpath resources.
     *
     * @param resourcePath The path to the SQL script file within resources.
     * @return The content of the SQL script as a String.
     * @throws IOException if the file cannot be read.
     */
    private static String loadSqlScript(String resourcePath) throws IOException {
        InputStream inputStream = DatabaseConnectionManager.class.getClassLoader().getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new FileNotFoundException("SQL script file '" + resourcePath + "' not found in the classpath");
        }
        try (InputStream is = inputStream;
             java.util.Scanner scanner = new java.util.Scanner(is, StandardCharsets.UTF_8.name())) {
            return scanner.useDelimiter("\\A").next();
        }
    }

    /**
     * Executes the database setup script (db_setup.sql) to ensure tables exist.
     * Assumes the database itself exists and the dataSource is initialized.
     * Skips CREATE DATABASE and USE statements. Handles comments more robustly.
     */
    private static void ensureTablesExist() {
        String scriptContent;
        try {
            scriptContent = loadSqlScript(DB_SETUP_SCRIPT);
        } catch (IOException e) {
            log.error("Failed to load database setup script '{}'. Tables might not be created.", DB_SETUP_SCRIPT, e);
            throw new RuntimeException("Failed to load database setup script: " + DB_SETUP_SCRIPT, e);
        }

        // Split script into statements using semicolon as delimiter.
        String[] statements = scriptContent.split(";");
        log.debug("Split db_setup.sql into {} potential statements.", statements.length);

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            log.info("Executing database setup script '{}' to ensure tables exist...", DB_SETUP_SCRIPT);
            int executedCount = 0;
            int statementIndex = 0;
            for (String sql : statements) {
                statementIndex++;
                log.debug("Raw statement #{}: '{}'", statementIndex, sql.replace('\n', ' ').replace('\r', ' ').trim());

                // Process the statement to remove comment lines (starting with --)
                String processedSql = Arrays.stream(sql.split("\\r?\\n")) // Split into lines
                                            .map(String::trim) // Trim each line
                                            .filter(line -> !line.startsWith("--") && !line.isEmpty()) // Filter out comments and empty lines
                                            .collect(Collectors.joining("\n")) // Join back with newlines
                                            .trim(); // Trim the final result

                log.debug("Processed statement #{}: '{}'", statementIndex, processedSql.replace('\n', ' ').replace('\r', ' ').trim());

                // Check the processed SQL: Ignore empty, CREATE DATABASE, and USE statements.
                if (processedSql.isEmpty() ||
                    processedSql.toUpperCase().startsWith("CREATE DATABASE") ||
                    processedSql.toUpperCase().startsWith("USE")) {
                    if (!processedSql.isEmpty()) {
                        log.debug("Skipping statement #{}: {}", statementIndex, processedSql.length() > 100 ? processedSql.substring(0, 100) + "..." : processedSql);
                    } else {
                        log.debug("Skipping empty or comment-only statement #{}", statementIndex);
                    }
                    continue; // Skip to the next statement
                }

                try {
                    log.info("Attempting to execute SQL statement #{}: {}", statementIndex, processedSql.length() > 150 ? processedSql.substring(0, 150) + "..." : processedSql);
                    stmt.execute(processedSql);
                    executedCount++;
                    log.info("Successfully executed SQL statement #{}", statementIndex);
                } catch (SQLException e) {
                    if (e.getErrorCode() == 1050) { // MySQL specific code for "Table already exists"
                         log.warn("Table already exists (skipped execution for statement #{}): {}", statementIndex, processedSql.length() > 100 ? processedSql.substring(0, 100) + "..." : processedSql);
                    } else {
                        log.error("Error executing SQL statement #{}: {} - SQLState: {}, ErrorCode: {}, Message: {}",
                                  statementIndex,
                                  processedSql.length() > 100 ? processedSql.substring(0, 100) + "..." : processedSql,
                                  e.getSQLState(), e.getErrorCode(), e.getMessage(), e); // Log the exception too
                        // Consider re-throwing critical errors
                    }
                }
            }
            log.info("Database setup script execution finished. Attempted to execute {} DDL statements.", executedCount);

        } catch (SQLException e) {
            log.error("Failed to get connection or execute database setup script '{}'. Critical error.", DB_SETUP_SCRIPT, e);
            throw new RuntimeException("Failed to execute database setup script: " + DB_SETUP_SCRIPT, e);
        } catch (Exception e) {
            log.error("Unexpected error during database setup script execution.", e);
            throw new RuntimeException("Unexpected error during database setup script execution.", e);
        }
    }

    /**
     * Gets a connection from the connection pool.
     *
     * @return A database connection.
     * @throws SQLException if a database access error occurs.
     */
    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
             log.error("HikariCP DataSource is not initialized!");
             throw new SQLException("DataSource is not initialized.");
        }
        return dataSource.getConnection();
    }

    /**
     * Closes the connection pool. Should be called on application shutdown.
     */
    public static void closeDataSource() {
        closeDataSourceInternal();
        log.info("HikariCP DataSource closed via public method.");
    }

    /**
     * Internal method to close the data source without logging if already null.
     * Used during initialization failure cleanup.
     */
    private static void closeDataSourceInternal() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            dataSource = null;
        } else {
            dataSource = null;
        }
    }

    // Private constructor to prevent instantiation
    private DatabaseConnectionManager() {
    }
}
