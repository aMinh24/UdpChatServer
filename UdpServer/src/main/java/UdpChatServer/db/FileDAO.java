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

import UdpChatServer.model.File;

/**
 * Data Access Object for File related operations.
 */
public class FileDAO {

    private static final Logger log = LoggerFactory.getLogger(FileDAO.class);

    /**
     * Saves a file record to the database.
     *
     * @param file The File object containing roomId, senderChatid,
     * reciverChatid, and filename.
     * @return true if the file record was saved successfully, false otherwise.
     */
    public boolean saveFile(File file) {
        if (file == null || file.getRoomId() == null || file.getReceiverChatid() == null
                || file.getReceiverChatid() == null || file.getFileName() == null) {
            log.warn("Attempted to save invalid file object: {}", file);
            return false;
        }

        String sql = "INSERT INTO files (room_id, sender_chatid, reciver_chatid, file_name, timestamp) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseConnectionManager.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, file.getRoomId());
            pstmt.setString(2, file.getSenderChatid());
            pstmt.setString(3, file.getReceiverChatid());
            pstmt.setString(4, file.getFileName());
            pstmt.setTimestamp(5, file.getTimestamp() != null ? file.getTimestamp() : new Timestamp(System.currentTimeMillis()));

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                log.info("File record saved successfully from '{}' to '{}' in room '{}'.",
                        file.getSenderChatid(), file.getSenderChatid(), file.getRoomId());
                return true;
            } else {
                log.warn("Failed to save file record. No rows affected.");
                return false;
            }
        } catch (SQLException e) {
            log.error("SQL error saving file record: {}", e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("Unexpected error saving file record: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Retrieves a list of files for a specific room, ordered by timestamp.
     *
     * @param roomId The ID of the room.
     * @return A List of File objects, or an empty list if none are found or on
     * error.
     */
    public List<File> getFilesByRoom(String roomId) {
        if (roomId == null) {
            return Collections.emptyList();
        }

        List<File> files = new ArrayList<>();
        String sql = "SELECT file_id, room_id, sender_chatid, reciver_chatid, file_name, timestamp "
                + "FROM files WHERE room_id = ? ORDER BY timestamp ASC";

        try (Connection conn = DatabaseConnectionManager.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, roomId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    File file = new File(
                            rs.getLong("file_id"),
                            rs.getString("room_id"),
                            rs.getString("sender_chatid"),
                            rs.getString("reciver_chatid"),
                            rs.getString("file_name"),
                            rs.getTimestamp("timestamp")
                    );
                    files.add(file);
                }
            }
        } catch (SQLException e) {
            log.error("SQL error retrieving files for room '{}': {}", roomId, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error retrieving files for room '{}': {}", roomId, e.getMessage(), e);
        }
        return files;
    }

    /**
     * Retrieves a list of files sent by a specific sender in a room.
     *
     * @param roomId The ID of the room.
     * @param senderChatid The chatid of the sender.
     * @return A List of File objects, or an empty list if none are found or on
     * error.
     */
    public List<File> getFilesBySender(String roomId, String senderChatid) {
        if (roomId == null || senderChatid == null) {
            return Collections.emptyList();
        }

        List<File> files = new ArrayList<>();
        String sql = "SELECT file_id, room_id, sender_chatid, reciver_chatid, file_name, timestamp "
                + "FROM files WHERE room_id = ? AND sender_chatid = ? ORDER BY timestamp ASC";

        try (Connection conn = DatabaseConnectionManager.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, roomId);
            pstmt.setString(2, senderChatid);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    File file = new File(
                            rs.getLong("file_id"),
                            rs.getString("room_id"),
                            rs.getString("sender_chatid"),
                            rs.getString("reciver_chatid"),
                            rs.getString("file_name"),
                            rs.getTimestamp("timestamp")
                    );
                    files.add(file);
                }
            }
        } catch (SQLException e) {
            log.error("SQL error retrieving files for sender '{}' in room '{}': {}",
                    senderChatid, roomId, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error retrieving files for sender '{}' in room '{}': {}",
                    senderChatid, roomId, e.getMessage(), e);
        }
        return files;
    }

    /**
     * Retrieves a list of files received by a specific receiver in a room.
     *
     * @param roomId The ID of the room.
     * @param reciverChatid The chatid of the receiver.
     * @return A List of File objects, or an empty list if none are found or on
     * error.
     */
    public List<File> getFilesByReceiver(String roomId, String reciverChatid) {
        if (roomId == null || reciverChatid == null) {
            return Collections.emptyList();
        }

        List<File> files = new ArrayList<>();
        String sql = "SELECT file_id, room_id, sender_chatid, reciver_chatid, file_name, timestamp "
                + "FROM files WHERE room_id = ? AND reciver_chatid = ? ORDER BY timestamp ASC";

        try (Connection conn = DatabaseConnectionManager.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, roomId);
            pstmt.setString(2, reciverChatid);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    File file = new File(
                            rs.getLong("file_id"),
                            rs.getString("room_id"),
                            rs.getString("sender_chatid"),
                            rs.getString("reciver_chatid"),
                            rs.getString("file_name"),
                            rs.getTimestamp("timestamp")
                    );
                    files.add(file);
                }
            }
        } catch (SQLException e) {
            log.error("SQL error retrieving files for receiver '{}' in room '{}': {}",
                    reciverChatid, roomId, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error retrieving files for receiver '{}' in room '{}': {}",
                    reciverChatid, roomId, e.getMessage(), e);
        }
        return files;
    }
}
