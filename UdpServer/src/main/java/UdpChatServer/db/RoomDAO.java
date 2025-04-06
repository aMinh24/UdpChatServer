package UdpChatServer.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data Access Object for Room related operations.
 * Includes owner checks for management actions.
 */
public class RoomDAO {

    private static final Logger log = LoggerFactory.getLogger(RoomDAO.class);

    /**
     * Creates a new room in the database if it doesn't exist, setting the owner.
     *
     * @param roomId The unique ID for the room.
     * @param roomName The display name for the room.
     * @param ownerChatId The chat ID of the user creating the room (the owner).
     * @return true if the room was created successfully or already existed, false on error.
     */
    public boolean createRoomIfNotExists(String roomId, String roomName, String ownerChatId) {
        if (roomId == null || roomId.trim().isEmpty() ||
            roomName == null || roomName.trim().isEmpty() ||
            ownerChatId == null || ownerChatId.trim().isEmpty()) {
            log.warn("Attempted to create room with null or empty ID, name, or owner.");
            return false;
        }

        // Use INSERT IGNORE to avoid errors if the room already exists
        String sql = "INSERT IGNORE INTO rooms (room_id, name, owner) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, roomId);
            pstmt.setString(2, roomName);
            pstmt.setString(3, ownerChatId);
            int affectedRows = pstmt.executeUpdate();

            if (affectedRows > 0) {
                log.info("Room '{}' with name '{}' and owner '{}' created successfully in DB.", roomId, roomName, ownerChatId);
            } else {
                // This means the room likely already existed due to INSERT IGNORE
                log.info("Room '{}' already exists or insert was ignored. Checking/updating name if needed.", roomId);
                // Update room name if it already exists but name has changed (owner remains the original one)
                updateRoomNameIfNeeded(roomId, roomName);
            }
            return true; // Return true if created or already exists

        } catch (SQLException e) {
             // Check for foreign key constraint violation (e.g., owner user doesn't exist)
            if ("23000".equals(e.getSQLState()) && e.getMessage().contains("FOREIGN KEY (`owner`)")) {
                 log.warn("Failed to create room '{}': Owner user '{}' does not exist.", roomId, ownerChatId);
            } else {
                log.error("SQL error while creating room '{}' with name '{}' and owner '{}': {}", roomId, roomName, ownerChatId, e.getMessage(), e);
            }
            return false;
        } catch (Exception e) {
            log.error("Unexpected error while creating room '{}' with name '{}' and owner '{}': {}", roomId, roomName, ownerChatId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Updates a room's name if it already exists and the name is different.
     * Does not change the owner.
     *
     * @param roomId The room ID
     * @param roomName The new room name
     * @return true if name was updated or already matches, false on error
     */
    private boolean updateRoomNameIfNeeded(String roomId, String roomName) {
        String checkSql = "SELECT name FROM rooms WHERE room_id = ?";
        String updateSql = "UPDATE rooms SET name = ? WHERE room_id = ?";

        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {

            checkStmt.setString(1, roomId);
            try (ResultSet rs = checkStmt.executeQuery()) {
                if (rs.next()) {
                    String currentName = rs.getString("name");
                    if (!roomName.equals(currentName)) {
                        try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                            updateStmt.setString(1, roomName);
                            updateStmt.setString(2, roomId);
                            updateStmt.executeUpdate();
                            log.info("Updated name for room '{}' from '{}' to '{}'", roomId, currentName, roomName);
                        }
                    }
                } else {
                     log.warn("Cannot update name for non-existent room '{}'", roomId);
                     return false; // Room doesn't exist
                }
            }
            return true;
        } catch (SQLException e) {
            log.error("SQL error checking/updating room name for '{}': {}", roomId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Adds a participant to a room in the database.
     * Does not check for ownership, intended for initial room creation or system actions.
     *
     * @param roomId The ID of the room.
     * @param chatid The ID of the participant to add.
     * @return true if the participant was added successfully or already existed, false on error.
     */
    public boolean addParticipantToRoom(String roomId, String chatid) {
        if (roomId == null || roomId.trim().isEmpty() || chatid == null || chatid.trim().isEmpty()) {
            log.warn("Attempted to add participant with invalid input (roomId: {}, chatid: {})", roomId, chatid);
            return false;
        }

        // Use INSERT IGNORE to avoid errors if the participant is already in the room
        String sql = "INSERT IGNORE INTO room_participants (room_id, chatid) VALUES (?, ?)";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, roomId);
            pstmt.setString(2, chatid);
            int affectedRows = pstmt.executeUpdate();

             if (affectedRows > 0) {
                log.info("Participant '{}' added to room '{}' in DB.", chatid, roomId);
            } else {
                log.info("Participant '{}' already in room '{}' or insert was ignored.", chatid, roomId);
            }
            return true; // Return true if added or already exists

        } catch (SQLException e) {
             // Check for foreign key constraint violation (e.g., room or user doesn't exist)
            if ("23000".equals(e.getSQLState())) {
                 log.warn("Failed to add participant '{}' to room '{}': Room or User does not exist.", chatid, roomId);
            } else {
                log.error("SQL error adding participant '{}' to room '{}': {}", chatid, roomId, e.getMessage(), e);
            }
            return false;
        } catch (Exception e) {
            log.error("Unexpected error adding participant '{}' to room '{}': {}", chatid, roomId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Checks if a specific user is the owner of a specific room.
     *
     * @param roomId The ID of the room.
     * @param chatid The ID of the user to check.
     * @return true if the user is the owner, false otherwise or on error.
     */
    public boolean isRoomOwner(String roomId, String chatid) {
        if (roomId == null || chatid == null) return false;
        String sql = "SELECT 1 FROM rooms WHERE room_id = ? AND owner = ? LIMIT 1";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, roomId);
            pstmt.setString(2, chatid);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next(); // Returns true if a row is found
            }
        } catch (SQLException e) {
            log.error("SQL error checking ownership for user '{}' in room '{}': {}", chatid, roomId, e.getMessage(), e);
            return false;
        } catch (Exception e) {
           log.error("Unexpected error checking ownership for user '{}' in room '{}': {}", chatid, roomId, e.getMessage(), e);
           return false;
       }
    }

    /**
     * Adds a user to a room, but only if the requesting user is the owner.
     *
     * @param roomId The ID of the room.
     * @param userToAdd The chat ID of the user to add.
     * @param requestingUser The chat ID of the user making the request (must be the owner).
     * @return true if the user was added successfully, false if the requester is not the owner or on error.
     */
    public boolean addUserToRoomByOwner(String roomId, String userToAdd, String requestingUser) {
        if (!isRoomOwner(roomId, requestingUser)) {
            log.warn("Permission denied: User '{}' attempted to add user '{}' to room '{}' but is not the owner.",
                     requestingUser, userToAdd, roomId);
            return false;
        }
        // Check if user to add exists (optional, but good practice)
        UserDAO userDAO = new UserDAO(); // Consider injecting this dependency
        if (!userDAO.userExists(userToAdd)) {
             log.warn("Cannot add non-existent user '{}' to room '{}'.", userToAdd, roomId);
             return false;
        }
        return addParticipantToRoom(roomId, userToAdd); // Use the existing method to add
    }

    /**
     * Removes a user from a room, but only if the requesting user is the owner.
     * The owner cannot remove themselves using this method.
     *
     * @param roomId The ID of the room.
     * @param userToRemove The chat ID of the user to remove.
     * @param requestingUser The chat ID of the user making the request (must be the owner).
     * @return true if the user was removed successfully, false if the requester is not the owner,
     *         trying to remove self, or on error.
     */
    public boolean removeUserFromRoomByOwner(String roomId, String userToRemove, String requestingUser) {
        if (!isRoomOwner(roomId, requestingUser)) {
            log.warn("Permission denied: User '{}' attempted to remove user '{}' from room '{}' but is not the owner.",
                     requestingUser, userToRemove, roomId);
            return false;
        }
        if (requestingUser.equals(userToRemove)) {
             log.warn("Owner '{}' cannot remove themselves from room '{}' using this method.", requestingUser, roomId);
             return false; // Owner cannot remove self this way, must delete room
        }

        String sql = "DELETE FROM room_participants WHERE room_id = ? AND chatid = ?";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, roomId);
            pstmt.setString(2, userToRemove);
            int affectedRows = pstmt.executeUpdate();

            if (affectedRows > 0) {
                log.info("User '{}' removed from room '{}' by owner '{}'.", userToRemove, roomId, requestingUser);
                return true;
            } else {
                log.warn("User '{}' not found in room '{}' or already removed.", userToRemove, roomId);
                return false; // User wasn't in the room or deletion failed silently
            }
        } catch (SQLException e) {
            log.error("SQL error removing user '{}' from room '{}' by owner '{}': {}",
                      userToRemove, roomId, requestingUser, e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("Unexpected error removing user '{}' from room '{}' by owner '{}': {}",
                      userToRemove, roomId, requestingUser, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Deletes a room, but only if the requesting user is the owner.
     * This will cascade delete participants and messages due to FOREIGN KEY constraints.
     *
     * @param roomId The ID of the room to delete.
     * @param requestingUser The chat ID of the user making the request (must be the owner).
     * @return true if the room was deleted successfully, false otherwise.
     */
    public boolean deleteRoom(String roomId, String requestingUser) {
        if (!isRoomOwner(roomId, requestingUser)) {
            log.warn("Permission denied: User '{}' attempted to delete room '{}' but is not the owner.",
                     requestingUser, roomId);
            return false;
        }

        String sql = "DELETE FROM rooms WHERE room_id = ? AND owner = ?"; // Double check owner
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, roomId);
            pstmt.setString(2, requestingUser); // Ensure owner is correct
            int affectedRows = pstmt.executeUpdate();

            if (affectedRows > 0) {
                log.info("Room '{}' deleted successfully by owner '{}'.", roomId, requestingUser);
                return true;
            } else {
                // Should not happen if isRoomOwner passed, but good to log
                log.warn("Failed to delete room '{}'. It might not exist or owner mismatch despite check.", roomId);
                return false;
            }
        } catch (SQLException e) {
            log.error("SQL error deleting room '{}' by owner '{}': {}", roomId, requestingUser, e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("Unexpected error deleting room '{}' by owner '{}': {}", roomId, requestingUser, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Renames a room, but only if the requesting user is the owner.
     *
     * @param roomId The ID of the room to rename.
     * @param newName The new name for the room.
     * @param requestingUser The chat ID of the user making the request (must be the owner).
     * @return true if the room was renamed successfully, false otherwise.
     */
    public boolean renameRoom(String roomId, String newName, String requestingUser) {
         if (newName == null || newName.trim().isEmpty()) {
            log.warn("Attempted to rename room '{}' to an empty name.", roomId);
            return false;
        }
        if (!isRoomOwner(roomId, requestingUser)) {
            log.warn("Permission denied: User '{}' attempted to rename room '{}' but is not the owner.",
                     requestingUser, roomId);
            return false;
        }

        String sql = "UPDATE rooms SET name = ? WHERE room_id = ? AND owner = ?"; // Double check owner
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newName.trim());
            pstmt.setString(2, roomId);
            pstmt.setString(3, requestingUser); // Ensure owner is correct
            int affectedRows = pstmt.executeUpdate();

            if (affectedRows > 0) {
                log.info("Room '{}' renamed to '{}' by owner '{}'.", roomId, newName.trim(), requestingUser);
                return true;
            } else {
                 // Should not happen if isRoomOwner passed, but good to log
                log.warn("Failed to rename room '{}'. It might not exist or owner mismatch despite check.", roomId);
                return false;
            }
        } catch (SQLException e) {
            log.error("SQL error renaming room '{}' by owner '{}': {}", roomId, requestingUser, e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("Unexpected error renaming room '{}' by owner '{}': {}", roomId, requestingUser, e.getMessage(), e);
            return false;
        }
    }


    // --- Existing methods below ---

    /**
     * Checks if a specific user is a participant in a specific room in the database.
     *
     * @param roomId The ID of the room.
     * @param chatid The ID of the user.
     * @return true if the user is a participant, false otherwise or on error.
     */
    public boolean isUserInRoom(String roomId, String chatid) {
         if (roomId == null || chatid == null) return false;
         String sql = "SELECT 1 FROM room_participants WHERE room_id = ? AND chatid = ? LIMIT 1";
         try (Connection conn = DatabaseConnectionManager.getConnection();
              PreparedStatement pstmt = conn.prepareStatement(sql)) {
             pstmt.setString(1, roomId);
             pstmt.setString(2, chatid);
             try (ResultSet rs = pstmt.executeQuery()) {
                 return rs.next(); // Returns true if a row is found
             }
         } catch (SQLException e) {
             log.error("SQL error checking participation for user '{}' in room '{}': {}", chatid, roomId, e.getMessage(), e);
             return false;
         } catch (Exception e) {
            log.error("Unexpected error checking participation for user '{}' in room '{}': {}", chatid, roomId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Retrieves a list of room IDs that a specific user participates in.
     *
     * @param chatid The ID of the user.
     * @return A List of room IDs, or an empty list if none are found or on error.
     */
    public List<String> getRoomsByUser(String chatid) {
        if (chatid == null) return Collections.emptyList();
        List<String> roomIds = new ArrayList<>();
        String sql = "SELECT room_id FROM room_participants WHERE chatid = ?";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, chatid);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    roomIds.add(rs.getString("room_id"));
                }
            }
        } catch (SQLException e) {
            log.error("SQL error retrieving rooms for user '{}': {}", chatid, e.getMessage(), e);
            // Return empty list on error to avoid breaking callers
        } catch (Exception e) {
            log.error("Unexpected error retrieving rooms for user '{}': {}", chatid, e.getMessage(), e);
        }
        return roomIds;
    }

    /**
     * Retrieves all participants for a given room from the database.
     *
     * @param roomId The ID of the room.
     * @return A Set of participant chat IDs, or an empty set if none are found or on error.
     */
    public Set<String> getParticipantsInRoom(String roomId) {
        if (roomId == null) return Collections.emptySet();
        Set<String> participants = new HashSet<>();
        String sql = "SELECT chatid FROM room_participants WHERE room_id = ?";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, roomId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    participants.add(rs.getString("chatid"));
                }
            }
        } catch (SQLException e) {
            log.error("SQL error retrieving participants for room '{}': {}", roomId, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error retrieving participants for room '{}': {}", roomId, e.getMessage(), e);
        }
        return participants;
    }

    /**
     * Retrieves a room name from its ID
     *
     * @param roomId The ID of the room
     * @return The name of the room, or the room ID if not found or on error
     */
    public String getRoomName(String roomId) {
        if (roomId == null) return null;

        String sql = "SELECT name FROM rooms WHERE room_id = ?";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, roomId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("name");
                } else {
                    log.warn("Room name not found for room ID '{}'", roomId);
                    return roomId; // Return ID as fallback if not found
                }
            }
        } catch (SQLException e) {
            log.error("SQL error retrieving name for room '{}': {}", roomId, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error retrieving name for room '{}': {}", roomId, e.getMessage(), e);
        }

        // Return the room ID as fallback on error
        return roomId;
    }

    /**
     * Checks if a room with the given ID exists in the database.
     *
     * @param roomId The room ID to check.
     * @return true if the room exists, false otherwise or on error.
     */
    public boolean roomExists(String roomId) {
        if (roomId == null) return false;
        String sql = "SELECT 1 FROM rooms WHERE room_id = ? LIMIT 1";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, roomId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("SQL error checking existence for room '{}': {}", roomId, e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("Unexpected error checking existence for room '{}': {}", roomId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Retrieves a list of rooms a specific user is a participant in, along with the members of each room.
     *
     * @param chatid The ID of the user.
     * @return A List of Maps, where each map represents a room and contains 'id', 'name', and 'members' (a List of chatids). Returns an empty list if the user is in no rooms or on error.
     */
    public List<Map<String, Object>> getRoomsAndMembersByUser(String chatid) {
        List<Map<String, Object>> userRooms = new ArrayList<>();
        if (chatid == null || chatid.trim().isEmpty()) {
            log.warn("Attempted to get rooms for null or empty chatid.");
            return userRooms; // Return empty list
        }

        // SQL query to get rooms the user is in and their names
        // We also join with room_participants again to fetch all members of those rooms
        String sql = "SELECT r.room_id, r.name, rp_all.chatid AS member_id " +
                     "FROM rooms r " +
                     "JOIN room_participants rp_user ON r.room_id = rp_user.room_id " +
                     "JOIN room_participants rp_all ON r.room_id = rp_all.room_id " +
                     "WHERE rp_user.chatid = ? " +
                     "ORDER BY r.room_id, rp_all.chatid"; // Order to easily group members by room

        Map<String, Map<String, Object>> roomDataMap = new HashMap<>();

        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, chatid);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String roomId = rs.getString("room_id");
                    String roomName = rs.getString("name");
                    String memberId = rs.getString("member_id");

                    // Check if we've already started processing this room
                    Map<String, Object> roomInfo = roomDataMap.get(roomId);
                    if (roomInfo == null) {
                        // First time seeing this room for the user
                        roomInfo = new HashMap<>();
                        roomInfo.put("id", roomId);
                        roomInfo.put("name", roomName);
                        roomInfo.put("members", new ArrayList<String>());
                        roomDataMap.put(roomId, roomInfo);
                        userRooms.add(roomInfo); // Add to the final list
                    }

                    // Add the current member to the list for this room
                    @SuppressWarnings("unchecked") // Safe cast due to initialization
                    List<String> members = (List<String>) roomInfo.get("members");
                    if (memberId != null && !members.contains(memberId)) { // Avoid duplicates if query returns multiple rows for same member
                         members.add(memberId);
                    }
                }
            }
            log.info("Retrieved {} rooms and their members for user '{}'.", userRooms.size(), chatid);

        } catch (SQLException e) {
            log.error("SQL error retrieving rooms and members for user '{}': {}", chatid, e.getMessage(), e);
            return Collections.emptyList(); // Return empty list on error
        } catch (Exception e) {
            log.error("Unexpected error retrieving rooms and members for user '{}': {}", chatid, e.getMessage(), e);
            return Collections.emptyList(); // Return empty list on error
        }

        return userRooms;
    }
}
