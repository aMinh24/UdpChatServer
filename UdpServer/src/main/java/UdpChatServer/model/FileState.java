package UdpChatServer.model;

import java.sql.Timestamp;

/**
 * Represents a file in the chat system.
 */
public class FileState {
    private final Long fileId;
    private final String roomId;
    private final String senderChatid;
    private final String filePath;
    private final String fileType;
    private final Timestamp timestamp;

    public FileState(Long fileId, String roomId, String senderChatid, String filePath, String fileType, Timestamp timestamp) {
        this.fileId = fileId;
        this.roomId = roomId;
        this.senderChatid = senderChatid;
        this.filePath = filePath;
        this.fileType = fileType;
        this.timestamp = timestamp;
    }

    public Long getFileId() {
        return fileId;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getSenderChatid() {
        return senderChatid;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getFileType() {
        return  fileType;
    }

    public Timestamp getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("file[id=%d, room=%s, sender=%s, filePath=%s, fileType=%s, time=%s]",
                fileId, roomId, senderChatid, filePath, fileType, timestamp);
    }
}
