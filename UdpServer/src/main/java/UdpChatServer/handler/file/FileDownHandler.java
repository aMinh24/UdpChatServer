package UdpChatServer.handler.file;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.MessageDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.manager.ClientSessionManager;
import UdpChatServer.model.Constants;
import UdpChatServer.model.FileState;

public class FileDownHandler extends FileTransferHandler {
    // MAX_RETRIES không còn cần thiết ở đây vì việc gửi không cần đợi ACK từ client
    // trong mô hình đơn giản này. Client sẽ tự xử lý việc thiếu gói tin nếu cần.

    public FileDownHandler(MessageDAO messageDAO, UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO,
            DatagramSocket socket, ClientSessionManager sessionManager) {
        // Gọi constructor của lớp cha với đầy đủ tham số
        super(sessionManager, messageDAO, userDAO, roomDAO, fileDAO, socket);
    }

    /**
     * Handles the initial download request (ACTION_FILE_DOWN_REQ).
     * Finds the file and starts the sending process in a new thread if found.
     */
    public void handle(JsonObject jsonPacket, InetAddress clientAddress, int clientPort) {
        String requestedFilename = null;
        try {
            JsonObject dataJson = jsonPacket.getAsJsonObject(Constants.KEY_DATA);
            String roomId = dataJson.get("room_id").getAsString(); // Lấy roomId từ request
            requestedFilename = dataJson.get("file_name").getAsString(); // Lấy tên file từ request

            System.out.println("Received DOWNLOAD request from " + clientAddress + ":" + clientPort +
                    " (Room: " + roomId + ") for file: " + requestedFilename);

            // Tìm thông tin file trong cơ sở dữ liệu
            List<FileState> roomFiles = fileDAO.getFilesByRoom(roomId);
            final String finalRequestedFilename = requestedFilename; // Biến final để dùng trong lambda
            Optional<FileState> foundFileState = roomFiles.stream()
                    .filter(fs -> fs.getFilePath().equals(finalRequestedFilename))
                    .findFirst();

            if (foundFileState.isPresent()) {
                FileState fileState = foundFileState.get();
                // Xây dựng đường dẫn đầy đủ tới file trên server
                Path filePath = Paths.get(Constants.STORAGE_DIR, roomId, fileState.getFilePath());

                if (Files.exists(filePath) && Files.isReadable(filePath)) {
                    System.out.println("File '" + requestedFilename + "' found. Starting download process for " +
                            clientAddress + ":" + clientPort);
                    // Khởi chạy việc gửi file trong một luồng riêng biệt
                    // Truyền thông tin cần thiết: fileState, filePath, địa chỉ client, socket
                    new Thread(() -> sendFileToClient(fileState, roomId, filePath, clientAddress, clientPort)).start();

                } else {
                    System.err.println("File '" + requestedFilename + "' not found or not readable on server at path: "
                            + filePath);
                    sendErrorResponse(Constants.ACTION_FILE_DOWN_REQ, "File not found or unreadable on server.",
                            clientAddress, clientPort);
                }
            } else {
                System.out.println("File '" + requestedFilename + "' not found in database for room " + roomId);
                sendErrorResponse(Constants.ACTION_FILE_DOWN_REQ, "File not listed for this room.", clientAddress,
                        clientPort);
            }
        } catch (Exception e) {
            System.err.println("Server error processing download request for room " + ", file "
                    + requestedFilename + ": " + e.getMessage());
            e.printStackTrace(); // Log stack trace for debugging
            // Gửi phản hồi lỗi chung nếu có lỗi không mong muốn
            sendErrorResponse(Constants.ACTION_FILE_DOWN_REQ, "Server error processing request.", clientAddress,
                    clientPort);
        }
    }

    /**
     * Sends the file content (Meta, Data, Fin) to the client.
     * This method runs in a separate thread.
     *
     * @param fileState     Metadata of the file from the database.
     * @param filePath      The actual path to the file on the server.
     * @param clientAddress The client's IP address.
     * @param clientPort    The client's port.
     */
    private void sendFileToClient(FileState fileState, String roomId, Path filePath, InetAddress clientAddress, int clientPort) {
        String filename = fileState.getFilePath(); // Lấy tên file gốc
        try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
            long fileSize = Files.size(filePath); // Lấy kích thước file thực tế
            int totalPackets = (int) Math.ceil((double) fileSize / Constants.DATA_CHUNK_SIZE);
            // Xử lý trường hợp file 0 byte
            if (fileSize == 0) {
                totalPackets = 0;
            }

            // --- 1. Send META Packet ---
            JsonObject metaDataJson = new JsonObject();
            metaDataJson.addProperty("room_id", roomId);
            metaDataJson.addProperty("file_path", filename);
            metaDataJson.addProperty("file_size", fileSize);
            metaDataJson.addProperty("total_packets", totalPackets);
            JsonObject metaJsonPacket = createJsonPacket(Constants.ACTION_FILE_DOWN_META, null, null, metaDataJson);
            sendPacket(metaJsonPacket, clientAddress, clientPort);
            System.out.println("Sent META for " + filename + " to " + clientAddress + ":" + clientPort +
                    " (Size: " + fileSize + ", Packets: " + totalPackets + ")");
            Thread.sleep(10); // Delay nhỏ để client kịp xử lý META

            // --- 2. Send DATA Packets ---
            byte[] dataBuffer = new byte[Constants.DATA_CHUNK_SIZE];
            int bytesRead;
            int sequenceNumber = 0;

            while ((bytesRead = fis.read(dataBuffer)) != -1) {
                sequenceNumber++; // Bắt đầu từ 1

                // Chỉ mã hóa Base64 phần dữ liệu thực sự đọc được
                String base64Data = Base64.getEncoder().encodeToString(Arrays.copyOf(dataBuffer, bytesRead));

                JsonObject dataJson = new JsonObject();
                dataJson.addProperty("room_id", roomId);
                dataJson.addProperty("file_name", filename);
                dataJson.addProperty("sequence_number", sequenceNumber);
                dataJson.addProperty("chunk_size", bytesRead); // Kích thước thực tế của chunk này
                dataJson.addProperty("file_data", base64Data);

                JsonObject dataJsonPacket = createJsonPacket(Constants.ACTION_FILE_DOWN_DATA, null, null, dataJson);

                // Gửi gói DATA
                sendPacket(dataJsonPacket, clientAddress, clientPort);

                // In log tiến trình (tùy chọn, có thể giảm tần suất in)
                if (sequenceNumber % 50 == 0 || sequenceNumber == totalPackets) {
                    System.out.printf("\rSending %s: Packet %d / %d to %s:%d",
                            filename, sequenceNumber, totalPackets, clientAddress.getHostAddress(), clientPort);
                }

                Thread.sleep(1); // Giảm delay giữa các gói DATA xuống 1ms để tăng tốc độ
            }
            System.out.println(); // Xuống dòng sau khi in tiến trình xong

            // Xử lý trường hợp file 0 byte (không có vòng lặp while nào chạy)
            if (fileSize == 0 && sequenceNumber == 0) {
                System.out.println("File " + filename + " is empty. Sending FIN.");
            } else {
                System.out.println("Finished sending all " + totalPackets + " data packets for " + filename + ".");
            }

            // --- 3. Send FIN Packet (Success) ---
            JsonObject dataFinJson = new JsonObject();
            dataFinJson.addProperty("room_id", roomId);
            dataFinJson.addProperty("file_path", filename);
            JsonObject finJsonPacket = createJsonPacket(Constants.ACTION_FILE_DOWN_FIN, null, null, dataFinJson);
            sendPacket(finJsonPacket, clientAddress, clientPort);
            System.out.println(
                    "Sent FIN (Success) for download of " + filename + " to " + clientAddress + ":" + clientPort);

        } catch (IOException e) {
            System.err.println("Error reading or sending file '" + filename + "' to " + clientAddress + ":" + clientPort
                    + ": " + e.getMessage());
            // --- Send FIN Packet (Error) ---
            sendFinError(filename, "Server error during file transmission.", clientAddress, clientPort);

        } catch (InterruptedException e) {
            System.err.println("File sending thread interrupted for " + filename);
            // --- Send FIN Packet (Error) ---
            sendFinError(filename, "Server transmission interrupted.", clientAddress, clientPort);
            Thread.currentThread().interrupt(); // Khôi phục trạng thái interrupted
        } catch (Exception e) { // Bắt các lỗi khác
            System.err.println("Unexpected error during file transmission for '" + filename + "': " + e.getMessage());
            e.printStackTrace();
            sendFinError(filename, "Unexpected server error during transmission.", clientAddress, clientPort);
        }
    }

    /**
     * Helper method to send an error response packet.
     *
     * @param action        The action type for the response.
     * @param message       The error message.
     * @param clientAddress The client's IP address.
     * @param clientPort    The client's port.
     */
    private void sendErrorResponse(String action, String message, InetAddress clientAddress, int clientPort) {
        JsonObject responseJson = createJsonPacket(action, Constants.STATUS_ERROR, message, null);
        sendPacket(responseJson, clientAddress, clientPort);
    }

    /**
     * Helper method to send a FIN packet indicating an error.
     *
     * @param filename      The name of the file being transferred.
     * @param message       The error message.
     * @param clientAddress The client's IP address.
     * @param clientPort    The client's port.
     */
    private void sendFinError(String filename, String message, InetAddress clientAddress, int clientPort) {
        JsonObject finDataJson = new JsonObject();
        finDataJson.addProperty("file_name", filename); // Vẫn nên gửi tên file để client biết lỗi thuộc về file nào
        JsonObject finPacket = createJsonPacket(Constants.ACTION_FILE_DOWN_FIN, Constants.STATUS_ERROR, message,
                finDataJson);
        sendPacket(finPacket, clientAddress, clientPort);
        System.out.println("Sent FIN (Error: " + message + ") for download of " + filename + " to " + clientAddress
                + ":" + clientPort);
    }
}