package UdpChatServer.handler.file;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentSkipListMap;

import com.google.gson.JsonObject;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.MessageDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.model.Constants;

public class FileSendInitHandler extends FileTransferHandler {
    public FileSendInitHandler(MessageDAO messageDAO, UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO,
            DatagramSocket socket) {
        super(messageDAO, userDAO, roomDAO, fileDAO, socket);
    }

    public void handle(JsonObject jsonPacket, InetAddress clientAddress, int clientPort) {
        try {
            JsonObject dataJson = jsonPacket.getAsJsonObject(Constants.KEY_DATA);
            String senderChatId = dataJson.get("chat_id").getAsString();
            String roomId = dataJson.get("room_id").getAsString();
            String filePath = dataJson.get("file_path").getAsString();
            long fileSize = dataJson.get("file_size").getAsLong();
            String localFileType = dataJson.get("file_type").getAsString();
            int totalPackets = dataJson.get("total_packets").getAsInt();

            String fileIdentifier = senderChatId + "_" + roomId + "_" + filePath;

            // Check if the file is already being received
            if (fileSize <= 0 || totalPackets <= 0) {
                System.err.println("Send file init: Invalid file size or total packets: " + fileSize + ", " + totalPackets);
                JsonObject responJsonPacket = createJsonPacket(Constants.ACTION_FILE_SEND_INIT, Constants.STATUS_FAILURE,
                        "Send file init: Invalid file size or total packets.", null);
                sendPacket(responJsonPacket, clientAddress, clientPort);
                return;
            }

            this.fileType = localFileType;
            incomingFileChunks.put(fileIdentifier, new ConcurrentSkipListMap<>()); // Prepare to receive chunks

            System.out.println("Receiving file '" + filePath + "' from " + senderChatId + " for " + roomId + " ("
                    + fileSize + " bytes, " + totalPackets + " packets)");

            // Send an ACK back to the sender
            JsonObject responJsonPacket = createJsonPacket(Constants.ACTION_FILE_SEND_INIT, Constants.STATUS_SUCCESS,
                    "Send file init: Server accepted.", dataJson);
            sendPacket(responJsonPacket, clientAddress, clientPort);
        } catch (NumberFormatException e) {
            System.err.println("Send file init: Invalid number format: " + e.getMessage());
            JsonObject responJsonPacket = createJsonPacket(Constants.ACTION_FILE_SEND_INIT, Constants.STATUS_ERROR,
                    "Send file init: Invalid number format.", null);
            sendPacket(responJsonPacket, clientAddress, clientPort);
        } catch (Exception e) {
            System.err.println("Send file init: Error in FileSendInitHandler: " + e.getMessage());
            JsonObject responJsonPacket = createJsonPacket(Constants.ACTION_FILE_SEND_INIT, Constants.STATUS_ERROR,
                    "Send file init: Error in FileSendInitHandler.", null);
            sendPacket(responJsonPacket, clientAddress, clientPort);
        }
    }
}
