package UdpChatServer.model;

import java.net.InetAddress;

/**
 * Holds session information for a connected client.
 */
public class SessionInfo {
    private final InetAddress ipAddress;
    private final int port;
    private final String key;
    private final String chatid;
    private long lastActivityTime;

    public SessionInfo(InetAddress ipAddress, int port, String key, String chatid) {
        this.ipAddress = ipAddress;
        this.port = port;
        this.key = key;
        this.chatid = chatid;
        this.lastActivityTime = System.currentTimeMillis();
    }

    public InetAddress getIpAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }

    public String getKey() {
        return key;
    }

    public String getChatid() {
        return chatid;
    }

    public long getLastActivityTime() {
        return lastActivityTime;
    }

    public void updateLastActivityTime() {
        this.lastActivityTime = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("Session[chatid=%s, address=%s:%d]", 
            chatid, ipAddress.getHostAddress(), port);
    }
}
