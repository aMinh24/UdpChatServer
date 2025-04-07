// package UdpChatServer;

// import java.io.File;
// import java.io.FileInputStream;
// import java.io.FileOutputStream;
// import java.io.IOException;
// import java.net.DatagramPacket;
// import java.net.DatagramSocket;
// import java.net.InetAddress;
// import java.net.SocketException;
// import java.net.SocketTimeoutException;
// import java.net.UnknownHostException;
// import java.nio.charset.StandardCharsets;
// import java.nio.file.Files;
// import java.nio.file.Path;
// import java.nio.file.Paths;
// import java.util.Arrays;
// import java.util.Base64;
// import java.util.Map;
// import java.util.Scanner;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.ConcurrentMap;
// import java.util.concurrent.ConcurrentSkipListMap;

// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

// import com.google.gson.JsonObject;
// import com.google.gson.JsonParser;

// import UdpChatServer.model.Constants;

// public class Client {

//     private static final Logger log = LoggerFactory.getLogger(Client.class);

//     private static final int SERVER_PORT = 9877; // File transfer server port
//     private static final String SERVER_ADDRESS = "localhost"; // Change if server is on a different machine
//     private static final int TIMEOUT_MS = 5000; // Socket timeout for receiving responses (e.g., 5 seconds)
//     private static final int MAX_RETRIES = 3;
//     private static final int RETRY_DELAY_MS = 1000;
//     private static volatile boolean waitingForResponse = false;
//     private static volatile boolean serverAccepted = false;

//     private DatagramSocket socket;
//     private InetAddress serverAddress;
//     private String clientName;
//     private String clientStorageDir;

//     // Temp storage for incoming download chunks
//     // Key: Filename, Value: Map<SequenceNumber, DataChunk>
//     private final ConcurrentMap<String, ConcurrentSkipListMap<Integer, byte[]>> incomingDownloads = new ConcurrentHashMap<>();
//     private final ConcurrentMap<String, FileDownloadState> downloadStates = new ConcurrentHashMap<>();

//     public Client(String clientName) throws SocketException, UnknownHostException {
//         this.clientName = clientName;
//         this.clientStorageDir = clientName + "_storage";
//         this.socket = new DatagramSocket(); // Bind to any available local port
//         this.serverAddress = InetAddress.getByName(SERVER_ADDRESS);
//         this.socket.setSoTimeout(TIMEOUT_MS); // Set timeout for receive calls

//         // Ensure client storage directory exists
//         try {
//             Files.createDirectories(Paths.get(clientStorageDir));
//             System.out.println("Client storage directory: " + Paths.get(clientStorageDir).toAbsolutePath());
//         } catch (IOException e) {
//             System.err.println("Error creating client storage directory: " + e.getMessage());
//             // Decide if the client should exit or continue without guaranteed storage
//         }
//         System.out.println(
//                 "Client '" + clientName + "' started. Sending to Server at " + SERVER_ADDRESS + ":" + SERVER_PORT);
//     }

//     public void startConsole() {
//         Scanner scanner = new Scanner(System.in);
//         System.out.println("\nAvailable commands:");
//         System.out.println("  test - Test Json file transfer");
//         System.out.println("  send <recipient_client_name> <local_filepath>");
//         System.out.println("  list");
//         System.out.println("  download <filename>");
//         System.out.println("  exit");

//         // Start a separate thread for receiving packets asynchronously
//         Thread receiverThread = new Thread(this::receivePackets);
//         receiverThread.setDaemon(true); // Allow program to exit even if this thread is running
//         receiverThread.start();

//         while (true) {
//             System.out.print("> ");
//             String input = scanner.nextLine().trim();
//             if (input.isEmpty()) {
//                 continue;
//             }

//             String[] parts = input.split("\\s+", 3); // Split into command and arguments
//             String command = parts[0].toLowerCase();

//             try {
//                 switch (command) {
//                     case "send":
//                         if (parts.length == 3) {
//                             String recipient = parts[1];
//                             String filepath = parts[2];
//                             sendFile(recipient, filepath);
//                         } else {
//                             System.out.println("Usage: send <recipient_client_name> <local_filepath>");
//                         }
//                         break;
//                     case "list":
//                         if (parts.length == 1) {
//                             requestFileList();
//                         } else {
//                             System.out.println("Usage: list");
//                         }
//                         break;
//                     case "download":
//                         if (parts.length == 2) {
//                             String filename = parts[1];
//                             requestDownload(filename);
//                         } else {
//                             System.out.println("Usage: download <filename>");
//                         }
//                         break;
//                     case "exit":
//                         System.out.println("Exiting client...");
//                         socket.close();
//                         scanner.close();
//                         return; // Exit the loop and main method
//                     default:
//                         System.out.println("Unknown command. Available commands: send, list, download, exit");
//                 }
//             } catch (IOException e) {
//                 System.err.println("Network error: " + e.getMessage());
//             } catch (Exception e) {
//                 System.err.println("An error occurred: " + e.getMessage());
//             }
//             // Add a small delay to allow receiver thread to potentially print output
//             try {
//                 Thread.sleep(100);
//             } catch (InterruptedException ignored) {
//             }
//         }
//     }

//     private void receivePackets() {
//         byte[] receiveBuffer = new byte[Constants.BUFFER_SIZE];
//         while (!socket.isClosed()) {
//             try {
//                 DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
//                 socket.receive(receivePacket); // This will block until a packet arrives or timeout

//                 handleReceivedPacket(receivePacket);

//             } catch (SocketTimeoutException e) {
//                 // Timeout is expected, just continue listening
//                 continue;
//             } catch (IOException e) {
//                 if (socket.isClosed()) {
//                     System.out.println("Socket closed, receiver thread stopping.");
//                     break;
//                 }
//                 System.err.println("Receiver thread IOException: " + e.getMessage());
//                 // Consider adding a delay or specific error handling here
//             } catch (Exception e) {
//                 System.err.println("Error in receiver thread: " + e.getMessage());
//             }
//         }
//     }

//     private void handleReceivedPacket(DatagramPacket packet) {
//         try {
//             byte[] receivedData = packet.getData();
//             int receivedLength = packet.getLength();
//             String jsonString = new String(receivedData, 0, receivedLength, StandardCharsets.UTF_8);
//             JsonObject responseJson = JsonParser.parseString(jsonString).getAsJsonObject();
//             String action = responseJson.get(Constants.KEY_ACTION).getAsString();
//             String status;
//             String message;

//             switch (action) {
//                 case Constants.ACTION_FILE_INIT:
//                     status = responseJson.get(Constants.KEY_STATUS).getAsString();
//                     if (status.equals(Constants.STATUS_SUCCESS)) {
//                         serverAccepted = true;
//                         System.out.println("Server accepted file transfer request.");
//                     } else {
//                         System.err.println("Server rejected file transfer request.");
//                     }
//                     waitingForResponse = false;
//                     System.out.print("> ");
//                     break;
//                 case Constants.ACTION_FILE_FIN:
//                     message = responseJson.get(Constants.KEY_MESSAGE).getAsString();
//                     System.out.println("\nReceived FIN from Server: " + message);
//                     break;
//                 case Constants.ACTION_LIST_REQ:
//                     message = responseJson.get(Constants.KEY_MESSAGE).getAsString();
//                     System.out.println("\nFiles available for you on the server:");
//                     System.out.println(" " + message);
//                     System.out.print("> ");
//                     break;
//                 case Constants.ACTION_FILE_DOWN:
//                     handleDownloadMeta(responseJson);
//                     break;
//                 case Constants.ACTION_DOWN_REQ:
//                     status = responseJson.get(Constants.KEY_STATUS).getAsString();
//                     if (status.equals(Constants.STATUS_ERROR)) {
//                         message = responseJson.get(Constants.KEY_MESSAGE).getAsString();
//                         System.out.println(message);
//                     }
//                     break;
//                 case Constants.ACTION_FILE_DATA:
//                     handleDownloadData(responseJson);
//                     break;
//                 case Constants.ACTION_DOWN_FIN:
//                     status = responseJson.get(Constants.KEY_STATUS).getAsString();
//                     if (status.equals(Constants.STATUS_ERROR)) {
//                         message = responseJson.get(Constants.KEY_MESSAGE).getAsString();
//                         System.out.println(message);
//                     } else {
//                         handleDownloadFin(responseJson);
//                     }
//                     break;
//                 default:
//                     System.err.println("Received unknown response from server: " + jsonString);
//                     break;
//             }
//         } catch (Exception e) {
//             System.err.println("Error handling received packet: " + e.getMessage());
//         }
//     }

//     private void sendFile(String recipient, String filepath) throws IOException {
//         File file = new File(filepath);
//         if (!file.exists() || !file.isFile()) {
//             System.err.println("Error: File not found or is not a regular file: " + filepath);
//             return;
//         }
//         if (!file.canRead()) {
//             System.err.println("Error: Cannot read file: " + filepath);
//             return;
//         }

//         long fileSize = file.length();
//         int totalPackets = (int) Math.ceil((double) fileSize / Constants.DATA_CHUNK_SIZE);
//         if (totalPackets == 0 && fileSize > 0) {
//             totalPackets = 1; // Handle small files

//         }
//         if (fileSize == 0) {
//             totalPackets = 0; // Handle empty file case
//         }
//         System.out.println("Preparing to send file '" + file.getName() + "' (" + fileSize + " bytes) to " + recipient
//                 + " in " + totalPackets + " packets.");

//         // 1. Send INIT packet
//         waitingForResponse = true;
//         serverAccepted = false;

//         JsonObject initJson = new JsonObject();
//         initJson.addProperty(Constants.KEY_ACTION, Constants.ACTION_FILE_INIT);
//         JsonObject dataJson = new JsonObject();
//         dataJson.addProperty("client_name", clientName);
//         dataJson.addProperty("recipient", recipient);
//         dataJson.addProperty("file_name", file.getName());
//         dataJson.addProperty("file_size", fileSize);
//         dataJson.addProperty("total_packets", totalPackets);
//         initJson.add(Constants.KEY_DATA, dataJson);

//         // Add retries for INIT
//         int retries = 0;
//         while (retries < MAX_RETRIES) {
//             waitingForResponse = true;
//             serverAccepted = false;

//             sendPacket(initJson);
//             System.out.println("Sent INIT packet (attempt " + (retries + 1) + "/" + MAX_RETRIES + ")");

//             // Wait for response
//             long startTime = System.currentTimeMillis();
//             while (waitingForResponse) {
//                 if (System.currentTimeMillis() - startTime > TIMEOUT_MS) {
//                     break;
//                 }
//                 try {
//                     Thread.sleep(100);
//                 } catch (InterruptedException e) {
//                     break;
//                 }
//             }

//             if (serverAccepted) {
//                 break;
//             }

//             retries++;
//             if (retries < MAX_RETRIES) {
//                 System.out.println("Retrying INIT in " + RETRY_DELAY_MS + "ms...");
//                 try {
//                     Thread.sleep(RETRY_DELAY_MS);
//                 } catch (InterruptedException e) {
//                     break;
//                 }
//             }
//         }

//         if (!serverAccepted) {
//             System.err.println("Server did not accept the file transfer request after " + MAX_RETRIES + " attempts.");
//             return;
//         }

//         // 2. Send DATA packets with retries and progress tracking
//         try (FileInputStream fis = new FileInputStream(file)) {
//             byte[] dataBuffer = new byte[Constants.DATA_CHUNK_SIZE];
//             int bytesRead;
//             int sequenceNumber = 0;
//             long totalBytesSent = 0;

//             while ((bytesRead = fis.read(dataBuffer)) != -1) {
//                 sequenceNumber++;

//                 String base64Data = Base64.getEncoder().encodeToString(Arrays.copyOf(dataBuffer, bytesRead));
//                 JsonObject dataPacketJson = new JsonObject();
//                 dataPacketJson.addProperty(Constants.KEY_ACTION, Constants.ACTION_FILE_DATA);
//                 JsonObject dataDataJson = new JsonObject();
//                 dataDataJson.addProperty("client_name", clientName);
//                 dataDataJson.addProperty("recipient", recipient);
//                 dataDataJson.addProperty("file_name", file.getName());
//                 dataDataJson.addProperty("sequence_number", sequenceNumber);
//                 dataDataJson.addProperty("chunk_size", bytesRead);
//                 dataDataJson.addProperty("file_data", base64Data);
//                 dataPacketJson.add(Constants.KEY_DATA, dataDataJson);

//                 String jsonHeaderString = dataPacketJson.toString();
//                 byte[] packetBytes = jsonHeaderString.getBytes(StandardCharsets.UTF_8);

//                 // Add retry logic for each data packet
//                 boolean packetSent = false;
//                 retries = 0;

//                 while (!packetSent && retries < MAX_RETRIES) {
//                     try {
//                         DatagramPacket dataPacket = new DatagramPacket(packetBytes, packetBytes.length, serverAddress,
//                                 SERVER_PORT);
//                         socket.send(dataPacket);
//                         packetSent = true;
//                         totalBytesSent += bytesRead;

//                         // Print progress
//                         if (sequenceNumber % 10 == 0 || sequenceNumber == totalPackets) {
//                             double progress = (double) totalBytesSent / fileSize * 100;
//                             System.out.printf("Progress: %.1f%% (%d/%d packets sent)\n",
//                                     progress, sequenceNumber, totalPackets);
//                         }
//                     } catch (IOException e) {
//                         retries++;
//                         if (retries < MAX_RETRIES) {
//                             System.err.println("Error sending packet " + sequenceNumber + ", retrying...");
//                             Thread.sleep(RETRY_DELAY_MS);
//                         } else {
//                             throw new IOException(
//                                     "Failed to send packet " + sequenceNumber + " after " + MAX_RETRIES + " attempts");
//                         }
//                     }
//                 }

//                 Thread.sleep(5); // Basic rate control
//             }

//             // Handle zero-byte file or completion
//             if (fileSize == 0 && sequenceNumber == 0) {
//                 System.out.println("Sending FIN for empty file.");
//             } else {
//                 System.out.println("\nAll data packets sent successfully.");
//             }

//         } catch (Exception e) {
//             System.err.println("Error during file transfer: " + e.getMessage());
//             return;
//         }

//         // 3. Send FIN with retry
//         JsonObject finJson = new JsonObject();
//         finJson.addProperty(Constants.KEY_ACTION, Constants.ACTION_FILE_FIN);
//         JsonObject finDataJson = new JsonObject();
//         finDataJson.addProperty("client_name", clientName);
//         finDataJson.addProperty("recipient", recipient);
//         finDataJson.addProperty("file_name", file.getName());
//         finJson.add(Constants.KEY_DATA, finDataJson);

//         retries = 0;

//         while (retries < MAX_RETRIES) {
//             sendPacket(finJson);
//             System.out.println("Sent FIN packet (attempt " + (retries + 1) + "/" + MAX_RETRIES + ")");
//             break;
//         }
//     }

//     private void requestFileList() throws IOException {
//         JsonObject listReqJson = new JsonObject();
//         listReqJson.addProperty(Constants.KEY_ACTION, Constants.ACTION_LIST_REQ);
//         JsonObject dataJson = new JsonObject();
//         dataJson.addProperty("client_name", clientName);
//         listReqJson.add(Constants.KEY_DATA, dataJson);

//         System.out.println("Requesting file list from server...");
//         sendPacket(listReqJson);
//     }

//     private void requestDownload(String filename) throws IOException {
//         JsonObject downReqJson = new JsonObject();
//         downReqJson.addProperty(Constants.KEY_ACTION, Constants.ACTION_DOWN_REQ);
//         JsonObject dataJson = new JsonObject();
//         dataJson.addProperty("client_name", clientName);
//         dataJson.addProperty("file_name", filename);
//         downReqJson.add(Constants.KEY_DATA, dataJson);

//         System.out.println("Requesting download of file: " + filename);
//         sendPacket(downReqJson);
//         // Response and data transfer handled by receiver thread
//     }

//     // Format: filename|:|filesize|:|totalpackets
//     private void handleDownloadMeta(JsonObject responseJson) {
//         try {
//             JsonObject dataJson = responseJson.getAsJsonObject(Constants.KEY_DATA);
//             String filename = dataJson.get("file_name").getAsString();
//             long fileSize = dataJson.get("file_size").getAsLong();
//             int totalPackets = dataJson.get("total_packets").getAsInt();

//             System.out.println("\nStarting download for '" + filename + "' (" + fileSize + " bytes, " + totalPackets
//                     + " packets).");
//             System.out.print("> ");

//             // Prepare to receive chunks
//             incomingDownloads.put(filename, new ConcurrentSkipListMap<>());
//             downloadStates.put(filename, new FileDownloadState(filename, fileSize, totalPackets));

//         } catch (NumberFormatException e) {
//             System.err.println("\nError parsing download metadata: " + e.getMessage());
//             System.out.print("> ");
//         } catch (Exception e) {
//             System.err.println("\nError processing download metadata: " + e.getMessage());
//             System.out.print("> ");
//         }
//     }

//     // Format: filename|:|seqnum|:|DATA_BYTES
//     private void handleDownloadData(JsonObject responseJson) {
//         try {
//             if (!responseJson.has(Constants.KEY_DATA)) {
//                 throw new IllegalArgumentException("Missing data object in response");
//             }

//             JsonObject dataJson = responseJson.getAsJsonObject(Constants.KEY_DATA);
            
//             // Validate all required fields exist
//             String[] requiredFields = {"file_name", "sequence_number", "chunk_size", "file_data"};
//             for (String field : requiredFields) {
//                 if (!dataJson.has(field)) {
//                     throw new IllegalArgumentException("Missing required field: " + field);
//                 }
//             }

//             String filename = dataJson.get("file_name").getAsString();
//             int sequenceNumber = dataJson.get("sequence_number").getAsInt();
//             int chunkSize = dataJson.get("chunk_size").getAsInt();
//             String base64Data = dataJson.get("file_data").getAsString();

//             // Validate values
//             if (filename.isEmpty()) {
//                 throw new IllegalArgumentException("Filename cannot be empty");
//             }
//             if (sequenceNumber < 1) {
//                 throw new IllegalArgumentException("Invalid sequence number: " + sequenceNumber);
//             }
//             if (chunkSize < 0 || chunkSize > Constants.DATA_CHUNK_SIZE) {
//                 throw new IllegalArgumentException("Invalid chunk size: " + chunkSize);
//             }
//             if (base64Data.isEmpty()) {
//                 throw new IllegalArgumentException("Empty file data");
//             }

//             byte[] dataChunk;
//             try {
//                 dataChunk = Base64.getDecoder().decode(base64Data);
//                 if (dataChunk.length != chunkSize) {
//                     log.warn("Chunk size mismatch for {}: expected {} but got {}", 
//                         filename, chunkSize, dataChunk.length);
//                 }
//             } catch (IllegalArgumentException e) {
//                 throw new IllegalArgumentException("Invalid base64 data: " + e.getMessage());
//             }

//             ConcurrentSkipListMap<Integer, byte[]> chunks = incomingDownloads.get(filename);
//             FileDownloadState state = downloadStates.get(filename);

//             if (chunks != null && state != null) {
//                 chunks.put(sequenceNumber, dataChunk);
//                 state.incrementReceivedPackets();
//                 state.addBytesReceived(dataChunk.length);

//                 // In tiến trình mỗi 5% hoặc gói cuối cùng
//                 long totalBytes = state.getExpectedSize();
//                 long receivedBytes = state.getBytesReceived();
//                 int currentPercent = (int)((receivedBytes * 100.0) / totalBytes);
                
//                 if (currentPercent % 5 == 0 && currentPercent != state.getLastPrintedPercent()) {
//                     System.out.printf("\rDownloading: %d%% (%d/%d bytes) - %d/%d packets", 
//                         currentPercent,
//                         receivedBytes,
//                         totalBytes,
//                         state.getReceivedPackets(),
//                         state.getTotalPackets());
//                     System.out.flush();
//                     state.setLastPrintedPercent(currentPercent);
//                 }

//                 // In newline khi hoàn thành
//                 if (state.getReceivedPackets() == state.getTotalPackets()) {
//                     System.out.println("\nDownload completed!");
//                 }
//             } else {
//                 log.debug("Received data chunk for unknown or completed download: {} seq {}", 
//                     filename, sequenceNumber);
//             }
//         } catch (Exception e) {
//             log.error("Error handling download data: " + e.getMessage());
//             System.err.println("Error processing download chunk: " + e.getMessage());
//         }
//     }

//     // Format: filename
//     private void handleDownloadFin(JsonObject responseJson) {
//         JsonObject dataJson = responseJson.getAsJsonObject(Constants.KEY_DATA);
//         String filename = dataJson.get("file_name").getAsString();

//         System.out.println("\nReceived FIN for download: " + filename);

//         ConcurrentSkipListMap<Integer, byte[]> chunks = incomingDownloads.remove(filename);
//         FileDownloadState state = downloadStates.remove(filename);

//         if (chunks == null || state == null) {
//             System.err.println("Received FIN for '" + filename
//                     + "', but download was not in progress or already completed/failed.");
//             System.out.print("> ");
//             return;
//         }

//         if (chunks.size() != state.getTotalPackets()) {
//             System.err.println("Warning: Download finished for '" + filename + "', but received " + chunks.size()
//                     + " packets instead of expected " + state.getTotalPackets()
//                     + ". File might be incomplete due to packet loss.");
//             // Decide whether to save the incomplete file or discard it. Let's save it with
//             // a warning.
//         } else {
//             System.out.println("All expected packets received for '" + filename + "'. Assembling file...");
//         }

//         // Assemble the file
//         Path filePath = Paths.get(clientStorageDir, filename);
//         long totalBytesWritten = 0;
//         try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
//             for (Map.Entry<Integer, byte[]> entry : chunks.entrySet()) {
//                 fos.write(entry.getValue());
//                 totalBytesWritten += entry.getValue().length;
//             }
//             fos.flush();
//             System.out.println("File '" + filename + "' downloaded successfully (" + totalBytesWritten + " bytes) to "
//                     + clientStorageDir);

//             // Verify size if possible (may differ slightly if last packet wasn't full but
//             // still counted)
//             if (totalBytesWritten != state.getExpectedSize() && state.getTotalPackets() > 0) { // Only warn if not an
//                                                                                                // empty file download
//                 System.out.println(
//                         "Note: Final file size (" + totalBytesWritten + ") differs slightly from expected size ("
//                                 + state.getExpectedSize() + "). This might be normal depending on chunking.");
//             } else if (state.getTotalPackets() == 0 && totalBytesWritten == 0) {
//                 System.out.println("Empty file '" + filename + "' downloaded successfully.");
//             }

//         } catch (IOException e) {
//             System.err.println("Error writing downloaded file '" + filename + "': " + e.getMessage());
//             // Keep partially downloaded chunks map? Maybe remove them here.
//             incomingDownloads.remove(filename); // Ensure cleanup
//             downloadStates.remove(filename);
//         } finally {
//             System.out.print("> "); // Show prompt again
//         }
//     }

//     private void sendPacket(JsonObject payloadJson) {
//         try {
//             String payloadString = payloadJson.toString();
//             byte[] sendData = payloadString.getBytes(StandardCharsets.UTF_8);
//             DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, serverAddress, SERVER_PORT);
//             socket.send(sendPacket);
//         } catch (IOException e) {
//             System.err.println("Error sending packet to " + serverAddress + ":" + SERVER_PORT + " - " + e.getMessage());
//         }
//     }

//     // Inner class to track download state
//     private static class FileDownloadState {

//         final String filename;
//         final long expectedSize;
//         final int totalPackets;
//         private int receivedPackets;
//         private long bytesReceived;
//         private int lastPrintedPercent;

//         FileDownloadState(String filename, long expectedSize, int totalPackets) {
//             this.filename = filename;
//             this.expectedSize = expectedSize;
//             this.totalPackets = totalPackets;
//             this.receivedPackets = 0;
//             this.bytesReceived = 0;
//             this.lastPrintedPercent = -1;
//         }

//         synchronized void incrementReceivedPackets() {
//             this.receivedPackets++;
//         }

//         synchronized void addBytesReceived(int bytes) {
//             this.bytesReceived += bytes;
//         }

//         synchronized int getReceivedPackets() {
//             return receivedPackets;
//         }

//         synchronized long getBytesReceived() {
//             return bytesReceived;
//         }

//         synchronized int getLastPrintedPercent() {
//             return lastPrintedPercent;
//         }

//         synchronized void setLastPrintedPercent(int percent) {
//             this.lastPrintedPercent = percent;
//         }

//         int getTotalPackets() {
//             return totalPackets;
//         }

//         long getExpectedSize() {
//             return expectedSize;
//         }
//     }

//     public static void main(String[] args) {
//         if (args.length != 1) {
//             System.out.println("Usage: java Client <client_name>");
//             return;
//         }
//         String clientName = args[0];

//         try {
//             Client client = new Client(clientName);
//             client.startConsole();
//         } catch (SocketException e) {
//             System.err.println("Client network error: " + e.getMessage());
//         } catch (UnknownHostException e) {
//             System.err.println("Client error: Could not find server host '" + SERVER_ADDRESS + "'");
//         } catch (Exception e) {
//             System.err.println("Client failed to start: " + e.getMessage());
//         }
//     }
// }
