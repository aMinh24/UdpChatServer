package UdpChatServer.handler.file;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.model.FileMetaData;
import UdpChatServer.util.JsonHelper;

/**
 * Handles file transfer operations including uploading, downloading and listing
 * files. Uses UDP packets for data transfer with a simple reliable transfer
 * protocol.
 */
public class SendFileHandler {

    private static final Logger log = LoggerFactory.getLogger(SendFileHandler.class);

    protected static final int BUFFER_SIZE = 1024 * 4;
    protected static final int DATA_CHUNK_SIZE = BUFFER_SIZE - 100;
    protected static final String STORAGE_DIR = "server_storage";
    protected static final String PACKET_DELIMITER = "|:|";

    protected final UserDAO userDAO;
    protected final RoomDAO roomDAO;
    protected final FileDAO fileDAO;
    // private final UdpSender udpSender;
    protected final DatagramSocket socket;

    // Stores metadata about files available for download for each client
    protected static final ConcurrentMap<String, List<FileMetaData>> filesForClients = new ConcurrentHashMap<>();

    // Temporarily stores incoming file chunks during upload
    protected static final ConcurrentMap<String, ConcurrentSkipListMap<Integer, byte[]>> incomingFileChunks = new ConcurrentHashMap<>();


    /**
     * Constructs a new SendFileHandler with required dependencies.
     */
    public SendFileHandler(UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket) {
        this.socket = socket;
        this.userDAO = userDAO;
        this.roomDAO = roomDAO;
        this.fileDAO = fileDAO;
    }

    // public SendFileHandler(ClientSessionManager sessionManager, MessageDAO messageDAO, RoomDAO roomDAO, FileDAO fileDAO, UdpSender udpSender, DatagramSocket socket) {
    //     this.socket = socket;
    //     this.sessionManager = sessionManager;
    //     this.messageDAO = messageDAO;
    //     this.roomDAO = roomDAO;
    //     this.fileDAO = fileDAO;
    //     this.udpSender = udpSender;
    // }
    
    public void test(InetAddress clientAddress, int clientPort) {
        JsonObject data = new JsonObject();
        data.addProperty("hello", "test");

        JsonObject response = JsonHelper.createReply(
                "test", // action
                null, // no status needed
                null, // no message needed 
                data // data object
        );
        System.out.println(response.toString());
        sendPacket(response, clientAddress, clientPort);
    }

    /**
     * Handles initial file upload request from client. Validates parameters and
     * initializes file transfer session.
     */
    // public void handleSendInit(String payload, InetAddress clientAddress, int clientPort) {
    //     try {
    //         String[] parts = payload.split(Pattern.quote(PACKET_DELIMITER));
    //         if (parts.length != 5) {
    //             System.err.println("Invalid SEND_INIT format: " + payload);
    //             return;
    //         }
    //         String sender = parts[0];
    //         String receiver = parts[1];
    //         String filename = parts[2];
    //         long fileSize = Long.parseLong(parts[3]);
    //         int totalPackets = Integer.parseInt(parts[4]);

    //         String fileIdentifier = sender + "_" + receiver + "_" + filename;

    //         // Check if the file is already being received
    //         if (fileSize <= 0 || totalPackets <= 0) {
    //             sendPacket("SEND_INIT_RESP" + PACKET_DELIMITER + "ERROR" + PACKET_DELIMITER + "Invalid file size or packet count", clientAddress, clientPort);
    //             return;
    //         }

    //         incomingFileChunks.put(fileIdentifier, new ConcurrentSkipListMap<>()); // Prepare to receive chunks

    //         System.out.println("Receiving file '" + filename + "' from " + sender + " for " + receiver + " (" + fileSize + " bytes, " + totalPackets + " packets)");
    //         // Send an ACK back to the sender
    //         sendPacket("SEND_INIT_RESP" + PACKET_DELIMITER + "OK" + PACKET_DELIMITER + "Ready to receive file", clientAddress, clientPort);
    //     } catch (NumberFormatException e) {
    //         System.err.println("Error parsing SEND_INIT numbers: " + payload + " - " + e.getMessage());
    //         sendPacket("SEND_INIT_RESP" + PACKET_DELIMITER + "OK" + PACKET_DELIMITER + "ERROR", clientAddress, clientPort);
    //     } catch (Exception e) {
    //         System.err.println("Error in handleSendInit: " + e.getMessage());
    //         sendPacket("SEND_INIT_RESP" + PACKET_DELIMITER + "OK" + PACKET_DELIMITER + "ERROR", clientAddress, clientPort);
    //     }
    // }

    // public void handleSendData(String metadataPayload, byte[] rawPacketData, int packetLength,
    //         InetAddress clientAddress, int clientPort) {
    //     try {
    //         String[] parts = metadataPayload.split(Pattern.quote(PACKET_DELIMITER));
    //         if (parts.length < 5) { // Check minimum parts before data
    //             System.err.println("Invalid SEND_DATA format (metadata): " + metadataPayload);
    //             return;
    //         }
    //         String sender = parts[0];
    //         String receiver = parts[1];
    //         String filename = parts[2];
    //         int sequenceNumber = Integer.parseInt(parts[3]);

    //         String fileIdentifier = sender + "_" + receiver + "_" + filename;

    //         // Calculate where data starts
    //         String metadataHeader = "SEND_DATA" + PACKET_DELIMITER + metadataPayload.substring(0, metadataPayload.lastIndexOf(PACKET_DELIMITER) + PACKET_DELIMITER.length());
    //         int metadataLength = metadataHeader.getBytes().length;

    //         if (packetLength <= metadataLength) {
    //             System.err.println("SEND_DATA packet has no data payload for seq " + sequenceNumber);
    //             return;
    //         }

    //         byte[] dataChunk = Arrays.copyOfRange(rawPacketData, metadataLength, packetLength);

    //         ConcurrentSkipListMap<Integer, byte[]> chunks = incomingFileChunks.get(fileIdentifier);
    //         if (chunks != null) {
    //             chunks.put(sequenceNumber, dataChunk);
    //             // System.out.println("DEBUG: Received chunk " + sequenceNumber + " for " + fileIdentifier); // Verbose debug
    //         } else {
    //             System.err.println("Received data chunk for unknown/uninitialized file transfer: " + fileIdentifier);
    //         }
    //     } catch (NumberFormatException e) {
    //         System.err.println("Error parsing SEND_DATA sequence number: " + metadataPayload + " - " + e.getMessage());
    //     } catch (ArrayIndexOutOfBoundsException e) {
    //         System.err.println("Error processing SEND_DATA packet structure: " + metadataPayload + " - " + e.getMessage());
    //     } catch (Exception e) {
    //         System.err.println("Error in handleSendData: " + e.getMessage());
    //     }
    // }

    // public void handleSendFin(String payload, InetAddress clientAddress, int clientPort) {
    //     try {
    //         String[] parts = payload.split(Pattern.quote(PACKET_DELIMITER));
    //         if (parts.length != 3) {
    //             System.err.println("Invalid SEND_FIN format: " + payload);
    //             // Gửi phản hồi lỗi format
    //             sendPacket("SEND_FIN_RESP" + PACKET_DELIMITER + "ERROR" + PACKET_DELIMITER
    //                     + "Invalid format", clientAddress, clientPort);
    //             return;
    //         }

    //         String sender = parts[0];
    //         String receiver = parts[1];
    //         String filename = parts[2];
    //         String fileIdentifier = sender + "_" + receiver + "_" + filename;

    //         System.out.println("Received FIN for file '" + filename + "' from " + sender
    //                 + " for " + receiver);

    //         ConcurrentSkipListMap<Integer, byte[]> chunks = incomingFileChunks.remove(fileIdentifier);
    //         if (chunks == null) {
    //             System.err.println("Received FIN for file '" + filename
    //                     + "', but no chunks were received or already processed.");
    //             // Gửi phản hồi lỗi không có chunks
    //             sendPacket("SEND_FIN_RESP" + PACKET_DELIMITER + "ERROR" + PACKET_DELIMITER
    //                     + "No file chunks found", clientAddress, clientPort);
    //             return;
    //         }

    //         // Assemble the file
    //         Path filePath = Paths.get(STORAGE_DIR, filename);
    //         long totalBytesWritten = 0;
    //         try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
    //             for (byte[] chunk : chunks.values()) {
    //                 fos.write(chunk);
    //                 totalBytesWritten += chunk.length;
    //             }
    //             fos.flush();
    //             System.out.println("File '" + filename + "' assembled successfully ("
    //                     + totalBytesWritten + " bytes) in " + STORAGE_DIR);

    //             // Add file metadata for the recipient client
    //             FileMetaData metaData = new FileMetaData(filename, totalBytesWritten,
    //                     filePath.toString());
    //             filesForClients.computeIfAbsent(receiver, k
    //                     -> new CopyOnWriteArrayList<>()).add(metaData);

    //             // Gửi phản hồi thành công
    //             sendPacket("SEND_FIN_RESP" + PACKET_DELIMITER + "OK" + PACKET_DELIMITER
    //                     + "File assembled successfully: " + totalBytesWritten + " bytes",
    //                     clientAddress, clientPort);

    //             System.out.println("File '" + filename + "' is now available for client '"
    //                     + receiver + "'");

    //         } catch (IOException e) {
    //             System.err.println("Error writing assembled file '" + filename + "': "
    //                     + e.getMessage());
    //             // Clean up potentially partially written file
    //             try {
    //                 Files.deleteIfExists(filePath);
    //             } catch (IOException ex) {
    //                 System.err.println("Error deleting partial file: " + ex.getMessage());
    //             }
    //             // Gửi phản hồi lỗi khi ghi file
    //             sendPacket("SEND_FIN_RESP" + PACKET_DELIMITER + "ERROR" + PACKET_DELIMITER
    //                     + "Error writing file: " + e.getMessage(), clientAddress, clientPort);
    //         }

    //         // TODO: Fix room here
    //         Timestamp timestamp = new Timestamp(System.currentTimeMillis());
    //         File fileToSave = new File(null, "room01", sender, receiver, filename, timestamp);
    //         fileDAO.saveFile(fileToSave); // Save file metadata to DB

    //     } catch (Exception e) {
    //         System.err.println("Error in handleSendFin: " + e.getMessage());
    //         // Gửi phản hồi lỗi chung
    //         sendPacket("SEND_FIN_RESP" + PACKET_DELIMITER + "ERROR" + PACKET_DELIMITER
    //                 + "Server error", clientAddress, clientPort);
    //     }
    // }

    // public void handleListRequest(String clientName, InetAddress clientAddress, int clientPort) {
    //     System.out.println("Received LIST request from client: " + clientName);
    //     List<FileMetaData> files = filesForClients.getOrDefault(clientName, Collections.emptyList());

    //     StringBuilder fileListStr = new StringBuilder();
    //     if (files.isEmpty()) {
    //         fileListStr.append("No files available for you.");
    //     } else {
    //         for (int i = 0; i < files.size(); i++) {
    //             fileListStr.append(files.get(i).getFilename());
    //             fileListStr.append(" (").append(files.get(i).getFileSize()).append(" bytes)");
    //             if (i < files.size() - 1) {
    //                 fileListStr.append(", ");
    //             }
    //         }
    //     }

    //     String responsePayload = "LIST_RESP" + PACKET_DELIMITER + fileListStr.toString();
    //     sendPacket(responsePayload, clientAddress, clientPort);
    //     System.out.println("Sent file list to " + clientName + ": " + (files.isEmpty() ? "None" : fileListStr.toString()));
    // }

    // public void handleDownloadRequest(String payload, InetAddress clientAddress, int clientPort) {
    //     try {
    //         String[] parts = payload.split(Pattern.quote(PACKET_DELIMITER));
    //         if (parts.length != 2) {
    //             System.err.println("Invalid DOWNLOAD_REQ format: " + payload);
    //             return;
    //         }
    //         String clientName = parts[0];
    //         String requestedFilename = parts[1];

    //         System.out.println("Received DOWNLOAD request from " + clientName + " for file: " + requestedFilename);

    //         List<FileMetaData> clientFiles = filesForClients.get(clientName);
    //         Optional<FileMetaData> foundFile = Optional.empty();
    //         if (clientFiles != null) {
    //             foundFile = clientFiles.stream()
    //                     .filter(meta -> meta.getFilename().equals(requestedFilename))
    //                     .findFirst();
    //         }

    //         if (foundFile.isPresent()) {
    //             FileMetaData meta = foundFile.get();
    //             Path filePath = Paths.get(meta.getServerPath());
    //             if (Files.exists(filePath) && Files.isReadable(filePath)) {
    //                 System.out.println("Starting download of '" + requestedFilename + "' for " + clientName);
    //                 // Start sending the file in a separate thread
    //                 new Thread(() -> sendFileToClient(meta, filePath, clientAddress, clientPort)).start();
    //             } else {
    //                 System.err.println("File '" + requestedFilename + "' not found or not readable on server, although listed for " + clientName);
    //                 sendPacket("DOWNLOAD_ERR" + PACKET_DELIMITER + "File not found or unreadable on server.", clientAddress, clientPort);
    //             }
    //         } else {
    //             System.out.println("File '" + requestedFilename + "' not available for client " + clientName);
    //             sendPacket("DOWNLOAD_ERR" + PACKET_DELIMITER + "File not found or not assigned to you.", clientAddress, clientPort);
    //         }
    //     } catch (Exception e) {
    //         System.err.println("Error handling download request: " + e.getMessage());
    //         sendPacket("DOWNLOAD_ERR" + PACKET_DELIMITER + "Server error processing request.", clientAddress, clientPort);
    //     }
    // }

    // private void sendFileToClient(FileMetaData meta, Path filePath,
    //         InetAddress clientAddress, int clientPort) {
    //     try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
    //         long fileSize = meta.getFileSize();
    //         int totalPackets = (int) Math.ceil((double) fileSize / DATA_CHUNK_SIZE);

    //         // 1. Send Meta Packet
    //         String metaPayload = "DOWNLOAD_RESP_META" + PACKET_DELIMITER + meta.getFilename() + PACKET_DELIMITER + fileSize + PACKET_DELIMITER + totalPackets;
    //         sendPacket(metaPayload, clientAddress, clientPort);
    //         System.out.println("Sent META for " + meta.getFilename() + " to " + clientAddress + ":" + clientPort);
    //         Thread.sleep(10); // Small delay

    //         // 2. Send Data Packets
    //         byte[] dataBuffer = new byte[DATA_CHUNK_SIZE];
    //         int bytesRead;
    //         int sequenceNumber = 0;
    //         while ((bytesRead = fis.read(dataBuffer)) != -1) {
    //             sequenceNumber++;
    //             String dataHeader = "DOWNLOAD_RESP_DATA" + PACKET_DELIMITER + meta.getFilename() + PACKET_DELIMITER + sequenceNumber + PACKET_DELIMITER;
    //             byte[] headerBytes = dataHeader.getBytes();
    //             byte[] packetBytes = new byte[headerBytes.length + bytesRead];

    //             System.arraycopy(headerBytes, 0, packetBytes, 0, headerBytes.length);
    //             System.arraycopy(dataBuffer, 0, packetBytes, headerBytes.length, bytesRead);

    //             DatagramPacket dataPacket = new DatagramPacket(packetBytes, packetBytes.length, clientAddress, clientPort);
    //             socket.send(dataPacket);
    //             // System.out.println("Sent chunk " + sequenceNumber + "/" + totalPackets + " for " + meta.getFilename()); // Verbose
    //             Thread.sleep(5); // Small delay to avoid overwhelming the receiver buffer (basic rate control)
    //         }

    //         // 3. Send Fin Packet
    //         String finPayload = "DOWNLOAD_RESP_FIN" + PACKET_DELIMITER + meta.getFilename();
    //         sendPacket(finPayload, clientAddress, clientPort);
    //         System.out.println("Sent FIN for download of " + meta.getFilename() + " to " + clientAddress + ":" + clientPort);
    //     } catch (IOException e) {
    //         System.err.println("Error reading or sending file '" + meta.getFilename() + "': " + e.getMessage());
    //         sendPacket("DOWNLOAD_ERR" + PACKET_DELIMITER + "Server error during file transmission.", clientAddress, clientPort);
    //     } catch (InterruptedException e) {
    //         System.err.println("File sending thread interrupted for " + meta.getFilename());
    //         Thread.currentThread().interrupt();
    //     }
    // }

    protected void sendPacket(String payload, InetAddress address, int port) {
        try {
            byte[] sendData = payload.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);
            socket.send(sendPacket);
        } catch (IOException e) {
            System.err.println("Error sending packet to " + address + ":" + port + " - " + e.getMessage());
        }
    }

    protected void sendPacket(JsonObject jo, InetAddress address, int port) {
        try {
            String jsonString = jo.toString();
            byte[] sendData = jsonString.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);
            socket.send(sendPacket);
        } catch (IOException e) {
            System.err.println("Error sending packet to " + address + ":" + port + " - " + e.getMessage());
        }
    }
}
