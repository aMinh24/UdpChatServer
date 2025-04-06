package UdpChatServer.model;

import java.sql.Timestamp;

/**
 * Represents a file in the chat system.
 */
public class File {
    private final Long fileId;
    private final String roomId;
    private final String senderChatid;
    private final String receiverChatid;
    private final String filename;
    private final Timestamp timestamp;

    public File(Long fileId, String roomId, String senderChatid, String receiverChatid, String fileName, Timestamp timestamp) {
        this.fileId = fileId;
        this.roomId = roomId;
        this.senderChatid = senderChatid;
        this.receiverChatid = receiverChatid;
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

    public String getReceiverChatid() {
        return receiverChatid;
    }

    public String getFileName() {
        return filename;
    }

    public Timestamp getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("file[id=%d, room=%s, sender=%s, receiver=%s, time=%s, fileName=%s]",
                fileId, roomId, senderChatid, receiverChatid, timestamp, filename);
    }
}
