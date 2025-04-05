package UdpChatServer.handler.file;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Base64;
import java.util.concurrent.ConcurrentSkipListMap;

import com.google.gson.JsonObject;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.model.Constants;

public class FileDataHandler extends SendFileHandler {
    public FileDataHandler(UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket) {
        super(userDAO, roomDAO, fileDAO, socket);
    }

    public void handleSendData(JsonObject jsonPacket, InetAddress clientAddress, int clientPort) {
        try {
            JsonObject dataJson = jsonPacket.getAsJsonObject(Constants.KEY_DATA);
            String sender = dataJson.get("client_name").getAsString();
            String receiver = dataJson.get("recipient").getAsString();
            String filename = dataJson.get("file_name").getAsString();
            int sequenceNumber = dataJson.get("sequence_number").getAsInt();
            int chunkSize = dataJson.get("chunk_size").getAsInt();
            String base64Data = dataJson.get("file_data").getAsString();
            byte[] dataChunk = Base64.getDecoder().decode(base64Data);

            String fileIdentifier = sender + "_" + receiver + "_" + filename;

            ConcurrentSkipListMap<Integer, byte[]> chunks = incomingFileChunks.get(fileIdentifier);
            if (chunks != null) {
                chunks.put(sequenceNumber, dataChunk);
                System.out.println("Received chunk " + sequenceNumber + " for " + fileIdentifier + 
                    " (size: " + dataChunk.length + " bytes)");
            } else {
                System.err.println("Received data chunk for unknown/uninitialized file transfer: " + fileIdentifier);
            }

            if (chunks != null) {
                chunks.put(sequenceNumber, dataChunk);
            } else {
                System.err.println("Received data chunk for unknown/uninitialized file transfer: " + fileIdentifier);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println(e.getMessage());
        } catch (Exception e) {
            System.err.println("Error in handleSendData: " + e.getMessage());
        }
    }
}
