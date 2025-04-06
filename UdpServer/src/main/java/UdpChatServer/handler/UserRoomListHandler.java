package UdpChatServer.handler;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import UdpChatServer.db.RoomDAO;
import UdpChatServer.manager.ClientSessionManager;
import UdpChatServer.model.Constants;
import UdpChatServer.model.PendingMessageInfo;
import UdpChatServer.net.UdpSender;
import UdpChatServer.util.JsonHelper;

/**
 * Handles requests for fetching the list of rooms a user is part of.
 */
public class UserRoomListHandler {
    private static final Logger log = LoggerFactory.getLogger(UserRoomListHandler.class);
    private static final Gson gson = new Gson(); // For converting List<Map> to JSON

    private final ClientSessionManager sessionManager;
    private final RoomDAO roomDAO;
    private final UdpSender udpSender;

    public UserRoomListHandler(ClientSessionManager sessionManager, RoomDAO roomDAO, UdpSender udpSender) {
        this.sessionManager = sessionManager;
        this.roomDAO = roomDAO;
        this.udpSender = udpSender;
    }

    /**
     * Processes a confirmed request to get the list of rooms for the requesting user.
     *
     * @param pendingInfo Information about the confirmed transaction.
     * @return true if the request was processed successfully, false otherwise.
     */
    public boolean processConfirmedGetUserRooms(PendingMessageInfo pendingInfo) {
        if (pendingInfo == null || pendingInfo.getDirection() != PendingMessageInfo.Direction.CLIENT_TO_SERVER ||
            !Constants.ACTION_GET_USER_ROOMS.equals(pendingInfo.getOriginalAction())) { // Assuming a new constant ACTION_GET_USER_ROOMS
            log.error("Invalid pending info for get user rooms: {}", pendingInfo);
            return false;
        }

        InetAddress clientAddress = pendingInfo.getPartnerAddress();
        int clientPort = pendingInfo.getPartnerPort();
        String transactionKey = pendingInfo.getTransactionKey();

        JsonObject originalRequest = pendingInfo.getOriginalMessageJson();
        JsonObject requestData = originalRequest.getAsJsonObject(Constants.KEY_DATA);

        String requestingUser = requestData.get(Constants.KEY_CHAT_ID).getAsString();

        log.info("Processing confirmed get_user_rooms request from user: '{}'", requestingUser);

        // Validate session
        if (!sessionManager.validateSession(requestingUser, clientAddress, clientPort)) {
            log.warn("Invalid session for get_user_rooms from {}", requestingUser);
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                    "Invalid session.", transactionKey);
            return false;
        }

        // Fetch rooms and members from DAO
        List<Map<String, Object>> userRoomsData = roomDAO.getRoomsAndMembersByUser(requestingUser);

        // Convert the list of maps to a JsonArray
        JsonArray roomsJsonArray = gson.toJsonTree(userRoomsData).getAsJsonArray();

        // Send success response with the room list
        JsonObject responseData = new JsonObject();
        responseData.add("rooms", roomsJsonArray); // Key name can be adjusted

        JsonObject response = JsonHelper.createReply(
            Constants.ACTION_USER_ROOM_LIST, // Assuming a new constant ACTION_USER_ROOM_LIST for the response
            Constants.STATUS_SUCCESS,
            "User room list retrieved successfully.",
            responseData
        );
        
        udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), true,
                response.toString(), transactionKey);

        log.info("Successfully sent room list to user '{}'", requestingUser);
        return true;
    }
} 
