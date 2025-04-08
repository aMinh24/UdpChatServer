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
import UdpChatServer.db.MessageDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.model.Constants;
import UdpChatServer.model.FileMetaData;

/**
 * Handles file transfer operations including uploading, downloading and listing
 * files. Uses UDP packets for data transfer with a simple reliable transfer
 * protocol.
 */
public class FileTransferHandler {

    protected static final Logger log = LoggerFactory.getLogger(FileTransferHandler.class);

    protected final UserDAO userDAO;
    protected final RoomDAO roomDAO;
    protected final FileDAO fileDAO;
    protected final MessageDAO messageDAO;
    protected final DatagramSocket socket;

    protected String fileType;

    // Stores metadata about files available for download for each client
    protected static final ConcurrentMap<String, List<FileMetaData>> filesForClients = new ConcurrentHashMap<>();

    // Temporarily stores incoming file chunks during upload
    protected static final ConcurrentMap<String, ConcurrentSkipListMap<Integer, byte[]>> incomingFileChunks = new ConcurrentHashMap<>();

    public FileTransferHandler(MessageDAO messageDAO, UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket) {
        this.messageDAO = messageDAO;
        this.socket = socket;
        this.userDAO = userDAO;
        this.roomDAO = roomDAO;
        this.fileDAO = fileDAO;
    }

    public JsonObject createJsonPacket(String action, String status, String message, JsonObject dataJson) {
        JsonObject jsonPacket = new JsonObject();
        jsonPacket.addProperty(Constants.KEY_ACTION, action);
        if (status != null) {
            jsonPacket.addProperty(Constants.KEY_STATUS, status);
        }
        if (message != null) {
            jsonPacket.addProperty(Constants.KEY_MESSAGE, message);
        }
        if (dataJson != null) {
            jsonPacket.add(Constants.KEY_DATA, dataJson);
        }
        return jsonPacket;
    }

    public void sendPacket(JsonObject jsonPacket, InetAddress address, int port) {
        try {
            String jsonString = jsonPacket.toString();
            System.out.println(" -------------------json  _-------------"+jsonString);
            byte[] sendData = jsonString.getBytes(StandardCharsets.UTF_8);
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);
            System.out.println("Sent packet to " + address + ":" + port + ": " + jsonString);
            System.out.println(" 0--------------------------------");
            socket.send(sendPacket);
        } catch (IOException e) {
            System.err.println("Error sending packet to " + address + ":" + port + ": " + e.getMessage()); 
        }
    }
}
