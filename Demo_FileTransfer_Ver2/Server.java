import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

public class Server {

    private static final int SERVER_PORT = 9876;
    private static final int BUFFER_SIZE = 1024 * 4; // Max packet size, includes header
    private static final int DATA_CHUNK_SIZE = BUFFER_SIZE - 100; // Reserve space for header
    private static final String STORAGE_DIR = "server_storage";
    private static final String PACKET_DELIMITER = "|:|";

    // Stores metadata about files available for download for each client
    // Key: recipient client name, Value: List of FileMetaData objects
    private static final ConcurrentMap<String, List<FileMetaData>> filesForClients = new ConcurrentHashMap<>();

    // Temporarily stores incoming file chunks during upload
    // Key: unique file identifier (sender_receiver_filename), Value: Map<SequenceNumber, DataChunk>
    private static final ConcurrentMap<String, ConcurrentSkipListMap<Integer, byte[]>> incomingFileChunks = new ConcurrentHashMap<>();

    private DatagramSocket socket;

    public Server() throws SocketException {
        socket = new DatagramSocket(SERVER_PORT);
        System.out.println("UDP Server started on port " + SERVER_PORT);
        // Ensure storage directory exists
        try {
            Files.createDirectories(Paths.get(STORAGE_DIR));
            System.out.println("Server storage directory: " + Paths.get(STORAGE_DIR).toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Error creating server storage directory: " + e.getMessage());
            // Optionally exit if storage cannot be created
        }
    }

    public void listen() {
        byte[] receiveBuffer = new byte[BUFFER_SIZE];
        ExecutorService executor = Executors.newCachedThreadPool(); // Use a thread pool

        System.out.println("Server listening for incoming packets...");

        while (true) {
            try {
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                socket.receive(receivePacket);

                // Handle packet in a separate thread to avoid blocking the listener
                executor.submit(() -> handlePacket(receivePacket));

            } catch (IOException e) {
                System.err.println("IOException during receive: " + e.getMessage());
                // Consider if the server should stop or continue after an error
            }
        }
        // executor.shutdown(); // In a real application, provide a way to shut down gracefully
    }

    private void handlePacket(DatagramPacket packet) {
        try {
            String receivedData = new String(packet.getData(), 0, packet.getLength()).trim();
            String[] parts = receivedData.split(Pattern.quote(PACKET_DELIMITER), 2); // Split into command and rest
            if (parts.length < 1) {
                System.err.println("Received invalid packet format from " + packet.getAddress() + ":" + packet.getPort());
                return;
            }

            String command = parts[0];
            String payload = (parts.length > 1) ? parts[1] : "";
            InetAddress clientAddress = packet.getAddress();
            int clientPort = packet.getPort();

            // System.out.println("DEBUG: Received command '" + command + "' from " + clientAddress + ":" + clientPort); // Debug output

            switch (command) {
                case "SEND_INIT":
                    handleSendInit(payload, clientAddress, clientPort);
                    break;
                case "SEND_DATA":
                    handleSendData(payload, packet.getData(), packet.getLength(), clientAddress, clientPort);
                    break;
                case "SEND_FIN":
                    handleSendFin(payload, clientAddress, clientPort);
                    break;
                case "LIST_REQ":
                    handleListRequest(payload, clientAddress, clientPort);
                    break;
                case "DOWNLOAD_REQ":
                    handleDownloadRequest(payload, clientAddress, clientPort);
                    break;
                default:
                    System.err.println("Unknown command received: " + command);
            }
        } catch (Exception e) {
            System.err.println("Error handling packet from " + packet.getAddress() + ":" + packet.getPort() + " - " + e.getMessage());
        }
    }

    // Format: sender|:|receiver|:|filename|:|filesize|:|totalpackets
    private void handleSendInit(String payload, InetAddress clientAddress, int clientPort) {
        try {
            String[] parts = payload.split(Pattern.quote(PACKET_DELIMITER));
            if (parts.length != 5) {
                System.err.println("Invalid SEND_INIT format: " + payload);
                return;
            }
            String sender = parts[0];
            String receiver = parts[1];
            String filename = parts[2];
            long fileSize = Long.parseLong(parts[3]);
            int totalPackets = Integer.parseInt(parts[4]);

            String fileIdentifier = sender + "_" + receiver + "_" + filename;
            incomingFileChunks.put(fileIdentifier, new ConcurrentSkipListMap<>()); // Prepare to receive chunks

            System.out.println("Receiving file '" + filename + "' from " + sender + " for " + receiver + " (" + fileSize + " bytes, " + totalPackets + " packets)");
            // Optionally send an ACK back to the sender (not implemented for simplicity)
        } catch (NumberFormatException e) {
            System.err.println("Error parsing SEND_INIT numbers: " + payload + " - " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error in handleSendInit: " + e.getMessage());
        }
    }

    // Format: sender|:|receiver|:|filename|:|seqnum|:|DATA_BYTES
    private void handleSendData(String metadataPayload, byte[] rawPacketData, int packetLength, InetAddress clientAddress, int clientPort) {
        try {
            String[] parts = metadataPayload.split(Pattern.quote(PACKET_DELIMITER));
             if (parts.length < 5) { // Check minimum parts before data
                System.err.println("Invalid SEND_DATA format (metadata): " + metadataPayload);
                return;
            }
            String sender = parts[0];
            String receiver = parts[1];
            String filename = parts[2];
            int sequenceNumber = Integer.parseInt(parts[3]);

            String fileIdentifier = sender + "_" + receiver + "_" + filename;

            // Calculate where data starts
            String metadataHeader = "SEND_DATA" + PACKET_DELIMITER + metadataPayload.substring(0, metadataPayload.lastIndexOf(PACKET_DELIMITER) + PACKET_DELIMITER.length());
            int metadataLength = metadataHeader.getBytes().length;

            if (packetLength <= metadataLength) {
                 System.err.println("SEND_DATA packet has no data payload for seq " + sequenceNumber);
                 return;
            }

            byte[] dataChunk = Arrays.copyOfRange(rawPacketData, metadataLength, packetLength);

            ConcurrentSkipListMap<Integer, byte[]> chunks = incomingFileChunks.get(fileIdentifier);
            if (chunks != null) {
                chunks.put(sequenceNumber, dataChunk);
                // System.out.println("DEBUG: Received chunk " + sequenceNumber + " for " + fileIdentifier); // Verbose debug
            } else {
                System.err.println("Received data chunk for unknown/uninitialized file transfer: " + fileIdentifier);
            }
        } catch (NumberFormatException e) {
             System.err.println("Error parsing SEND_DATA sequence number: " + metadataPayload + " - " + e.getMessage());
        } catch (ArrayIndexOutOfBoundsException e) {
             System.err.println("Error processing SEND_DATA packet structure: " + metadataPayload + " - " + e.getMessage());
        } catch (Exception e) {
             System.err.println("Error in handleSendData: " + e.getMessage());
        }
    }

    // Format: sender|:|receiver|:|filename
    private void handleSendFin(String payload, InetAddress clientAddress, int clientPort) {
        try {
            String[] parts = payload.split(Pattern.quote(PACKET_DELIMITER));
            if (parts.length != 3) {
                System.err.println("Invalid SEND_FIN format: " + payload);
                return;
            }
            String sender = parts[0];
            String receiver = parts[1];
            String filename = parts[2];
            String fileIdentifier = sender + "_" + receiver + "_" + filename;

            System.out.println("Received FIN for file '" + filename + "' from " + sender + " for " + receiver);

            ConcurrentSkipListMap<Integer, byte[]> chunks = incomingFileChunks.remove(fileIdentifier);
            if (chunks == null) {
                System.err.println("Received FIN for file '" + filename + "', but no chunks were received or already processed.");
                return;
            }

            // Assemble the file
            Path filePath = Paths.get(STORAGE_DIR, filename); // Store directly in server_storage
            long totalBytesWritten = 0;
            try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                for (byte[] chunk : chunks.values()) { // ConcurrentSkipListMap iterates in key order (sequence number)
                    fos.write(chunk);
                    totalBytesWritten += chunk.length;
                }
                fos.flush();
                System.out.println("File '" + filename + "' assembled successfully (" + totalBytesWritten + " bytes) in " + STORAGE_DIR);

                // Add file metadata for the recipient client
                FileMetaData metaData = new FileMetaData(filename, totalBytesWritten, filePath.toString());
                filesForClients.computeIfAbsent(receiver, k -> new CopyOnWriteArrayList<>()).add(metaData); // Thread-safe list addition
                System.out.println("File '" + filename + "' is now available for client '" + receiver + "'");

            } catch (IOException e) {
                System.err.println("Error writing assembled file '" + filename + "': " + e.getMessage());
                // Clean up potentially partially written file
                try {
                    Files.deleteIfExists(filePath);
                } catch (IOException ex) {
                    System.err.println("Error deleting partial file: " + ex.getMessage());
                }
            }
        } catch (Exception e) {
             System.err.println("Error in handleSendFin: " + e.getMessage());
        }
    }

    // Format: clientname
    private void handleListRequest(String clientName, InetAddress clientAddress, int clientPort) {
        System.out.println("Received LIST request from client: " + clientName);
        List<FileMetaData> files = filesForClients.getOrDefault(clientName, Collections.emptyList());

        StringBuilder fileListStr = new StringBuilder();
        if (files.isEmpty()) {
            fileListStr.append("No files available for you.");
        } else {
            for (int i = 0; i < files.size(); i++) {
                fileListStr.append(files.get(i).getFilename());
                fileListStr.append(" (").append(files.get(i).getFileSize()).append(" bytes)");
                if (i < files.size() - 1) {
                    fileListStr.append(", ");
                }
            }
        }

        String responsePayload = "LIST_RESP" + PACKET_DELIMITER + fileListStr.toString();
        sendPacket(responsePayload, clientAddress, clientPort);
        System.out.println("Sent file list to " + clientName + ": " + (files.isEmpty() ? "None" : fileListStr.toString()));
    }

    // Format: clientname|:|filename
    private void handleDownloadRequest(String payload, InetAddress clientAddress, int clientPort) {
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

    private void sendFileToClient(FileMetaData meta, Path filePath, InetAddress clientAddress, int clientPort) {
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
                 Thread.sleep(5); // Small delay to avoid overwhelming the receiver buffer (basic rate control)
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


    private void sendPacket(String payload, InetAddress address, int port) {
        try {
            byte[] sendData = payload.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);
            socket.send(sendPacket);
        } catch (IOException e) {
            System.err.println("Error sending packet to " + address + ":" + port + " - " + e.getMessage());
        }
    }

    // Inner class to hold file metadata on the server
    private static class FileMetaData {
        private final String filename;
        private final long fileSize;
        private final String serverPath; // Path within server_storage

        public FileMetaData(String filename, long fileSize, String serverPath) {
            this.filename = filename;
            this.fileSize = fileSize;
            this.serverPath = serverPath;
        }

        public String getFilename() {
            return filename;
        }

        public long getFileSize() {
            return fileSize;
        }

        public String getServerPath() {
            return serverPath;
        }

        @Override
        public String toString() {
            return "FileMetaData{" +
                   "filename='" + filename + '\'' +
                   ", fileSize=" + fileSize +
                   ", serverPath='" + serverPath + '\'' +
                   '}';
        }
    }


    public static void main(String[] args) {
        try {
            Server server = new Server();
            server.listen();
        } catch (SocketException e) {
            System.err.println("Failed to start server: Could not bind to port " + SERVER_PORT);
        }
    }
}