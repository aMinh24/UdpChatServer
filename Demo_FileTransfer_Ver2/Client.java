
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

public class Client {

    private static final int SERVER_PORT = 9876;
    private static final String SERVER_ADDRESS = "localhost"; // Change if server is on a different machine
    private static final int BUFFER_SIZE = 1024 * 4; // Must match server's buffer size for receiving
    private static final int DATA_CHUNK_SIZE = BUFFER_SIZE - 100; // Data part size, matches server calculation
    private static final String PACKET_DELIMITER = "|:|";
    private static final int TIMEOUT_MS = 5000; // Socket timeout for receiving responses (e.g., 5 seconds)

    private DatagramSocket socket;
    private InetAddress serverAddress;
    private String clientName;
    private String clientStorageDir;

    // Temp storage for incoming download chunks
    // Key: Filename, Value: Map<SequenceNumber, DataChunk>
    private final ConcurrentMap<String, ConcurrentSkipListMap<Integer, byte[]>> incomingDownloads = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, FileDownloadState> downloadStates = new ConcurrentHashMap<>();

    public Client(String clientName) throws SocketException, UnknownHostException {
        this.clientName = clientName;
        this.clientStorageDir = clientName + "_storage";
        this.socket = new DatagramSocket(); // Bind to any available local port
        this.serverAddress = InetAddress.getByName(SERVER_ADDRESS);
        this.socket.setSoTimeout(TIMEOUT_MS); // Set timeout for receive calls

        // Ensure client storage directory exists
        try {
            Files.createDirectories(Paths.get(clientStorageDir));
            System.out.println("Client storage directory: " + Paths.get(clientStorageDir).toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Error creating client storage directory: " + e.getMessage());
            // Decide if the client should exit or continue without guaranteed storage
        }
        System.out.println("Client '" + clientName + "' started. Sending to Server at " + SERVER_ADDRESS + ":" + SERVER_PORT);
    }

    public void startConsole() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("\nAvailable commands:");
        System.out.println("  send <recipient_client_name> <local_filepath>");
        System.out.println("  list");
        System.out.println("  download <filename>");
        System.out.println("  exit");

        // Start a separate thread for receiving packets asynchronously
        Thread receiverThread = new Thread(this::receivePackets);
        receiverThread.setDaemon(true); // Allow program to exit even if this thread is running
        receiverThread.start();

        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }

            String[] parts = input.split("\\s+", 3); // Split into command and arguments
            String command = parts[0].toLowerCase();

            try {
                switch (command) {
                    case "send":
                        if (parts.length == 3) {
                            String recipient = parts[1];
                            String filepath = parts[2];
                            sendFile(recipient, filepath);
                        } else {
                            System.out.println("Usage: send <recipient_client_name> <local_filepath>");
                        }
                        break;
                    case "list":
                        if (parts.length == 1) {
                            requestFileList();
                        } else {
                            System.out.println("Usage: list");
                        }
                        break;
                    case "download":
                        if (parts.length == 2) {
                            String filename = parts[1];
                            requestDownload(filename);
                        } else {
                            System.out.println("Usage: download <filename>");
                        }
                        break;
                    case "exit":
                        System.out.println("Exiting client...");
                        socket.close();
                        scanner.close();
                        return; // Exit the loop and main method
                    default:
                        System.out.println("Unknown command. Available commands: send, list, download, exit");
                }
            } catch (IOException e) {
                System.err.println("Network error: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("An error occurred: " + e.getMessage());
            }
            // Add a small delay to allow receiver thread to potentially print output
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // Runs in a separate thread to listen for incoming packets from the server
    private void receivePackets() {
        byte[] receiveBuffer = new byte[BUFFER_SIZE];
        while (!socket.isClosed()) {
            try {
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                socket.receive(receivePacket); // This will block until a packet arrives or timeout

                handleReceivedPacket(receivePacket);

            } catch (SocketTimeoutException e) {
                // Timeout is expected, just continue listening
                continue;
            } catch (IOException e) {
                if (socket.isClosed()) {
                    System.out.println("Socket closed, receiver thread stopping.");
                    break;
                }
                System.err.println("Receiver thread IOException: " + e.getMessage());
                // Consider adding a delay or specific error handling here
            } catch (Exception e) {
                System.err.println("Error in receiver thread: " + e.getMessage());
            }
        }
    }

    private void handleReceivedPacket(DatagramPacket packet) {
        try {
            String receivedData = new String(packet.getData(), 0, packet.getLength()).trim();
            String[] parts = receivedData.split(Pattern.quote(PACKET_DELIMITER), 2);
            if (parts.length < 1) {
                return; // Ignore invalid packets
            }
            String command = parts[0];
            String payload = (parts.length > 1) ? parts[1] : "";

            // System.out.println("\nDEBUG: Received command '" + command + "' from server."); // Debug
            switch (command) {
                case "LIST_RESP":
                    System.out.println("\nFiles available for you on the server:");
                    System.out.println("  " + payload);
                    System.out.print("> "); // Prompt again
                    break;
                case "DOWNLOAD_RESP_META":
                    // Format: filename|:|filesize|:|totalpackets
                    handleDownloadMeta(payload);
                    break;
                case "DOWNLOAD_RESP_DATA":
                    // Format: filename|:|seqnum|:|DATA_BYTES
                    handleDownloadData(payload, packet.getData(), packet.getLength());
                    break;
                case "DOWNLOAD_RESP_FIN":
                    // Format: filename
                    handleDownloadFin(payload);
                    break;
                case "DOWNLOAD_ERR":
                    System.err.println("\nServer download error: " + payload);
                    System.out.print("> "); // Prompt again
                    // Clean up any partial download state if necessary
                    String filenameWithError = extractFilenameFromErrorPayload(payload); // Need a helper
                    if (filenameWithError != null) {
                        incomingDownloads.remove(filenameWithError);
                        downloadStates.remove(filenameWithError);
                    }
                    break;
                // Handle other potential server responses if needed (e.g., ACKs)
                default:
                    System.out.println("\nReceived unknown response from server: " + command);
                    System.out.print("> "); // Prompt again
            }
        } catch (Exception e) {
            System.err.println("Error handling received packet: " + e.getMessage());
        }
    }

    // Helper to attempt extracting filename if an error occurs mid-download
    private String extractFilenameFromErrorPayload(String payload) {
        // This is heuristic. Assumes filename might be mentioned.
        // A better approach is if server includes filename in error messages.
        // For now, just return null, requiring manual cleanup or timeout handling.
        return null;
    }

    private void sendFile(String recipient, String filepath) throws IOException {
        File file = new File(filepath);
        if (!file.exists() || !file.isFile()) {
            System.err.println("Error: File not found or is not a regular file: " + filepath);
            return;
        }
        if (!file.canRead()) {
            System.err.println("Error: Cannot read file: " + filepath);
            return;
        }

        long fileSize = file.length();
        int totalPackets = (int) Math.ceil((double) fileSize / DATA_CHUNK_SIZE);
        if (totalPackets == 0 && fileSize > 0) {
            totalPackets = 1; // Handle small files

                }if (fileSize == 0) {
            totalPackets = 0; // Handle empty file case
        }
        System.out.println("Preparing to send file '" + file.getName() + "' (" + fileSize + " bytes) to " + recipient + " in " + totalPackets + " packets.");

        // 1. Send INIT packet
        String initPayload = "SEND_INIT" + PACKET_DELIMITER
                + clientName + PACKET_DELIMITER
                + recipient + PACKET_DELIMITER
                + file.getName() + PACKET_DELIMITER
                + fileSize + PACKET_DELIMITER
                + totalPackets;
        sendPacket(initPayload);
        System.out.println("Sent INIT packet.");
        try {
            Thread.sleep(20);
        } catch (InterruptedException ignored) {
        } // Small delay

        // 2. Send DATA packets
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] dataBuffer = new byte[DATA_CHUNK_SIZE];
            int bytesRead;
            int sequenceNumber = 0;
            while ((bytesRead = fis.read(dataBuffer)) != -1) {
                sequenceNumber++;
                String dataHeader = "SEND_DATA" + PACKET_DELIMITER
                        + clientName + PACKET_DELIMITER
                        + recipient + PACKET_DELIMITER
                        + file.getName() + PACKET_DELIMITER
                        + sequenceNumber + PACKET_DELIMITER; // Delimiter marks end of header

                byte[] headerBytes = dataHeader.getBytes();
                byte[] packetBytes = new byte[headerBytes.length + bytesRead];

                // Copy header and data into the final packet byte array
                System.arraycopy(headerBytes, 0, packetBytes, 0, headerBytes.length);
                System.arraycopy(dataBuffer, 0, packetBytes, headerBytes.length, bytesRead);

                DatagramPacket dataPacket = new DatagramPacket(packetBytes, packetBytes.length, serverAddress, SERVER_PORT);
                socket.send(dataPacket);

                // Optional: Print progress
                if (sequenceNumber % 100 == 0 || sequenceNumber == totalPackets) { // Print every 100 packets or the last one
                    System.out.println("Sent packet " + sequenceNumber + "/" + totalPackets);
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ignored) {
                } // Basic rate control
            }

            // Handle zero-byte file case - still need FIN
            if (fileSize == 0 && sequenceNumber == 0) {
                System.out.println("Sending FIN for empty file.");
            } else if (sequenceNumber != totalPackets) {
                System.err.println("Warning: Sent " + sequenceNumber + " packets, but expected " + totalPackets);
            }

        } catch (IOException e) {
            System.err.println("Error reading or sending file data: " + e.getMessage());
            // Consider sending an ABORT packet to the server (not implemented)
            return; // Stop the process
        }

        // 3. Send FIN packet
        String finPayload = "SEND_FIN" + PACKET_DELIMITER
                + clientName + PACKET_DELIMITER
                + recipient + PACKET_DELIMITER
                + file.getName();
        sendPacket(finPayload);
        System.out.println("Sent FIN packet. File upload initiated.");
    }

    private void requestFileList() throws IOException {
        System.out.println("Requesting file list from server...");
        String requestPayload = "LIST_REQ" + PACKET_DELIMITER + clientName;
        sendPacket(requestPayload);
        // Response will be handled by the receiver thread
    }

    private void requestDownload(String filename) throws IOException {
        System.out.println("Requesting download of file: " + filename);
        String requestPayload = "DOWNLOAD_REQ" + PACKET_DELIMITER + clientName + PACKET_DELIMITER + filename;
        sendPacket(requestPayload);
        // Response and data transfer handled by receiver thread
    }

    // Format: filename|:|filesize|:|totalpackets
    private void handleDownloadMeta(String payload) {
        try {
            String[] parts = payload.split(Pattern.quote(PACKET_DELIMITER));
            if (parts.length != 3) {
                System.err.println("\nInvalid DOWNLOAD_RESP_META format: " + payload);
                System.out.print("> ");
                return;
            }
            String filename = parts[0];
            long fileSize = Long.parseLong(parts[1]);
            int totalPackets = Integer.parseInt(parts[2]);

            System.out.println("\nStarting download for '" + filename + "' (" + fileSize + " bytes, " + totalPackets + " packets).");
            System.out.print("> ");

            // Prepare to receive chunks
            incomingDownloads.put(filename, new ConcurrentSkipListMap<>());
            downloadStates.put(filename, new FileDownloadState(filename, fileSize, totalPackets));

        } catch (NumberFormatException e) {
            System.err.println("\nError parsing download metadata: " + payload + " - " + e.getMessage());
            System.out.print("> ");
        } catch (Exception e) {
            System.err.println("\nError processing download metadata: " + e.getMessage());
            System.out.print("> ");
        }
    }

    // Format: filename|:|seqnum|:|DATA_BYTES
    private void handleDownloadData(String metadataPayload, byte[] rawPacketData, int packetLength) {
        try {
            String[] parts = metadataPayload.split(Pattern.quote(PACKET_DELIMITER));
            if (parts.length < 3) { // filename|:|seqnum minimum
                System.err.println("Invalid DOWNLOAD_RESP_DATA format (metadata): " + metadataPayload);
                return;
            }
            String filename = parts[0];
            int sequenceNumber = Integer.parseInt(parts[1]);

            ConcurrentSkipListMap<Integer, byte[]> chunks = incomingDownloads.get(filename);
            FileDownloadState state = downloadStates.get(filename);

            if (chunks != null && state != null) {
                // Calculate where data starts
                String metadataHeader = "DOWNLOAD_RESP_DATA" + PACKET_DELIMITER + metadataPayload.substring(0, metadataPayload.lastIndexOf(PACKET_DELIMITER) + PACKET_DELIMITER.length());
                int metadataLength = metadataHeader.getBytes().length; // Use default charset

                if (packetLength <= metadataLength) {
                    System.err.println("DOWNLOAD_RESP_DATA packet has no data payload for seq " + sequenceNumber);
                    return;
                }

                byte[] dataChunk = Arrays.copyOfRange(rawPacketData, metadataLength, packetLength);
                chunks.put(sequenceNumber, dataChunk);
                state.incrementReceivedPackets();

                // Optional: Print progress
                if (state.getReceivedPackets() % 50 == 0 || state.getReceivedPackets() == state.getTotalPackets()) {
                    System.out.println("\nReceived packet " + state.getReceivedPackets() + "/" + state.getTotalPackets() + " for " + filename);
                    System.out.print("> ");
                }

            } else {
                // Might receive data before META or after FIN due to UDP reordering/loss, ignore if state isn't ready
                System.err.println("Received data chunk for unknown or completed download: " + filename + " seq " + sequenceNumber);
            }
        } catch (NumberFormatException e) {
            System.err.println("Error parsing DOWNLOAD_RESP_DATA sequence number: " + metadataPayload + " - " + e.getMessage());
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println("Error processing DOWNLOAD_RESP_DATA packet structure: " + metadataPayload + " - " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error handling download data chunk: " + e.getMessage());
        }
    }

    // Format: filename
    private void handleDownloadFin(String filename) {
        System.out.println("\nReceived FIN for download: " + filename);

        ConcurrentSkipListMap<Integer, byte[]> chunks = incomingDownloads.remove(filename);
        FileDownloadState state = downloadStates.remove(filename);

        if (chunks == null || state == null) {
            System.err.println("Received FIN for '" + filename + "', but download was not in progress or already completed/failed.");
            System.out.print("> ");
            return;
        }

        if (chunks.size() != state.getTotalPackets()) {
            System.err.println("Warning: Download finished for '" + filename + "', but received " + chunks.size() + " packets instead of expected " + state.getTotalPackets() + ". File might be incomplete due to packet loss.");
            // Decide whether to save the incomplete file or discard it. Let's save it with a warning.
        } else {
            System.out.println("All expected packets received for '" + filename + "'. Assembling file...");
        }

        // Assemble the file
        Path filePath = Paths.get(clientStorageDir, filename);
        long totalBytesWritten = 0;
        try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
            for (Map.Entry<Integer, byte[]> entry : chunks.entrySet()) {
                fos.write(entry.getValue());
                totalBytesWritten += entry.getValue().length;
            }
            fos.flush();
            System.out.println("File '" + filename + "' downloaded successfully (" + totalBytesWritten + " bytes) to " + clientStorageDir);

            // Verify size if possible (may differ slightly if last packet wasn't full but still counted)
            if (totalBytesWritten != state.getExpectedSize() && state.getTotalPackets() > 0) { // Only warn if not an empty file download
                System.out.println("Note: Final file size (" + totalBytesWritten + ") differs slightly from expected size (" + state.getExpectedSize() + "). This might be normal depending on chunking.");
            } else if (state.getTotalPackets() == 0 && totalBytesWritten == 0) {
                System.out.println("Empty file '" + filename + "' downloaded successfully.");
            }

        } catch (IOException e) {
            System.err.println("Error writing downloaded file '" + filename + "': " + e.getMessage());
            // Keep partially downloaded chunks map? Maybe remove them here.
            incomingDownloads.remove(filename); // Ensure cleanup
            downloadStates.remove(filename);
        } finally {
            System.out.print("> "); // Show prompt again
        }
    }

    private void sendPacket(String payload) throws IOException {
        byte[] sendData = payload.getBytes(); // Use default platform encoding
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, serverAddress, SERVER_PORT);
        socket.send(sendPacket);
    }

    // Inner class to track download state
    private static class FileDownloadState {

        final String filename;
        final long expectedSize;
        final int totalPackets;
        int receivedPackets;

        FileDownloadState(String filename, long expectedSize, int totalPackets) {
            this.filename = filename;
            this.expectedSize = expectedSize;
            this.totalPackets = totalPackets;
            this.receivedPackets = 0;
        }

        synchronized void incrementReceivedPackets() {
            this.receivedPackets++;
        }

        synchronized int getReceivedPackets() {
            return receivedPackets;
        }

        int getTotalPackets() {
            return totalPackets;
        }

        long getExpectedSize() {
            return expectedSize;
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java Client <client_name>");
            return;
        }
        String clientName = args[0];

        try {
            Client client = new Client(clientName);
            client.startConsole();
        } catch (SocketException e) {
            System.err.println("Client network error: " + e.getMessage());
        } catch (UnknownHostException e) {
            System.err.println("Client error: Could not find server host '" + SERVER_ADDRESS + "'");
        } catch (Exception e) {
            System.err.println("Client failed to start: " + e.getMessage());
        }
    }
}
