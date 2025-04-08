package UdpChatServer.handler.file;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Base64;
import java.util.concurrent.ConcurrentSkipListMap;

import com.google.gson.JsonObject;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.MessageDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.model.Constants;

public class FileSendDataHandler extends FileTransferHandler {
    public FileSendDataHandler(MessageDAO messageDAO, UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket) {
        super(messageDAO, userDAO, roomDAO, fileDAO, socket);
    }

    public void handle(JsonObject jsonPacket, InetAddress clientAddress, int clientPort) {
        try {
            JsonObject dataJson = jsonPacket.getAsJsonObject(Constants.KEY_DATA);
            String senderChatId = dataJson.get("chat_id").getAsString();
            String roomId = dataJson.get("room_id").getAsString();
            String filePath = dataJson.get("file_path").getAsString();
            int sequenceNumber = dataJson.get("sequence_number").getAsInt();
            String base64Data = dataJson.get("file_data").getAsString();
            byte[] dataChunk = Base64.getDecoder().decode(base64Data);
            
            String fileIdentifier = senderChatId + "_" + roomId + "_" + filePath;

            ConcurrentSkipListMap<Integer, byte[]> chunks = incomingFileChunks.get(fileIdentifier);
            if (chunks != null) {
                JsonObject dataRepons= jsonPacket.getAsJsonObject(Constants.KEY_DATA);
                dataRepons.addProperty("sequence_number", sequenceNumber);
                dataRepons.addProperty("chat_id", senderChatId);
                dataRepons.addProperty("room_id", roomId);
                dataRepons.addProperty("file_path", filePath);

                incomingFileChunks.get(fileIdentifier).put(sequenceNumber, dataChunk);
                System.out.println("Received chunk " + sequenceNumber + " for " + fileIdentifier + 
                    " (size: " + dataChunk.length + " bytes)");
                JsonObject responJsonObject = createJsonPacket(Constants.ACTION_FILE_SEND_DATA, Constants.STATUS_SUCCESS, "Send file data: File chunk " + sequenceNumber + "received.",dataRepons);
                sendPacket(responJsonObject, clientAddress, clientPort);
            } else {
                System.err.println("Send file data: Received data chunk for unknown/uninitialized file transfer: " + fileIdentifier);
                JsonObject respJsonObject = createJsonPacket(Constants.ACTION_FILE_SEND_DATA, Constants.STATUS_FAILURE, "Send file data: File transfer not initialized.", dataJson);
                sendPacket(respJsonObject, clientAddress, clientPort);
            }
            System.out.println(" ----------------------------: "+ incomingFileChunks.get(fileIdentifier).size() + "----------------------------: "+ incomingFileChunks.get(fileIdentifier).values());
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println("Send file data: Array index out of bounds: " + e.getMessage());
            JsonObject respJsonObject = createJsonPacket(Constants.ACTION_FILE_SEND_DATA, Constants.STATUS_ERROR, "Send file data: Array index out of bounds.", null);
                sendPacket(respJsonObject, clientAddress, clientPort);
        } catch (Exception e) {
            System.err.println("Send file data: Error in handleSendData: " + e.getMessage());
            JsonObject respJsonObject = createJsonPacket(Constants.ACTION_FILE_SEND_DATA, Constants.STATUS_ERROR, "Send file data: Error in handleSendData.", null);
                sendPacket(respJsonObject, clientAddress, clientPort);
        }
    }
}
