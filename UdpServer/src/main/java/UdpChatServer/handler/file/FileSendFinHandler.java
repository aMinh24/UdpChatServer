package UdpChatServer.handler.file;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.JsonObject;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.MessageDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.model.Constants;
import UdpChatServer.model.FileMetaData;
import UdpChatServer.model.FileState;
import UdpChatServer.model.Message;

public class FileSendFinHandler extends FileTransferHandler {
    public FileSendFinHandler(MessageDAO messageDAO, UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO,
            DatagramSocket socket) {
        super(messageDAO, userDAO, roomDAO, fileDAO, socket);
    }

    public void handle(JsonObject jsonPacket, InetAddress clientAddress, int clientPort) {
        try {
            JsonObject dataJson = jsonPacket.getAsJsonObject(Constants.KEY_DATA);
            String senderChatId = dataJson.get("chat_id").getAsString();
            String roomId = dataJson.get("room_id").getAsString();
            String filePath = dataJson.get("file_path").getAsString();

            String fileIdentifier = senderChatId + "_" + roomId + "_" + filePath;

            System.out.println("Received FIN for file '" + filePath + "' from " + senderChatId + " for " + roomId);

            ConcurrentSkipListMap<Integer, byte[]> chunks = incomingFileChunks.remove(fileIdentifier);
            if (chunks == null) {
                System.out.println("No chunks found for file '" + filePath + "' from " + senderChatId + " for " + roomId);
                JsonObject responJsonPacket = createJsonPacket(Constants.ACTION_FILE_SEND_FIN, Constants.STATUS_FAILURE, "No chunks found for file " + filePath, null);
                sendPacket(responJsonPacket, clientAddress, clientPort);
                return;
            }

            // Assemble the file
            Path storagePath = Paths.get(Constants.STORAGE_DIR + "/" + roomId, filePath);
            long totalBytesWritten = 0;
            try (FileOutputStream fos = new FileOutputStream(storagePath.toFile())) {
                for (byte[] chunk : chunks.values()) {
                    fos.write(chunk);
                    totalBytesWritten += chunk.length;
                }
                fos.flush();
                System.out.println("File '" + filePath + "' assembled successfully (" + totalBytesWritten + " bytes) in " + Constants.STORAGE_DIR);

                // Add file metadata for the recipient client
                FileMetaData metaData = new FileMetaData(filePath, totalBytesWritten, filePath);
                filesForClients.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(metaData);

                // Gửi phản hồi thành công
                JsonObject responJsonPacket = createJsonPacket(Constants.ACTION_FILE_SEND_FIN, Constants.STATUS_SUCCESS, "File assembled successfully: " + totalBytesWritten + " bytes", null);
                sendPacket(responJsonPacket, clientAddress, clientPort);

                System.out.println("File '" + filePath + "' is now available for client '"  + roomId + "'");
            } catch (IOException e) {
                System.err.println("Error writing assembled file '" + filePath + "': "
                        + e.getMessage());
                try {
                    Files.deleteIfExists(storagePath);
                } catch (IOException ex) {
                    System.err.println("Error deleting partial file: " + ex.getMessage());
                }
                JsonObject responJsonPacket = createJsonPacket(Constants.ACTION_FILE_SEND_FIN, Constants.STATUS_ERROR, "Error writing assembled file '" + filePath, null);
                sendPacket(responJsonPacket, clientAddress, clientPort);
            }

            // TODO: Fix room here
            Timestamp timestamp = new Timestamp(System.currentTimeMillis());
            FileState fileToSave = new FileState(null, roomId, senderChatId, filePath, timestamp);
            fileDAO.saveFile(fileToSave);
            // TODO: Sửa msg
            String fileMessage = "File '" + filePath + "' from " + senderChatId + " for " + roomId + "type" + fileType;
            Message messageToSave = new Message(null, roomId, senderChatId, fileMessage, timestamp);
            messageDAO.saveMessage(messageToSave);

            // Send notification to the recipient client
            // forwardMessageToRoom(sender, roomId, roomId, filename, timestamp, clientAddress, clientPort);
        } catch (Exception e) {
            System.err.println("Error in handleSendFin: " + e.getMessage());
            // Gửi phản hồi lỗi chung
            JsonObject responJson = jsonPacket;
            responJson.addProperty(Constants.KEY_MESSAGE, "Server error");
            responJson.addProperty(Constants.KEY_STATUS, Constants.STATUS_ERROR);
            sendPacket(responJson, clientAddress, clientPort);
        }
    }

    // private void forwardMessageToRoom(String sender, String receiver, String roomId, String filename,
    //         Timestamp timestamp, InetAddress clientAddress, int clientPort) {
    //     // Get participants from RoomDAO for persistence, or RoomManager for in-memory
    //     // state
    //     // Using RoomDAO might be slightly safer if RoomManager state could be
    //     // inconsistent
    //     Set<String> participants = roomDAO.getParticipantsInRoom(roomId);
    //     // Set<String> participants = roomManager.getUsersInRoom(roomId); // Alternative
    //     // using in-memory state

    //     if (participants.isEmpty()) {
    //         System.out
    //                 .println("No participants found in RoomDAO/RoomManager for room '{}' to forward message." + roomId);
    //         return;
    //     }

    //     System.out.println(
    //             "Forwarding message in room " + roomId + " from " + sender + " to participants: " + participants);

    //     JsonObject notificationJson = new JsonObject();
    //     notificationJson.addProperty(Constants.KEY_ACTION, Constants.ACTION_FILE_NOTI);
    //     notificationJson.addProperty(Constants.KEY_STATUS, Constants.STATUS_SUCCESS);
    //     notificationJson.addProperty(Constants.KEY_MESSAGE, "New file received: " + filename);
    //     JsonObject fileData = new JsonObject();
    //     fileData.addProperty("sender", sender);
    //     fileData.addProperty("recipient", receiver);
    //     fileData.addProperty("room_id", roomId);
    //     fileData.addProperty("file_name", filename);

    //     for (String recipientChatId : participants) {
    //         System.out.println("Recipient in room " + roomId + ": " + recipientChatId);
    //         if (!recipientChatId.equals(sender)) {
    //             SessionInfo recipientSession = sessionManager.getSessionInfo(recipientChatId);
    //             sendPacket(notificationJson, recipientSession.getIpAddress(), recipientSession.getPort());
    //             if (recipientSession != null && recipientSession.getKey() != null) {
    //                 // Initiate S2C flow for this recipient
    //                 System.out.println("Initiating S2C flow to forward message from {} to {} in room {}" + sender
    //                         + recipientChatId + roomId);
    //                 // udpSender.initiateServerToClientFlow( // Changed from requestHandler
    //                 // Constants.ACTION_RECEIVE_MESSAGE,
    //                 // messageJson,
    //                 // recipientSession.getIpAddress(),
    //                 // recipientSession.getPort(),
    //                 // recipientSession.getKey() // Use recipient's session key
    //                 // );

    //             } else {
    //                 System.out.println(
    //                         "Recipient '{}' in room '{}' is offline or key missing. Message saved in DB, not forwarded in real-time."
    //                                 + recipientChatId + roomId);
    //                 // Message is already saved, so offline users will get it later via get_messages
    //             }
    //         }
    //     }
    // }
}
