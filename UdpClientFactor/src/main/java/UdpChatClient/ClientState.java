package UdpChatClient;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

public class ClientState {
    private final String serverHost;
    private final int serverPort;
    private final DatagramSocket socket;
    private final InetAddress serverAddress;
    private String sessionKey;
    private String currentChatId;
    private volatile boolean running = true;

    public ClientState(String serverHost, int serverPort) throws SocketException, UnknownHostException {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.socket = new DatagramSocket();
        this.serverAddress = InetAddress.getByName(serverHost);
    }

    // Getters
    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public DatagramSocket getSocket() {
        return socket;
    }

    public InetAddress getServerAddress() {
        return serverAddress;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public String getCurrentChatId() {
        return currentChatId;
    }

    public boolean isRunning() {
        return running;
    }

    // Setters
    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public void setCurrentChatId(String currentChatId) {
        this.currentChatId = currentChatId;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public void closeSocket() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
