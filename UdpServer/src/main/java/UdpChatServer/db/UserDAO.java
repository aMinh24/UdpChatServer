package UdpChatServer.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data Access Object for User related operations.
 */
public class UserDAO {

    private static final Logger log = LoggerFactory.getLogger(UserDAO.class);

    /**
     * Authenticates a user based on chatid and password.
     * IMPORTANT: In a real application, passwords should be hashed and salted.
     * This implementation uses plain text comparison for simplicity based on the initial setup.
     *
     * @param chatid   The user's chat ID.
     * @param password The user's plain text password.
     * @return true if authentication is successful, false otherwise.
     */
    public boolean authenticateUser(String chatid, String password) {
        // Input validation
        if (chatid == null || chatid.trim().isEmpty() || password == null) {
            log.warn("Authentication attempt with invalid input (chatid: {})", chatid);
            return false;
        }

        String sql = "SELECT password FROM users WHERE chatid = ?";
        boolean authenticated = false;

        // Use try-with-resources for automatic resource management
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, chatid);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String storedPassword = rs.getString("password");
                    // !! SECURITY WARNING !!
                    // !! Comparing plain text passwords is highly insecure. !!
                    // !! In a production environment, use a strong hashing algorithm (e.g., bcrypt) !!
                    // !! Example with hashing: passwordEncoder.matches(password, storedPasswordHash) !!
                    if (password.equals(storedPassword)) {
                        authenticated = true;
                        log.info("User '{}' authenticated successfully.", chatid);
                    } else {
                        log.warn("Authentication failed for user '{}' due to incorrect password.", chatid);
                    }
                } else {
                    log.warn("Authentication failed: User '{}' not found.", chatid);
                }
            }
        } catch (SQLException e) {
            log.error("SQL error during authentication for user '{}': {}", chatid, e.getMessage(), e);
            // Depending on the error, you might want to throw a custom exception
        } catch (Exception e) {
            log.error("Unexpected error during authentication for user '{}': {}", chatid, e.getMessage(), e);
        }

        return authenticated;
    }

    /**
     * Checks if a user with the given chatid exists in the database.
     *
     * @param chatid The chat ID to check.
     * @return true if the user exists, false otherwise.
     */
    public boolean userExists(String chatid) {
        if (chatid == null || chatid.trim().isEmpty()) {
            return false;
        }
        String sql = "SELECT 1 FROM users WHERE chatid = ? LIMIT 1";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, chatid);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next(); // True if a row is found
            }
        } catch (SQLException e) {
            log.error("SQL error checking existence for user '{}': {}", chatid, e.getMessage(), e);
            return false; // Assume user doesn't exist on error
        } catch (Exception e) {
            log.error("Unexpected error checking existence for user '{}': {}", chatid, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Adds a new user to the database.
     * IMPORTANT: Ensure the password is properly hashed before calling this method in a real app.
     *
     * @param chatid The user's chat ID.
     * @param hashedPassword The hashed password.
     * @return true if the user was added successfully, false otherwise.
     */
    public boolean addUser(String chatid, String hashedPassword) {
         if (chatid == null || chatid.trim().isEmpty() || hashedPassword == null || hashedPassword.isEmpty()) {
            log.warn("Attempted to add user with invalid input (chatid: {})", chatid);
            return false;
        }
        String sql = "INSERT INTO users (chatid, password) VALUES (?, ?)";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, chatid);
            pstmt.setString(2, hashedPassword); // Store the hashed password

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                 log.info("User '{}' added successfully.", chatid);
                 return true;
            } else {
                 log.warn("Failed to add user '{}'. No rows affected.", chatid);
                 return false;
            }
        } catch (SQLException e) {
            // Handle potential duplicate entry errors (e.g., SQLState "23000")
            if ("23000".equals(e.getSQLState())) {
                 log.warn("Failed to add user '{}': User already exists.", chatid);
            } else {
                log.error("SQL error while adding user '{}': {}", chatid, e.getMessage(), e);
            }
            return false;
        } catch (Exception e) {
            log.error("Unexpected error while adding user '{}': {}", chatid, e.getMessage(), e);
            return false;
        }
    }
}
