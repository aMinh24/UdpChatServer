package UdpChatServer.model;

import java.sql.Timestamp;

/**
 * Represents a message in the chat system.
 */
public class Message {
    private final Long messageId;
    private final String roomId;
    private final String senderChatid;
    private final String content;
    private final Timestamp timestamp;

    public Message(Long messageId, String roomId, String senderChatid, String content, Timestamp timestamp) {
        this.messageId = messageId;
        this.roomId = roomId;
        this.senderChatid = senderChatid;
        this.content = content;
        this.timestamp = timestamp;
    }

    public Long getMessageId() {
        return messageId;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getSenderChatid() {
        return senderChatid;
    }

    public String getContent() {
        return content;
    }

    public Timestamp getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("Message[id=%d, room=%s, sender=%s, time=%s, content=%s]",
                messageId, roomId, senderChatid, timestamp, content);
    }
}
