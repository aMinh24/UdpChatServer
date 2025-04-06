package UdpChatServer.handler.file;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.model.Constants;
import UdpChatServer.model.FileMetaData;

public class FileDownloadHandler extends SendFileHandler {

    public FileDownloadHandler(UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket) {
        super(userDAO, roomDAO, fileDAO, socket);
    }

    public void handleDownloadRequest(JsonObject jsonPacket, InetAddress clientAddress, int clientPort) {
        try {
            JsonObject dataJson = jsonPacket.getAsJsonObject(Constants.KEY_DATA);
            String clientName = dataJson.get("client_name").getAsString();
            String requestedFilename = dataJson.get("file_name").getAsString();

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
                    System.err.println("File '" + requestedFilename
                            + "' not found or not readable on server, although listed for " + clientName);
                    JsonObject responseJson = jsonPacket;
                    responseJson.addProperty(Constants.KEY_STATUS, Constants.STATUS_ERROR);
                    responseJson.addProperty(Constants.KEY_MESSAGE, "File not found or unreadable on server.");
                    sendPacket(responseJson, clientAddress, clientPort);
                }
            } else {
                JsonObject responseJson = jsonPacket;
                responseJson.addProperty(Constants.KEY_STATUS, Constants.STATUS_ERROR);
                responseJson.addProperty(Constants.KEY_MESSAGE, "File not found or not assigned to you.");
                System.out.println("File '" + requestedFilename + "' not available for client " + clientName);
                sendPacket(responseJson, clientAddress, clientPort);
            }
        } catch (Exception e) {
            JsonObject responseJson = jsonPacket;
            responseJson.addProperty(Constants.KEY_STATUS, Constants.STATUS_ERROR);
            responseJson.addProperty(Constants.KEY_MESSAGE, "Server error processing request.");
            sendPacket(responseJson, clientAddress, clientPort);
        }
    }

    private void sendFileToClient(FileMetaData meta, Path filePath, InetAddress clientAddress, int clientPort) {
        try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
            long fileSize = meta.getFileSize();
            int totalPackets = (int) Math.ceil((double) fileSize / Constants.DATA_CHUNK_SIZE);

            // 1. Send Meta Packet
            JsonObject responseJson = new JsonObject();
            responseJson.addProperty(Constants.KEY_ACTION, Constants.ACTION_FILE_DOWN);
            JsonObject dataJson = new JsonObject();
            dataJson.addProperty("file_name", meta.getFilename());
            dataJson.addProperty("file_size", fileSize);
            dataJson.addProperty("total_packets", totalPackets);
            responseJson.add(Constants.KEY_DATA, dataJson);

            sendPacket(responseJson, clientAddress, clientPort);
            System.out.println("Sent META for " + meta.getFilename() + " to " + clientAddress + ":" + clientPort);
            Thread.sleep(10); // Small delay

            // 2. Send Data Packets
            byte[] dataBuffer = new byte[Constants.DATA_CHUNK_SIZE];
            int bytesRead;
            int sequenceNumber = 0;

            while ((bytesRead = fis.read(dataBuffer)) != -1) {
                sequenceNumber++;

                String base64Data = Base64.getEncoder().encodeToString(Arrays.copyOf(dataBuffer, bytesRead));
                JsonObject dataPacketJson = new JsonObject();
                dataPacketJson.addProperty(Constants.KEY_ACTION, Constants.ACTION_FILE_DATA);
                JsonObject dataDataJson = new JsonObject();
                dataDataJson.addProperty("file_name", meta.getFilename());
                dataDataJson.addProperty("sequence_number", sequenceNumber);
                dataDataJson.addProperty("chunk_size", bytesRead);
                dataDataJson.addProperty("file_data", base64Data);
                dataPacketJson.add(Constants.KEY_DATA, dataDataJson);

                String jsonHeaderString = dataPacketJson.toString();
                byte[] packetBytes = jsonHeaderString.getBytes(StandardCharsets.UTF_8);

                DatagramPacket dataPacket = new DatagramPacket(packetBytes, packetBytes.length, clientAddress,
                        clientPort);
                socket.send(dataPacket);
                Thread.sleep(1); // Giảm delay xuống 1ms
            }

            // 3. Send Fin Packet
            JsonObject finJson = new JsonObject();
            finJson.addProperty(Constants.KEY_ACTION, Constants.ACTION_DOWN_FIN);
            finJson.addProperty(Constants.KEY_STATUS, Constants.STATUS_SUCCESS);
            JsonObject finDataJson = new JsonObject();
            finDataJson.addProperty("file_name", meta.getFilename());
            finJson.add(Constants.KEY_DATA, finDataJson);
            sendPacket(finJson, clientAddress, clientPort);
            System.out.println("Sent FIN for download of " + meta.getFilename() + " to " + clientAddress + ":" + clientPort);
        } catch (IOException e) {
            JsonObject finJson = new JsonObject();
            finJson.addProperty(Constants.KEY_ACTION, Constants.ACTION_DOWN_FIN);
            finJson.addProperty(Constants.KEY_STATUS, Constants.STATUS_ERROR);
            finJson.addProperty(Constants.KEY_MESSAGE, "Server error during file transmission.");
            System.err.println("Error reading or sending file '" + meta.getFilename() + "': " + e.getMessage());
            sendPacket(finJson, clientAddress, clientPort);
        } catch (InterruptedException e) {
            System.err.println("File sending thread interrupted for " + meta.getFilename());
            Thread.currentThread().interrupt();
        }
    }
}
