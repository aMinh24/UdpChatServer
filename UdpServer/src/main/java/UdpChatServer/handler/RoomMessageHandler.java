package UdpChatServer.handler;

import java.net.DatagramSocket;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import UdpChatServer.db.MessageDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.manager.ClientSessionManager;
import UdpChatServer.manager.RoomManager;
import UdpChatServer.model.Constants;
import UdpChatServer.model.Message;
import UdpChatServer.model.PendingMessageInfo;
import UdpChatServer.net.UdpSender;
import UdpChatServer.util.JsonHelper;

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

    @SuppressWarnings("unused") private final RoomManager roomManager; // Keep reference if needed later
    private final RoomDAO roomDAO;
    private final MessageDAO messageDAO;
    private final DatagramSocket socket; // Keep for potential direct error replies
    private final UdpSender udpSender; // Changed from requestHandler

    public RoomMessageHandler(ClientSessionManager sessionManager, RoomManager roomManager,
                            RoomDAO roomDAO, MessageDAO messageDAO, DatagramSocket socket, UdpSender udpSender) { // Changed parameter
        this.roomManager = roomManager;
        this.roomDAO = roomDAO;
        this.messageDAO = messageDAO;
        this.socket = socket;
        this.udpSender = udpSender; // Changed from requestHandler
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
                    log.info("Lấy tin nhắn cho room {} từ thời điểm {}", roomId, fromTime);
                } catch (java.time.format.DateTimeParseException e) {
                    log.warn("Định dạng from_time không hợp lệ '{}' từ user {}", fromTimeStr, chatid);
                    return false;
                }
            } else {
                // Nếu không có thời gian, lấy tất cả tin nhắn
                messages = messageDAO.getAllMessagesByRoom(roomId);
                log.info("Lấy tất cả tin nhắn cho room {}", roomId);
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
            udpSender.initiateServerToClientFlow( // Changed from requestHandler
                Constants.ACTION_MESSAGES_LIST,
                response,
                pendingInfo.getPartnerAddress(),
                pendingInfo.getPartnerPort(),
                pendingInfo.getTransactionKey()
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
            List<String> roomIds = roomDAO.getRoomsByUser(chatid);
            
            // Tạo response JSON
            JsonObject data = new JsonObject();
            JsonArray roomsArray = new JsonArray();
            
            // Create detailed room info objects with both id and name
            for (String roomId : roomIds) {
                JsonObject roomInfo = new JsonObject();
                roomInfo.addProperty("id", roomId);
                roomInfo.addProperty("name", roomDAO.getRoomName(roomId));
                roomsArray.add(roomInfo);
                log.debug("Added room to response: id={}, name={}", roomId, roomDAO.getRoomName(roomId));
            }
            
            data.add("rooms", roomsArray);

            JsonObject response = JsonHelper.createReply(
                Constants.ACTION_ROOMS_LIST,
                Constants.STATUS_SUCCESS, 
                "Danh sách phòng chat được lấy thành công.",
                data
            );

            // Gửi response qua S2C flow
            log.info("Lấy được {} phòng chat cho user '{}'. Bắt đầu luồng S2C.", roomIds.size(), chatid);
            udpSender.initiateServerToClientFlow(
                Constants.ACTION_ROOMS_LIST,
                response,
                pendingInfo.getPartnerAddress(),
                pendingInfo.getPartnerPort(),
                pendingInfo.getTransactionKey()
            );

            return true;

        } catch (Exception e) {
            log.error("Lỗi khi xử lý get_rooms từ user '{}' (Transaction ID: {}): {}", 
                      chatid, transactionId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Processes the confirmed get_room_users request.
     * Called by UdpRequestHandler.handleConfirmCount when client confirms character count.
     * 
     * @param pendingInfo Information about the confirmed get_room_users transaction
     * @return true if room users were retrieved successfully, false otherwise
     */
    public boolean processConfirmedGetRoomUsers(PendingMessageInfo pendingInfo) {
        if (pendingInfo == null || pendingInfo.getDirection() != PendingMessageInfo.Direction.CLIENT_TO_SERVER ||
            !Constants.ACTION_GET_ROOM_USERS.equals(pendingInfo.getOriginalAction())) {
            log.error("Invalid pending info passed to processConfirmedGetRoomUsers: {}", pendingInfo);
            return false;
        }

        JsonObject originalRequest = pendingInfo.getOriginalMessageJson();
        JsonObject requestData = originalRequest.getAsJsonObject(Constants.KEY_DATA);
        String chatid = requestData.get(Constants.KEY_CHAT_ID).getAsString();
        String roomId = requestData.get(Constants.KEY_ROOM_ID).getAsString();
        String transactionId = pendingInfo.getTransactionId();

        log.info("Processing confirmed get_room_users from '{}' for room '{}' (Transaction ID: {})", 
                chatid, roomId, transactionId);

        try {
            // Check if the user is part of the room
            if (!roomDAO.isUserInRoom(roomId, chatid)) {
                log.warn("User '{}' tried to get users for room '{}' but is not a participant", chatid, roomId);
                
                JsonObject errorResponse = JsonHelper.createErrorReply(
                    Constants.ACTION_GET_ROOM_USERS,
                    "You are not a participant of this room."
                );
                
                udpSender.sendAck(
                    pendingInfo.getPartnerAddress(),
                    pendingInfo.getPartnerPort(),
                    transactionId,
                    false,
                    errorResponse.toString(),
                    pendingInfo.getTransactionKey()
                );
                
                return false;
            }

            // Get users from database
            Set<String> users = roomDAO.getParticipantsInRoom(roomId);
            
            // Create response JSON
            JsonObject data = new JsonObject();
            JsonArray usersArray = new JsonArray();
            
            for (String userId : users) {
                usersArray.add(userId);
                log.debug("Added user to response: {}", userId);
            }
            
            data.add("users", usersArray);
            data.addProperty(Constants.KEY_ROOM_ID, roomId);
            data.addProperty("room_name", roomDAO.getRoomName(roomId));

            JsonObject response = JsonHelper.createReply(
                Constants.ACTION_ROOM_USERS_LIST,
                Constants.STATUS_SUCCESS, 
                "Room users list retrieved successfully.",
                data
            );

            // Send response through S2C flow
            udpSender.sendAck(
                pendingInfo.getPartnerAddress(),
                pendingInfo.getPartnerPort(),
                transactionId,
                true,
                response.toString(),
                pendingInfo.getTransactionKey()
            );
            
            return true;
        } catch (Exception e) {
            log.error("Error while processing get_room_users from user '{}' (Transaction ID: {}): {}", 
                      chatid, transactionId, e.getMessage(), e);
            return false;
        }
    }
}
