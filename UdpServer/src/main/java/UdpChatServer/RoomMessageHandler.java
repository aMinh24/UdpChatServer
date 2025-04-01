package UdpChatServer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Handles requests related to room information (listing rooms, getting messages),
 * initiating the Server -> Client confirmation flow for responses.
 */
public class RoomMessageHandler {
    private static final Logger log = LoggerFactory.getLogger(RoomMessageHandler.class);
    // Ensure consistent date format, potentially with UTC timezone
    // Using ISO 8601 format is generally recommended for interoperability
    // private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    // static {
    //     dateFormat.setTimeZone(TimeZone.getTimeZone("UTC")); // Consider using UTC for consistency
    // }

    private final ClientSessionManager sessionManager;
    @SuppressWarnings("unused") private final RoomManager roomManager; // Keep reference if needed later
    private final RoomDAO roomDAO;
    private final MessageDAO messageDAO;
    private final DatagramSocket socket; // Keep for potential direct error replies
    private final UdpRequestHandler requestHandler; // To initiate S2C flow

    public RoomMessageHandler(ClientSessionManager sessionManager, RoomManager roomManager,
                            RoomDAO roomDAO, MessageDAO messageDAO, DatagramSocket socket, UdpRequestHandler requestHandler) {
        this.sessionManager = sessionManager;
        this.roomManager = roomManager;
        this.roomDAO = roomDAO;
        this.messageDAO = messageDAO;
        this.socket = socket;
        this.requestHandler = requestHandler;
    }

    /**
     * Handles the initial request to get the list of rooms for a user.
     * Retrieves the list and initiates the S2C flow to send the response.
     * Assumes requestJson is already decrypted and session is validated by UdpRequestHandler.
     *
     * @param requestPacket The original DatagramPacket.
     * @param requestJson   The decrypted JsonObject request.
     * @param clientAddress Client's IP address.
     * @param clientPort    Client's port.
     * @param sessionKey    Client's session key.
     */
    public void handleGetRooms(DatagramPacket requestPacket, JsonObject requestJson, InetAddress clientAddress, int clientPort, String sessionKey) {
        String chatid = null;

        try {
            // Basic validation: Extract fields from the 'data' object
            if (!requestJson.has(Constants.KEY_DATA)) {
                log.warn("get_rooms request missing 'data' object from {}:{}", clientAddress.getHostAddress(), clientPort);
                sendErrorReply(clientAddress, clientPort, Constants.ACTION_GET_ROOMS, Constants.ERROR_MSG_MISSING_FIELD + "'data'", sessionKey);
                return;
            }

            JsonObject dataRequest = requestJson.getAsJsonObject(Constants.KEY_DATA);
            if (!dataRequest.has(Constants.KEY_CHAT_ID)) {
                log.warn("get_rooms request missing 'chatid' in data from {}:{}", clientAddress.getHostAddress(), clientPort);
                sendErrorReply(clientAddress, clientPort, Constants.ACTION_GET_ROOMS, Constants.ERROR_MSG_MISSING_FIELD + "'chatid'", sessionKey);
                return;
            }

            chatid = dataRequest.get(Constants.KEY_CHAT_ID).getAsString();
            log.debug("Processing get_rooms request for user '{}'", chatid);

            // Lấy danh sách room từ database
            List<String> rooms = roomDAO.getRoomsByUser(chatid);

            // Tạo response JSON
            JsonObject data = new JsonObject();
            JsonArray roomsArray = new JsonArray();
            for (String roomId : rooms) {
                roomsArray.add(roomId);
            }
            data.add("rooms", roomsArray);

            JsonObject response = JsonHelper.createReply(
                Constants.ACTION_ROOMS_LIST,
                Constants.STATUS_SUCCESS,
                "Room list retrieved. Confirm receipt.", // Updated message
                data
            );

            // Initiate Server -> Client flow
            log.info("Retrieved {} rooms for user '{}'. Initiating S2C flow.", rooms.size(), chatid);
            requestHandler.initiateServerToClientFlow(
                Constants.ACTION_ROOMS_LIST,
                response,
                clientAddress,
                clientPort,
                sessionKey
            );

        } catch (Exception e) {
            log.error("Error processing get_rooms for user '{}' ({}:{}): {}",
                (chatid != null ? chatid : "UNKNOWN"),
                clientAddress.getHostAddress(), clientPort, e.getMessage(), e);
            // Send direct error reply ONLY if the S2C flow hasn't started
            // If flow started, errors should be handled via ACK mechanism ideally
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_GET_ROOMS,
                Constants.ERROR_MSG_INTERNAL_SERVER_ERROR, sessionKey);
        }
    }

    /**
     * Handles the initial request to get messages in a room.
     * Retrieves messages and initiates the S2C flow to send the response.
     * Assumes requestJson is already decrypted and session is validated by UdpRequestHandler.
     *
     * @param requestPacket The original DatagramPacket.
     * @param requestJson   The decrypted JsonObject request.
     * @param clientAddress Client's IP address.
     * @param clientPort    Client's port.
     * @param sessionKey    Client's session key.
     */
    public void handleGetMessages(DatagramPacket requestPacket, JsonObject requestJson, InetAddress clientAddress, int clientPort, String sessionKey) {
        String chatid = null;
        String roomId = null;

        try {
            // Basic validation: Extract fields from the 'data' object
            if (!requestJson.has(Constants.KEY_DATA)) {
                log.warn("get_messages request missing 'data' object from {}:{}", clientAddress.getHostAddress(), clientPort);
                sendErrorReply(clientAddress, clientPort, Constants.ACTION_GET_MESSAGES, Constants.ERROR_MSG_MISSING_FIELD + "'data'", sessionKey);
                return;
            }

            JsonObject dataRequest = requestJson.getAsJsonObject(Constants.KEY_DATA);
            if (!dataRequest.has(Constants.KEY_CHAT_ID) || !dataRequest.has(Constants.KEY_ROOM_ID)) {
                log.warn("get_messages request missing 'chatid' or 'room_id' in data from {}:{}", clientAddress.getHostAddress(), clientPort);
                sendErrorReply(clientAddress, clientPort, Constants.ACTION_GET_MESSAGES, Constants.ERROR_MSG_MISSING_FIELD + "'chatid' or 'room_id'", sessionKey);
                return;
            }

            chatid = dataRequest.get(Constants.KEY_CHAT_ID).getAsString();
            roomId = dataRequest.get(Constants.KEY_ROOM_ID).getAsString();
            log.debug("Processing get_messages request for user '{}', room '{}'", chatid, roomId);

            // Kiểm tra user có trong room không
            if (!roomDAO.isUserInRoom(roomId, chatid)) {
                log.warn("User {} attempted get_messages for room {} but is not a participant.", chatid, roomId);
                sendErrorReply(clientAddress, clientPort, Constants.ACTION_GET_MESSAGES,
                    Constants.ERROR_MSG_NOT_IN_ROOM, sessionKey);
                return;
            }

            List<Message> messages;
            Timestamp fromTime = null;

            // Nếu có thời gian trong request data, lấy tin nhắn từ thời gian đó
            if (dataRequest.has("from_time")) {
                String fromTimeStr = dataRequest.get("from_time").getAsString();
                try {
                    // Use Instant for parsing ISO 8601 format, which is more standard
                    fromTime = Timestamp.from(java.time.Instant.parse(fromTimeStr));
                    messages = messageDAO.getMessagesFromTime(roomId, fromTime);
                    log.debug("Retrieving messages for room {} from time {}", roomId, fromTime);
                } catch (java.time.format.DateTimeParseException e) {
                    log.warn("Invalid from_time format '{}' (expected ISO 8601) from user {}", fromTimeStr, chatid);
                    sendErrorReply(clientAddress, clientPort, Constants.ACTION_GET_MESSAGES,
                        Constants.ERROR_MSG_INVALID_TIME + " (Use ISO 8601 format like YYYY-MM-DDTHH:mm:ssZ)", sessionKey);
                    return;
                }
            } else {
                // Nếu không có thời gian, lấy tất cả tin nhắn
                messages = messageDAO.getAllMessagesByRoom(roomId);
                log.debug("Retrieving all messages for room {}", roomId);
            }

            // Tạo response JSON
            JsonObject data = new JsonObject();
            JsonArray messagesArray = new JsonArray();

            for (Message message : messages) {
                JsonObject messageObj = new JsonObject();
                messageObj.addProperty("message_id", message.getMessageId());
                messageObj.addProperty("sender_chatid", message.getSenderChatid());
                messageObj.addProperty("content", message.getContent());
                // Format timestamp consistently using ISO 8601 UTC
                messageObj.addProperty("timestamp", message.getTimestamp().toInstant().toString());
                messagesArray.add(messageObj);
            }

            data.add("messages", messagesArray);
            data.addProperty("room_id", roomId);
            if (fromTime != null) {
                data.addProperty("retrieved_from_time", fromTime.toInstant().toString());
            }

            JsonObject response = JsonHelper.createReply(
                Constants.ACTION_MESSAGES_LIST,
                Constants.STATUS_SUCCESS,
                "Messages retrieved. Confirm receipt.",
                data
            );

            // Initiate Server -> Client flow
            log.info("Retrieved {} messages for user '{}' in room '{}'. Initiating S2C flow.", messages.size(), chatid, roomId);
            requestHandler.initiateServerToClientFlow(
                Constants.ACTION_MESSAGES_LIST,
                response,
                clientAddress,
                clientPort,
                sessionKey
            );

        } catch (Exception e) {
            log.error("Error processing get_messages for user '{}', room '{}' ({}:{}): {}",
                (chatid != null ? chatid : "UNKNOWN"), roomId,
                clientAddress.getHostAddress(), clientPort, e.getMessage(), e);
            // Send direct error reply
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_GET_MESSAGES,
                Constants.ERROR_MSG_INTERNAL_SERVER_ERROR, sessionKey);
        }
    }

    /**
     * Processes the confirmed get_messages request.
     * Called by UdpRequestHandler.handleConfirmCount when client confirms character count.
     * 
     * @param pendingInfo Information about the confirmed get_messages transaction
     * @return true if messages were retrieved successfully, false otherwise
     */
    public boolean processConfirmedGetMessages(PendingMessageInfo pendingInfo) {
        if (pendingInfo == null || pendingInfo.getDirection() != PendingMessageInfo.Direction.CLIENT_TO_SERVER ||
            !Constants.ACTION_GET_MESSAGES.equals(pendingInfo.getOriginalAction())) {
            log.error("Invalid pending info passed to processConfirmedGetMessages: {}", pendingInfo);
            return false;
        }

        JsonObject originalRequest = pendingInfo.getOriginalMessageJson();
        JsonObject requestData = originalRequest.getAsJsonObject(Constants.KEY_DATA);
        String chatid = requestData.get(Constants.KEY_CHAT_ID).getAsString();
        String roomId = requestData.get(Constants.KEY_ROOM_ID).getAsString();
        String transactionId = pendingInfo.getTransactionId();

        log.info("Xử lý get_messages được xác nhận từ user '{}' cho room '{}' (Transaction ID: {})", 
                chatid, roomId, transactionId);

        try {
            // Kiểm tra user có trong room không
            if (!roomDAO.isUserInRoom(roomId, chatid)) {
                log.warn("User {} không nằm trong room {} khi xử lý get_messages đã xác nhận.", chatid, roomId);
                return false;
            }

            List<Message> messages;
            Timestamp fromTime = null;

            // Nếu có thời gian trong request data, lấy tin nhắn từ thời gian đó
            if (requestData.has("from_time")) {
                String fromTimeStr = requestData.get("from_time").getAsString();
                try {
                    fromTime = Timestamp.from(java.time.Instant.parse(fromTimeStr));
                    messages = messageDAO.getMessagesFromTime(roomId, fromTime);
                    log.debug("Lấy tin nhắn cho room {} từ thời điểm {}", roomId, fromTime);
                } catch (java.time.format.DateTimeParseException e) {
                    log.warn("Định dạng from_time không hợp lệ '{}' từ user {}", fromTimeStr, chatid);
                    return false;
                }
            } else {
                // Nếu không có thời gian, lấy tất cả tin nhắn
                messages = messageDAO.getAllMessagesByRoom(roomId);
                log.debug("Lấy tất cả tin nhắn cho room {}", roomId);
            }

            // Tạo response JSON
            JsonObject data = new JsonObject();
            JsonArray messagesArray = new JsonArray();

            for (Message message : messages) {
                JsonObject messageObj = new JsonObject();
                messageObj.addProperty("message_id", message.getMessageId());
                messageObj.addProperty("sender_chatid", message.getSenderChatid());
                messageObj.addProperty("content", message.getContent());
                messageObj.addProperty("timestamp", message.getTimestamp().toInstant().toString());
                messagesArray.add(messageObj);
            }

            data.add("messages", messagesArray);
            data.addProperty("room_id", roomId);
            if (fromTime != null) {
                data.addProperty("retrieved_from_time", fromTime.toInstant().toString());
            }

            JsonObject response = JsonHelper.createReply(
                Constants.ACTION_MESSAGES_LIST,
                Constants.STATUS_SUCCESS,
                "Lấy tin nhắn thành công.",
                data
            );

            // Gửi response qua S2C flow
            log.info("Lấy được {} tin nhắn cho user '{}' trong room '{}'. Bắt đầu luồng S2C.", 
                    messages.size(), chatid, roomId);
            requestHandler.initiateServerToClientFlow(
                Constants.ACTION_MESSAGES_LIST,
                response,
                pendingInfo.getPartnerAddress(),
                pendingInfo.getPartnerPort(),
                pendingInfo.getSessionKey()
            );

            return true;

        } catch (Exception e) {
            log.error("Lỗi khi xử lý get_messages từ user '{}' cho room '{}' (Transaction ID: {}): {}", 
                      chatid, roomId, transactionId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Processes the confirmed get_rooms request.
     * Called by UdpRequestHandler.handleConfirmCount when client confirms character count.
     * 
     * @param pendingInfo Information about the confirmed get_rooms transaction
     * @return true if rooms were retrieved successfully, false otherwise
     */
    public boolean processConfirmedGetRooms(PendingMessageInfo pendingInfo) {
        if (pendingInfo == null || pendingInfo.getDirection() != PendingMessageInfo.Direction.CLIENT_TO_SERVER ||
            !Constants.ACTION_GET_ROOMS.equals(pendingInfo.getOriginalAction())) {
            log.error("Invalid pending info passed to processConfirmedGetRooms: {}", pendingInfo);
            return false;
        }

        JsonObject originalRequest = pendingInfo.getOriginalMessageJson();
        JsonObject requestData = originalRequest.getAsJsonObject(Constants.KEY_DATA);
        String chatid = requestData.get(Constants.KEY_CHAT_ID).getAsString();
        String transactionId = pendingInfo.getTransactionId();

        log.info("Xử lý get_rooms được xác nhận từ user '{}' (Transaction ID: {})", chatid, transactionId);

        try {
            // Lấy danh sách room từ database 
            List<String> rooms = roomDAO.getRoomsByUser(chatid);
            
            // Tạo response JSON
            JsonObject data = new JsonObject();
            JsonArray roomsArray = new JsonArray();
            for (String roomId : rooms) {
                roomsArray.add(roomId); 
            }
            data.add("rooms", roomsArray);

            JsonObject response = JsonHelper.createReply(
                Constants.ACTION_ROOMS_LIST,
                Constants.STATUS_SUCCESS, 
                "Danh sách phòng chat được lấy thành công.",
                data
            );

            // Gửi response qua S2C flow
            log.info("Lấy được {} phòng chat cho user '{}'. Bắt đầu luồng S2C.", rooms.size(), chatid);
            requestHandler.initiateServerToClientFlow(
                Constants.ACTION_ROOMS_LIST,
                response,
                pendingInfo.getPartnerAddress(),
                pendingInfo.getPartnerPort(),
                pendingInfo.getSessionKey()
            );

            return true;

        } catch (Exception e) {
            log.error("Lỗi khi xử lý get_rooms từ user '{}' (Transaction ID: {}): {}", 
                      chatid, transactionId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Sends an error reply directly (only used for errors *before* S2C flow starts).
     * Encrypted with the provided session key.
     */
    private void sendErrorReply(InetAddress clientAddress, int clientPort, String action, String errorMessage, String sessionKey) {
         if (sessionKey == null || sessionKey.isEmpty()) {
            log.error("Cannot send error reply for action '{}' to {}:{}, session key is missing!", action, clientAddress.getHostAddress(), clientPort);
            return;
        }
        JsonObject errorReply = JsonHelper.createErrorReply(action, errorMessage);
        JsonHelper.sendPacket(socket, clientAddress, clientPort, errorReply, sessionKey, log);
    }
}
