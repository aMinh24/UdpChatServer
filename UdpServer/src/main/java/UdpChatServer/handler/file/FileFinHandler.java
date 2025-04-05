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
import java.util.regex.Pattern;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.model.File;
import UdpChatServer.model.FileMetaData;

public class FileFinHandler extends SendFileHandler {
    public FileFinHandler(UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket) {
        super(userDAO, roomDAO, fileDAO, socket);
    }

    public void handleSendFin(String payload, InetAddress clientAddress, int clientPort) {
        try {
            String[] parts = payload.split(Pattern.quote(PACKET_DELIMITER));
            if (parts.length != 3) {
                System.err.println("Invalid SEND_FIN format: " + payload);
                // Gửi phản hồi lỗi format
                sendPacket("SEND_FIN_RESP" + PACKET_DELIMITER + "ERROR" + PACKET_DELIMITER
                        + "Invalid format", clientAddress, clientPort);
                return;
            }

            String sender = parts[0];
            String receiver = parts[1];
            String filename = parts[2];
            String fileIdentifier = sender + "_" + receiver + "_" + filename;

            System.out.println("Received FIN for file '" + filename + "' from " + sender
                    + " for " + receiver);

            ConcurrentSkipListMap<Integer, byte[]> chunks = incomingFileChunks.remove(fileIdentifier);
            if (chunks == null) {
                System.err.println("Received FIN for file '" + filename
                        + "', but no chunks were received or already processed.");
                // Gửi phản hồi lỗi không có chunks
                sendPacket("SEND_FIN_RESP" + PACKET_DELIMITER + "ERROR" + PACKET_DELIMITER
                        + "No file chunks found", clientAddress, clientPort);
                return;
            }

            // Assemble the file
            Path filePath = Paths.get(STORAGE_DIR, filename);
            long totalBytesWritten = 0;
            try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                for (byte[] chunk : chunks.values()) {
                    fos.write(chunk);
                    totalBytesWritten += chunk.length;
                }
                fos.flush();
                System.out.println("File '" + filename + "' assembled successfully ("
                        + totalBytesWritten + " bytes) in " + STORAGE_DIR);

                // Add file metadata for the recipient client
                FileMetaData metaData = new FileMetaData(filename, totalBytesWritten,
                        filePath.toString());
                filesForClients.computeIfAbsent(receiver, k
                        -> new CopyOnWriteArrayList<>()).add(metaData);

                // Gửi phản hồi thành công
                sendPacket("SEND_FIN_RESP" + PACKET_DELIMITER + "OK" + PACKET_DELIMITER
                        + "File assembled successfully: " + totalBytesWritten + " bytes",
                        clientAddress, clientPort);

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
                sendPacket("SEND_FIN_RESP" + PACKET_DELIMITER + "ERROR" + PACKET_DELIMITER
                        + "Error writing file: " + e.getMessage(), clientAddress, clientPort);
            }

            // TODO: Fix room here
            Timestamp timestamp = new Timestamp(System.currentTimeMillis());
            File fileToSave = new File(null, "room01", sender, receiver, filename, timestamp);
            fileDAO.saveFile(fileToSave); // Save file metadata to DB

        } catch (Exception e) {
            System.err.println("Error in handleSendFin: " + e.getMessage());
            // Gửi phản hồi lỗi chung
            sendPacket("SEND_FIN_RESP" + PACKET_DELIMITER + "ERROR" + PACKET_DELIMITER
                    + "Server error", clientAddress, clientPort);
        }
    }
}
