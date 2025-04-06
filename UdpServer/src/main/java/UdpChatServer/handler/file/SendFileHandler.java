package UdpChatServer.handler.file;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.model.FileMetaData;
import UdpChatServer.util.JsonHelper;

/**
 * Handles file transfer operations including uploading, downloading and listing
 * files. Uses UDP packets for data transfer with a simple reliable transfer
 * protocol.
 */
public class SendFileHandler {

    private static final Logger log = LoggerFactory.getLogger(SendFileHandler.class);

    protected static final int BUFFER_SIZE = 1024 * 4;
    protected static final int DATA_CHUNK_SIZE = BUFFER_SIZE - 100;
    protected static final String STORAGE_DIR = "server_storage";
    protected static final String PACKET_DELIMITER = "|:|";

    protected final UserDAO userDAO;
    protected final RoomDAO roomDAO;
    protected final FileDAO fileDAO;
    // private final UdpSender udpSender;
    protected final DatagramSocket socket;

    // Stores metadata about files available for download for each client
    protected static final ConcurrentMap<String, List<FileMetaData>> filesForClients = new ConcurrentHashMap<>();

    // Temporarily stores incoming file chunks during upload
    protected static final ConcurrentMap<String, ConcurrentSkipListMap<Integer, byte[]>> incomingFileChunks = new ConcurrentHashMap<>();


    /**
     * Constructs a new SendFileHandler with required dependencies.
     */
    public SendFileHandler(UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket) {
        this.socket = socket;
        this.userDAO = userDAO;
        this.roomDAO = roomDAO;
        this.fileDAO = fileDAO;
    }

    // public SendFileHandler(ClientSessionManager sessionManager, MessageDAO messageDAO, RoomDAO roomDAO, FileDAO fileDAO, UdpSender udpSender, DatagramSocket socket) {
    //     this.socket = socket;
    //     this.sessionManager = sessionManager;
    //     this.messageDAO = messageDAO;
    //     this.roomDAO = roomDAO;
    //     this.fileDAO = fileDAO;
    //     this.udpSender = udpSender;
    // }
    
    public void test(InetAddress clientAddress, int clientPort) {
        JsonObject data = new JsonObject();
        data.addProperty("hello", "test");

        JsonObject response = JsonHelper.createReply(
                "test", // action
                null, // no status needed
                null, // no message needed 
                data // data object
        );
        System.out.println(response.toString());
        sendPacket(response, clientAddress, clientPort);
    }

    protected void sendPacket(String payload, InetAddress address, int port) {
        try {
            byte[] sendData = payload.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);
            socket.send(sendPacket);
        } catch (IOException e) {
            System.err.println("Error sending packet to " + address + ":" + port + " - " + e.getMessage());
        }
    }

    protected void sendPacket(JsonObject responJsonObject, InetAddress address, int port) {
        try {
            String payloadString = responJsonObject.toString();
            byte[] sendData = payloadString.getBytes(StandardCharsets.UTF_8);
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);
            System.out.println("Sent JSON to " + address + ":" + port + ": " + payloadString);
            socket.send(sendPacket);
        } catch (IOException e) {
            System.err.println("Error sending packet to " + address + ":" + port + " - " + e.getMessage()); 
        }
    }
}
