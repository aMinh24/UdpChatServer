package UdpChatServer.handler.file;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.model.FileMetaData;

public class FileDownloadHandler extends SendFileHandler {
    public FileDownloadHandler(UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket) {
        super(userDAO, roomDAO, fileDAO, socket);
    }

    public void handleDownloadRequest(String payload, InetAddress clientAddress, int clientPort) {
        try {
            String[] parts = payload.split(Pattern.quote(PACKET_DELIMITER));
            if (parts.length != 2) {
                System.err.println("Invalid DOWNLOAD_REQ format: " + payload);
                return;
            }
            String clientName = parts[0];
            String requestedFilename = parts[1];

            System.out.println("Received DOWNLOAD request from " + clientName + " for file: " + requestedFilename);

            List<FileMetaData> clientFiles = filesForClients.get(clientName);
            Optional<FileMetaData> foundFile = Optional.empty();
            if (clientFiles != null) {
                foundFile = clientFiles.stream()
                        .filter(meta -> meta.getFilename().equals(requestedFilename))
                        .findFirst();
            }

            if (foundFile.isPresent()) {
                FileMetaData meta = foundFile.get();
                Path filePath = Paths.get(meta.getServerPath());
                if (Files.exists(filePath) && Files.isReadable(filePath)) {
                    System.out.println("Starting download of '" + requestedFilename + "' for " + clientName);
                    // Start sending the file in a separate thread
                    new Thread(() -> sendFileToClient(meta, filePath, clientAddress, clientPort)).start();
                } else {
                    System.err.println("File '" + requestedFilename + "' not found or not readable on server, although listed for " + clientName);
                    sendPacket("DOWNLOAD_ERR" + PACKET_DELIMITER + "File not found or unreadable on server.", clientAddress, clientPort);
                }
            } else {
                System.out.println("File '" + requestedFilename + "' not available for client " + clientName);
                sendPacket("DOWNLOAD_ERR" + PACKET_DELIMITER + "File not found or not assigned to you.", clientAddress, clientPort);
            }
        } catch (Exception e) {
            System.err.println("Error handling download request: " + e.getMessage());
            sendPacket("DOWNLOAD_ERR" + PACKET_DELIMITER + "Server error processing request.", clientAddress, clientPort);
        }
    }

    private void sendFileToClient(FileMetaData meta, Path filePath,
            InetAddress clientAddress, int clientPort) {
        try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
            long fileSize = meta.getFileSize();
            int totalPackets = (int) Math.ceil((double) fileSize / DATA_CHUNK_SIZE);

            // 1. Send Meta Packet
            String metaPayload = "DOWNLOAD_RESP_META" + PACKET_DELIMITER + meta.getFilename() + PACKET_DELIMITER + fileSize + PACKET_DELIMITER + totalPackets;
            sendPacket(metaPayload, clientAddress, clientPort);
            System.out.println("Sent META for " + meta.getFilename() + " to " + clientAddress + ":" + clientPort);
            Thread.sleep(10); // Small delay

            // 2. Send Data Packets
            byte[] dataBuffer = new byte[DATA_CHUNK_SIZE];
            int bytesRead;
            int sequenceNumber = 0;
            while ((bytesRead = fis.read(dataBuffer)) != -1) {
                sequenceNumber++;
                String dataHeader = "DOWNLOAD_RESP_DATA" + PACKET_DELIMITER + meta.getFilename() + PACKET_DELIMITER + sequenceNumber + PACKET_DELIMITER;
                byte[] headerBytes = dataHeader.getBytes();
                byte[] packetBytes = new byte[headerBytes.length + bytesRead];

                System.arraycopy(headerBytes, 0, packetBytes, 0, headerBytes.length);
                System.arraycopy(dataBuffer, 0, packetBytes, headerBytes.length, bytesRead);

                DatagramPacket dataPacket = new DatagramPacket(packetBytes, packetBytes.length, clientAddress, clientPort);
                socket.send(dataPacket);
                // System.out.println("Sent chunk " + sequenceNumber + "/" + totalPackets + " for " + meta.getFilename()); // Verbose
                Thread.sleep(500); // Small delay to avoid overwhelming the receiver buffer (basic rate control)
            }

            // 3. Send Fin Packet
            String finPayload = "DOWNLOAD_RESP_FIN" + PACKET_DELIMITER + meta.getFilename();
            sendPacket(finPayload, clientAddress, clientPort);
            System.out.println("Sent FIN for download of " + meta.getFilename() + " to " + clientAddress + ":" + clientPort);
        } catch (IOException e) {
            System.err.println("Error reading or sending file '" + meta.getFilename() + "': " + e.getMessage());
            sendPacket("DOWNLOAD_ERR" + PACKET_DELIMITER + "Server error during file transmission.", clientAddress, clientPort);
        } catch (InterruptedException e) {
            System.err.println("File sending thread interrupted for " + meta.getFilename());
            Thread.currentThread().interrupt();
        }
    }
}
