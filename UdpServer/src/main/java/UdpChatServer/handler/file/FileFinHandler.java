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
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.model.Constants;
import UdpChatServer.model.File;
import UdpChatServer.model.FileMetaData;

public class FileFinHandler extends SendFileHandler {
    public FileFinHandler(UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket) {
        super(userDAO, roomDAO, fileDAO, socket);
    }

    public void handleSendFin(JsonObject jsonPacket, InetAddress clientAddress, int clientPort) {
        try {
            JsonObject dataJson = jsonPacket.getAsJsonObject(Constants.KEY_DATA);
            String sender = dataJson.get("client_name").getAsString();
            String receiver = dataJson.get("recipient").getAsString();
            String filename = dataJson.get("file_name").getAsString();

            String fileIdentifier = sender + "_" + receiver + "_" + filename;

            System.out.println("Received FIN for file '" + filename + "' from " + sender
                    + " for " + receiver);

            ConcurrentSkipListMap<Integer, byte[]> chunks = incomingFileChunks.remove(fileIdentifier);
            if (chunks == null) {
                System.err.println("Received FIN for file '" + filename
                        + "', but no chunks were received or already processed.");
                // Gửi phản hồi lỗi không có chunks
                JsonObject responJson = jsonPacket;
                responJson.addProperty(Constants.KEY_MESSAGE, "No chunks received");
                responJson.addProperty(Constants.KEY_STATUS, Constants.STATUS_ERROR);
                sendPacket(responJson, clientAddress, clientPort);
                return;
            }

            // Assemble the file
            Path filePath = Paths.get(Constants.STORAGE_DIR, filename);
            long totalBytesWritten = 0;
            try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                for (byte[] chunk : chunks.values()) {
                    fos.write(chunk);
                    totalBytesWritten += chunk.length;
                }
                fos.flush();
                System.out.println("File '" + filename + "' assembled successfully ("
                        + totalBytesWritten + " bytes) in " + Constants.STORAGE_DIR);

                // Add file metadata for the recipient client
                FileMetaData metaData = new FileMetaData(filename, totalBytesWritten,
                        filePath.toString());
                filesForClients.computeIfAbsent(receiver, k -> new CopyOnWriteArrayList<>()).add(metaData);

                // Gửi phản hồi thành công
                JsonObject responJson = jsonPacket;
                responJson.addProperty(Constants.KEY_MESSAGE,
                        "File assembled successfully: " + totalBytesWritten + " bytes");
                responJson.addProperty(Constants.KEY_STATUS, Constants.STATUS_SUCCESS);
                sendPacket(responJson, clientAddress, clientPort);

                System.out.println("File '" + filename + "' is now available for client '"
                        + receiver + "'");

            } catch (IOException e) {
                System.err.println("Error writing assembled file '" + filename + "': "
                        + e.getMessage());
                // Clean up potentially partially written file
                try {
                    Files.deleteIfExists(filePath);
                } catch (IOException ex) {
                    System.err.println("Error deleting partial file: " + ex.getMessage());
                }
                // Gửi phản hồi lỗi khi ghi file
                JsonObject responJson = jsonPacket;
                responJson.addProperty(Constants.KEY_MESSAGE, "Error writing file: " + e.getMessage());
                responJson.addProperty(Constants.KEY_STATUS, Constants.STATUS_ERROR);
                sendPacket(responJson, clientAddress, clientPort);
            }

            // TODO: Fix room here
            Timestamp timestamp = new Timestamp(System.currentTimeMillis());
            File fileToSave = new File(null, "room01", sender, receiver, filename, timestamp);
            fileDAO.saveFile(fileToSave); // Save file metadata to DB

        } catch (Exception e) {
            System.err.println("Error in handleSendFin: " + e.getMessage());
            // Gửi phản hồi lỗi chung
            JsonObject responJson = jsonPacket;
            responJson.addProperty(Constants.KEY_MESSAGE, "Server error");
            responJson.addProperty(Constants.KEY_STATUS, Constants.STATUS_ERROR);
            sendPacket(responJson, clientAddress, clientPort);
        }
    }
}
