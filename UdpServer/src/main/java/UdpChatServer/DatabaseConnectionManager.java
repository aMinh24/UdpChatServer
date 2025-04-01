package UdpChatServer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Manages the database connection pool using HikariCP.
 * Reads configuration from config.properties.
 */
public class DatabaseConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionManager.class);
    private static final String CONFIG_FILE = "config.properties";
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

        } catch (IOException e) {
            log.error("Failed to load database configuration file: {}", CONFIG_FILE, e);
            throw new RuntimeException("Failed to load database configuration", e);
        } catch (NumberFormatException e) {
            log.error("Invalid numeric value in database pool configuration", e);
            throw new RuntimeException("Invalid pool configuration value", e);
        } catch (Exception e) {
            log.error("Failed to initialize HikariCP DataSource", e);
            throw new RuntimeException("Failed to initialize DataSource", e);
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
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("HikariCP DataSource closed.");
        }
    }

    // Private constructor to prevent instantiation
    private DatabaseConnectionManager() {
    }
}
