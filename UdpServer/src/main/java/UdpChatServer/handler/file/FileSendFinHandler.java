package UdpChatServer.handler.file;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.JsonObject;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.MessageDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.manager.ClientSessionManager;
import UdpChatServer.model.Constants;
import UdpChatServer.model.FileMetaData;
import UdpChatServer.model.FileState;
import UdpChatServer.model.Message;
import UdpChatServer.model.SessionInfo;

public class FileSendFinHandler extends FileTransferHandler {
    public FileSendFinHandler(MessageDAO messageDAO, UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket, ClientSessionManager sessionManager) {
        super(sessionManager, messageDAO, userDAO, roomDAO, fileDAO, socket);
    }

    public void handle(JsonObject jsonPacket, InetAddress clientAddress, int clientPort) {
        try {
            JsonObject dataJson = jsonPacket.getAsJsonObject(Constants.KEY_DATA);
            System.out.println("Received FIN packet: " + dataJson.toString());
            String senderChatId = dataJson.get("chat_id").getAsString();
            String roomId = dataJson.get("room_id").getAsString();
            String filePath = dataJson.get("file_path").getAsString();
            String fileIdentifier = senderChatId + "_" + roomId + "_" + filePath;
            filePath = cleanFilePath(filePath);

            System.out.println("Received FIN for file '" + filePath + "' from " + senderChatId + " for " + roomId);
            ConcurrentSkipListMap<Integer, byte[]> chunks = incomingFileChunks.remove(fileIdentifier);
            if (chunks == null) {

                System.out
                        .println("No chunks found for file '" + filePath + "' from " + senderChatId + " for " + roomId);
                JsonObject responJsonPacket = createJsonPacket(Constants.ACTION_FILE_SEND_FIN, Constants.STATUS_FAILURE,
                        "No chunks found for file " + filePath, null);
                sendPacket(responJsonPacket, clientAddress, clientPort);

                return;
            }
           
            // Assemble the file
            Files.createDirectories(Paths.get(Constants.STORAGE_DIR + "/" + roomId));
            Path storagePath = Paths.get(Constants.STORAGE_DIR + "/" + roomId, filePath);
            long totalBytesWritten = 0;
            System.out.println("Storage path:----------------------- " + storagePath.toString());
            try (FileOutputStream fos = new FileOutputStream(storagePath.toFile())) {
                System.out.println(" ------------9-----------------(*( --------------------))");
                for (byte[] chunk : chunks.values()) {
                    fos.write(chunk);
                    totalBytesWritten += chunk.length;
                }
                fos.flush();
                System.out.println("File '" + filePath + "' assembled successfully (" + totalBytesWritten
                        + " bytes) in " + Constants.STORAGE_DIR);

                // Add file metadata for the recipient client
                FileMetaData metaData = new FileMetaData(filePath, totalBytesWritten, filePath);
                filesForClients.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(metaData);

                // Gửi phản hồi thành công

                JsonObject responJsonPacket = createJsonPacket(Constants.ACTION_FILE_SEND_FIN, Constants.STATUS_SUCCESS, "File assembled successfully: " + totalBytesWritten + " bytes", jsonPacket);

                sendPacket(responJsonPacket, clientAddress, clientPort);

                System.out.println("File '" + filePath + "' is now available for client '" + roomId + "'");
            } catch (IOException e) {
                System.err.println("Error writing assembled file '" + filePath + "': "
                        + e.getMessage());
                try {
                    Files.deleteIfExists(storagePath);
                } catch (IOException ex) {
                    System.err.println("Error deleting partial file: " + ex.getMessage());
                }
                JsonObject responJsonPacket = createJsonPacket(Constants.ACTION_FILE_SEND_FIN, Constants.STATUS_ERROR,
                        "Error writing assembled file '" + filePath, null);
                sendPacket(responJsonPacket, clientAddress, clientPort);
            }

            String fileType = fileTypes.remove(fileIdentifier); // Remove after use
            if (fileType == null) {
                fileType = "unknown"; // Default value if not found
            }

            Timestamp timestamp = new Timestamp(System.currentTimeMillis());
            FileState fileToSave = new FileState(null, roomId, senderChatId, filePath, fileType, timestamp);
            fileDAO.saveFile(fileToSave);
            // TODO: Change message format
            String fileMessage = "file_path " + filePath + " chat_id " + senderChatId + " room_id " + roomId + " file_type " + fileType;
            Message messageToSave = new Message(null, roomId, senderChatId, fileMessage, timestamp);
            messageDAO.saveMessage(messageToSave);

            forwardFileNotiToRoom(senderChatId, roomId, fileMessage, timestamp);
        } catch (Exception e) {
            System.err.println("Error in handleSendFin: " + e.getMessage());
            // Gửi phản hồi lỗi chung
            JsonObject responJson = jsonPacket;
            responJson.addProperty(Constants.KEY_MESSAGE, "Server error");
            responJson.addProperty(Constants.KEY_STATUS, Constants.STATUS_ERROR);
            sendPacket(responJson, clientAddress, clientPort);
        }
    }

    private String cleanFilePath(String filePath) {
        // Lấy tên file từ đường dẫn đầy đủ
        String fileName = Paths.get(filePath).getFileName().toString();
        
        // Loại bỏ các ký tự không hợp lệ
        fileName = fileName.replaceAll("[:\\\\/*?|<>]", "_");
        
        return fileName;
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


    private void forwardFileNotiToRoom(String senderChatId, String roomId, String fileMessage, Timestamp timestamp) {
        // Get participants from RoomDAO for persistence, or RoomManager for in-memory state
        // Using RoomDAO might be slightly safer if RoomManager state could be inconsistent
        Set<String> participants = roomDAO.getParticipantsInRoom(roomId);
        // Set<String> participants = roomManager.getUsersInRoom(roomId); // Alternative using in-memory state


        if (participants.isEmpty()) {
             log.warn("No participants found in RoomDAO/RoomManager for room '{}' to forward message.", roomId);
             return;
        }

        JsonObject messageJsonPacket = new JsonObject();
        messageJsonPacket.addProperty(Constants.KEY_ACTION, Constants.ACTION_RECEIVE_MESSAGE);
        JsonObject msgDataJson = new JsonObject();
        msgDataJson.addProperty("chat_id", senderChatId);
        msgDataJson.addProperty("room_id", roomId);
        msgDataJson.addProperty("content", fileMessage);
        messageJsonPacket.add(Constants.KEY_DATA, msgDataJson);

        log.debug("Forwarding message in room '{}' from '{}' to participants: {}", roomId, senderChatId, participants);

        for (String recipientChatId : participants) {
            if (!recipientChatId.equals(senderChatId)) {
                SessionInfo recipientSession = sessionManager.getSessionInfo(recipientChatId);
                System.out.println(recipientSession.getIpAddress());
                if (recipientSession.getKey() != null) {
                    // Initiate S2C flow for this recipient
                    log.debug("Initiating S2C flow to forward message from {} to {} in room {}", senderChatId, recipientChatId, roomId);
                    
                    sendPacket(messageJsonPacket, recipientSession.getIpAddress(), recipientSession.getPort() + 1);
                } else {
                    log.debug("Recipient '{}' in room '{}' is offline or key missing. Message saved in DB, not forwarded in real-time.", recipientChatId, roomId);
                    // Message is already saved, so offline users will get it later via get_messages
                }
            }
        }
    }
}
