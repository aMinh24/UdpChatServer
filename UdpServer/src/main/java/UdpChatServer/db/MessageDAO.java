package UdpChatServer.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import UdpChatServer.model.Message;


/**
 * Data Access Object for Message related operations.
 */
public class MessageDAO {

    private static final Logger log = LoggerFactory.getLogger(MessageDAO.class);

    /**
     * Saves a message to the database.
     *
     * @param message The Message object containing roomId, senderChatid, and content.
     *                The timestamp from the object will be used if provided, otherwise DB default.
     * @return true if the message was saved successfully, false otherwise.
     */
    public boolean saveMessage(Message message) {
        if (message == null || message.getRoomId() == null || message.getSenderChatid() == null || message.getContent() == null) {
            log.warn("Attempted to save invalid message object: {}", message);
            return false;
        }

        String sql = "INSERT INTO messages (room_id, sender_chatid, content, timestamp) VALUES (?, ?, ?, ?)";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, message.getRoomId());
            pstmt.setString(2, message.getSenderChatid());
            pstmt.setString(3, message.getContent());
            // Use the timestamp from the message object if available, otherwise let DB handle default
            pstmt.setTimestamp(4, message.getTimestamp() != null ? message.getTimestamp() : new Timestamp(System.currentTimeMillis()));


            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                log.info("Message saved successfully from '{}' in room '{}'.", message.getSenderChatid(), message.getRoomId());
                return true;
            } else {
                log.warn("Failed to save message from '{}' in room '{}'. No rows affected.", message.getSenderChatid(), message.getRoomId());
                return false;
            }
        } catch (SQLException e) {
             // Check for foreign key constraint violation (e.g., room or sender doesn't exist)
            if ("23000".equals(e.getSQLState())) {
                 log.warn("Failed to save message: Room '{}' or Sender '{}' does not exist.", message.getRoomId(), message.getSenderChatid());
            } else {
                log.error("SQL error saving message from '{}' in room '{}': {}", message.getSenderChatid(), message.getRoomId(), e.getMessage(), e);
            }
            return false;
        } catch (Exception e) {
            log.error("Unexpected error saving message from '{}' in room '{}': {}", message.getSenderChatid(), message.getRoomId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Retrieves a list of messages for a specific room, ordered by timestamp.
     *
     * @param roomId The ID of the room.
     * @param limit  The maximum number of messages to retrieve (e.g., for recent history). Use 0 or negative for no limit.
     * @return A List of Message objects, or an empty list if none are found or on error.
     */
    public List<Message> getMessagesByRoom(String roomId, int limit) {
        if (roomId == null) return Collections.emptyList();

        List<Message> messages = new ArrayList<>();
        // Order by timestamp ascending (older messages first)
        String sql = "SELECT message_id, room_id, sender_chatid, content, timestamp FROM messages WHERE room_id = ? ORDER BY timestamp ASC";
        if (limit > 0) {
            // If a limit is provided, adjust the query (syntax might vary slightly by DB)
            // For retrieving the *latest* N messages, you'd ORDER BY timestamp DESC LIMIT N, then reverse the list in Java.
            // This example gets the *oldest* N messages. Adjust if you need latest.
             sql = "SELECT message_id, room_id, sender_chatid, content, timestamp FROM messages WHERE room_id = ? ORDER BY timestamp ASC LIMIT ?";
        }


        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, roomId);
             if (limit > 0) {
                pstmt.setInt(2, limit);
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Message message = new Message(
                            rs.getLong("message_id"), // Autoboxing handles long -> Long
                            rs.getString("room_id"),
                            rs.getString("sender_chatid"),
                            rs.getString("content"),
                            rs.getTimestamp("timestamp")
                    );
                    messages.add(message);
                }
            }
        } catch (SQLException e) {
            log.error("SQL error retrieving messages for room '{}': {}", roomId, e.getMessage(), e);
            // Return empty list on error
        } catch (Exception e) {
            log.error("Unexpected error retrieving messages for room '{}': {}", roomId, e.getMessage(), e);
        }
        return messages;
    }

     /**
     * Retrieves all messages for a specific room, ordered by timestamp.
     * Convenience method calling getMessagesByRoom with no limit.
     *
     * @param roomId The ID of the room.
     * @return A List of Message objects, or an empty list if none are found or on error.
     */
    public List<Message> getAllMessagesByRoom(String roomId) {
        return getMessagesByRoom(roomId, 0); // 0 or negative means no limit
    }

    /**
     * Lấy tất cả tin nhắn của một room từ một thời điểm cụ thể đến hiện tại.
     *
     * @param roomId The ID of the room.
     * @param fromTime Timestamp bắt đầu lấy tin nhắn.
     * @return Danh sách tin nhắn, hoặc danh sách rỗng nếu không tìm thấy hoặc có lỗi.
     */
    public List<Message> getMessagesFromTime(String roomId, Timestamp fromTime) {
        if (roomId == null || fromTime == null) return Collections.emptyList();

        List<Message> messages = new ArrayList<>();
        String sql = "SELECT message_id, room_id, sender_chatid, content, timestamp " +
                    "FROM messages " +
                    "WHERE room_id = ? AND timestamp >= ? " +
                    "ORDER BY timestamp ASC";

        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, roomId);
            pstmt.setTimestamp(2, fromTime);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Message message = new Message(
                            rs.getLong("message_id"), // Autoboxing handles long -> Long
                            rs.getString("room_id"),
                            rs.getString("sender_chatid"),
                            rs.getString("content"),
                        rs.getTimestamp("timestamp")
                    );
                    messages.add(message);
                }
            }
        } catch (SQLException e) {
            log.error("SQL error lấy tin nhắn từ thời điểm {} cho room '{}': {}", 
                      fromTime, roomId, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Lỗi không mong đợi khi lấy tin nhắn từ thời điểm {} cho room '{}': {}", 
                      fromTime, roomId, e.getMessage(), e);
        }
        return messages;
    }
}
