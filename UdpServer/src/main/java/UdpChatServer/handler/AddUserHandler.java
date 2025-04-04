package UdpChatServer.handler;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.manager.ClientSessionManager;
import UdpChatServer.model.Constants;
import UdpChatServer.model.PendingMessageInfo;
import UdpChatServer.net.UdpSender;

import java.net.InetAddress;

/**
 * Handles the process of adding a user to a room.
 * Only the room owner can add users to the room.
 */
public class AddUserHandler {
    private static final Logger log = LoggerFactory.getLogger(AddUserHandler.class);
    private final ClientSessionManager sessionManager;
    private final RoomDAO roomDAO;
    private final UserDAO userDAO;
    private final UdpSender udpSender;

    /**
     * Constructor for AddUserHandler.
     *
     * @param sessionManager Manages client sessions.
     * @param roomDAO        Data Access Object for room operations.
     * @param userDAO        Data Access Object for user operations.
     * @param udpSender      Handles sending UDP messages.
     */
    public AddUserHandler(ClientSessionManager sessionManager, RoomDAO roomDAO, UserDAO userDAO, UdpSender udpSender) {
        this.sessionManager = sessionManager;
        this.roomDAO = roomDAO;
        this.userDAO = userDAO;
        this.udpSender = udpSender;
    }

    /**
     * Processes a confirmed request to add a user to a room.
     * - Validates the session of the requesting user.
     * - Ensures the requesting user is the room owner.
     * - Checks if the target user exists.
     * - Checks if the target user is already in the room.
     * - Adds the target user to the room if all conditions are met.
     *
     * @param pendingInfo The pending message information containing the request details.
     * @return true if the user was successfully added, false otherwise.
     */
    public boolean processConfirmedAddUser(PendingMessageInfo pendingInfo) {
        // Extract client address and port from the pending message
        InetAddress clientAddress = pendingInfo.getPartnerAddress();
        int clientPort = pendingInfo.getPartnerPort();

        // Parse the JSON data from the original message
        JsonObject data = pendingInfo.getOriginalMessageJson().getAsJsonObject(Constants.KEY_DATA);
        String transactionKey = pendingInfo.getTransactionKey();

        // Extract chatId, roomId, and targetChatId from the JSON data
        String chatId = data.get(Constants.KEY_CHAT_ID).getAsString();
        String roomId = data.get(Constants.KEY_ROOM_ID).getAsString(); // Sử dụng KEY_ROOM_ID
        String targetChatId = data.get(Constants.KEY_TARGET_CHATID).getAsString(); // Sử dụng KEY_TARGET_CHATID

        // Validate the session of the requesting user
        if (!sessionManager.validateSession(chatId, clientAddress, clientPort)) {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), Constants.STATUS_FAILURE_BOOL, "Invalid session.", transactionKey);
            log.warn("Add user failed: Invalid session for chatId: {}", chatId);
            return false;
        }

        // Check if the requesting user is the room owner
        String ownerChatId = roomDAO.getRoomOwner(roomId);
        if (ownerChatId == null || !ownerChatId.equals(chatId)) {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), Constants.STATUS_FAILURE_BOOL, "Only the room owner can add users.", transactionKey);
            log.warn("Add user failed: User {} is not the owner of room {}.", chatId, roomId);
            return false;
        }

        // Check if the target user exists in the database
        if (!userDAO.userExists(targetChatId)) {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), Constants.STATUS_FAILURE_BOOL, "Target user does not exist.", transactionKey);
            log.warn("Add user failed: Target user {} does not exist.", targetChatId);
            return false;
        }

        // Check if the target user is already in the room
        if (roomDAO.isUserInRoom(roomId, targetChatId)) {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), Constants.STATUS_FAILURE_BOOL, "User is already in the room.", transactionKey);
            log.warn("Add user failed: Target user {} is already in room {}.", targetChatId, roomId);
            return false;
        }

        // Add the target user to the room
        boolean success = roomDAO.addParticipantToRoom(roomId, targetChatId);
        if (success) {
            // Prepare response data
            JsonObject responseData = new JsonObject();
            responseData.addProperty(Constants.KEY_ROOM_ID, roomId); // Sử dụng KEY_ROOM_ID
            responseData.addProperty(Constants.KEY_TARGET_CHATID, targetChatId); // Sử dụng KEY_TARGET_CHATID

            // Send success response to the client
            udpSender.initiateServerToClientFlow(Constants.ACTION_ADD_USER_SUCCESS, responseData, clientAddress, clientPort, transactionKey);
            log.info("User {} was added to room {} by {}.", targetChatId, roomId, chatId);
            return true;
        } else {
            // Send failure response to the client
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), Constants.STATUS_FAILURE_BOOL, "Failed to add user.", transactionKey);
            log.error("Add user failed: Could not add user {} to room {}.", targetChatId, roomId);
            return false;
        }
    }
}