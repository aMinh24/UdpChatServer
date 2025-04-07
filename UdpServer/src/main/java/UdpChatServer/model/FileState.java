package UdpChatServer.model;

import java.sql.Timestamp;

/**
 * Represents a file in the chat system.
 */
public class FileState {
    private final Long fileId;
    private final String roomId;
    private final String senderChatid;
    private final String filename;
    private final Timestamp timestamp;

    public FileState(Long fileId, String roomId, String senderChatid, String fileName, Timestamp timestamp) {
        this.fileId = fileId;
        this.roomId = roomId;
        this.senderChatid = senderChatid;
        this.filename = fileName;
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

    public String getFileName() {
        return filename;
    }

    public Timestamp getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("file[id=%d, room=%s, sender=%s, time=%s, fileName=%s]",
                fileId, roomId, senderChatid, timestamp, filename);
    }
}
