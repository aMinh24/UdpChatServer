package UdpChatServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.util.Properties;

/**
 * Main entry point for the UDP Chat Server application.
 * Initializes all components and starts the request handler.
 */
public class ServerMain {

    private static final Logger log = LoggerFactory.getLogger(ServerMain.class);
    private static final String CONFIG_FILE = "config.properties";

    public static void main(String[] args) {
        log.info("Starting UDP Chat Server...");

        // Load configuration
        Properties configProps = loadConfig();
        int serverPort = Integer.parseInt(configProps.getProperty("server.port", String.valueOf(Constants.DEFAULT_SERVER_PORT)));

        UdpRequestHandler requestHandler = null;

        try {
            // Initialize Managers and DAOs
            // (DAOs don't need explicit initialization as they have static methods or are stateless,
            // but DatabaseConnectionManager initializes its pool statically)
            log.info("Initializing managers...");
            ClientSessionManager sessionManager = new ClientSessionManager();
            RoomManager roomManager = new RoomManager(); // RoomDAO could be injected if needed
            UserDAO userDAO = new UserDAO();
            RoomDAO roomDAO = new RoomDAO();
            MessageDAO messageDAO = new MessageDAO();
            log.info("Managers and DAOs initialized.");

            // Initialize Request Handler
            log.info("Initializing UDP Request Handler on port {}...", serverPort);
            requestHandler = new UdpRequestHandler(serverPort, sessionManager, roomManager, userDAO, roomDAO, messageDAO);

            // Start the Request Handler in a new thread
            Thread handlerThread = new Thread(requestHandler, "UDP-RequestHandler-Thread");
            handlerThread.start();
            log.info("UDP Chat Server started successfully.");

            // Add Shutdown Hook for graceful termination
            UdpRequestHandler finalRequestHandler = requestHandler; // Need final variable for lambda
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutdown hook triggered. Stopping server...");
                if (finalRequestHandler != null) {
                    finalRequestHandler.stop(); // Stop the handler loop and thread pool
                }
                DatabaseConnectionManager.closeDataSource(); // Close the database connection pool
                log.info("Server shut down gracefully.");
            }, "Server-Shutdown-Hook"));

            // Keep the main thread alive (optional, depends on deployment)
            // handlerThread.join(); // Or use another mechanism if needed

        } catch (SocketException e) {
            log.error("Failed to bind UDP socket to port {}: {}", serverPort, e.getMessage(), e);
            System.exit(1); // Exit if socket cannot be created
        } catch (Exception e) {
            log.error("An unexpected error occurred during server startup: {}", e.getMessage(), e);
             // Ensure resources are cleaned up if startup fails partially
            if (requestHandler != null) {
                requestHandler.stop();
            }
            DatabaseConnectionManager.closeDataSource();
            System.exit(1);
        }
    }

     /**
     * Loads configuration properties from the config file in the classpath.
     *
     * @return Properties object containing the configuration.
     */
    private static Properties loadConfig() {
        Properties props = new Properties();
        try (InputStream inputStream = ServerMain.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (inputStream == null) {
                log.warn("Configuration file '{}' not found in classpath. Using defaults.", CONFIG_FILE);
                // Add default values if needed, or rely on defaults in DatabaseConnectionManager/Constants
                props.setProperty("server.port", String.valueOf(Constants.DEFAULT_SERVER_PORT));
                // Add default DB pool properties if DatabaseConnectionManager doesn't handle defaults fully
            } else {
                props.load(inputStream);
                log.info("Loaded configuration from {}", CONFIG_FILE);
            }
        } catch (IOException e) {
            log.error("Failed to load configuration file '{}'. Using defaults. Error: {}", CONFIG_FILE, e.getMessage());
            // Set defaults on error
             props.setProperty("server.port", String.valueOf(Constants.DEFAULT_SERVER_PORT));
        }
        return props;
    }
}
