package UdpChatServer.handler.file;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentSkipListMap;

import com.google.gson.JsonObject;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.model.Constants;

public class FileInitHandler extends SendFileHandler {
    public FileInitHandler(UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket) {
        super(userDAO, roomDAO, fileDAO, socket);
    } 

    public void handleSendInit(JsonObject jsonPacket, InetAddress clientAddress, int clientPort) {
        try {
            JsonObject dataJson = jsonPacket.getAsJsonObject(Constants.KEY_DATA);
            String sender = dataJson.get("client_name").getAsString();
            String receiver = dataJson.get("recipient").getAsString();
            String filename = dataJson.get("file_name").getAsString();
            long fileSize = dataJson.get("file_size").getAsLong();
            int totalPackets = dataJson.get("total_packets").getAsInt();

            String fileIdentifier = sender + "_" + receiver + "_" + filename;

            // Check if the file is already being received
            if (fileSize <= 0 || totalPackets <= 0) {
                jsonPacket.addProperty(Constants.KEY_STATUS, Constants.STATUS_ERROR);
                sendPacket(jsonPacket, clientAddress, clientPort);
                return;
            }

            incomingFileChunks.put(fileIdentifier, new ConcurrentSkipListMap<>()); // Prepare to receive chunks

            System.out.println("Receiving file '" + filename + "' from " + sender + " for " + receiver + " (" + fileSize + " bytes, " + totalPackets + " packets)");
            
            // Send an ACK back to the sender
            JsonObject responJson = jsonPacket;
            responJson.addProperty(Constants.KEY_STATUS, Constants.STATUS_SUCCESS);
            sendPacket(responJson, clientAddress, clientPort);
        } catch (NumberFormatException e) {
            JsonObject responJson = jsonPacket;
            responJson.addProperty(Constants.KEY_STATUS, Constants.STATUS_ERROR);
            sendPacket(jsonPacket, clientAddress, clientPort);
        } catch (Exception e) {
            JsonObject responJson = jsonPacket;
            responJson.addProperty(Constants.KEY_STATUS, Constants.STATUS_ERROR);
            sendPacket(jsonPacket, clientAddress, clientPort);
        }
    }
}
