package UdpChatServer.handler;

import java.net.DatagramSocket;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.manager.ClientSessionManager;
import UdpChatServer.manager.RoomManager;
import UdpChatServer.model.Constants;
import UdpChatServer.model.PendingMessageInfo;
/**
 * Handles the logic for processing confirmed "create_room" actions.
 * The initial request is handled by UdpRequestHandler, which initiates the C2S flow.
 */
public class CreateRoomHandler {
    private static final Logger log = LoggerFactory.getLogger(CreateRoomHandler.class);

    private final ClientSessionManager sessionManager;
    private final RoomManager roomManager;
    private final RoomDAO roomDAO;
    private final UserDAO userDAO;

    public CreateRoomHandler(ClientSessionManager sessionManager, RoomManager roomManager,
                           RoomDAO roomDAO, UserDAO userDAO, DatagramSocket socket) {
        this.sessionManager = sessionManager;
        this.roomManager = roomManager;
        this.roomDAO = roomDAO;
        this.userDAO = userDAO;
    }

    /**
     * Processes the create_room action AFTER the client has confirmed via CONFIRM_COUNT.
     * Creates the room in the database, adds participants, and updates the in-memory RoomManager.
     * This method is called by UdpRequestHandler.handleConfirmCount.
     *
     * @param pendingInfo Information about the confirmed create_room transaction.
     * @return true if the room was created successfully, false otherwise.
     */
    public boolean processConfirmedCreateRoom(PendingMessageInfo pendingInfo) {
        if (pendingInfo == null || pendingInfo.getDirection() != PendingMessageInfo.Direction.CLIENT_TO_SERVER ||
            !Constants.ACTION_CREATE_ROOM.equals(pendingInfo.getOriginalAction())) {
            log.error("Invalid pending info passed to processConfirmedCreateRoom: {}", pendingInfo);
            return false;
        }

        JsonObject originalRequest = pendingInfo.getOriginalMessageJson(); // This is the outer JSON {action: ..., data: {...}}
        JsonObject requestData = originalRequest.getAsJsonObject(Constants.KEY_DATA); // Get the inner 'data' object
        String creatorChatId = requestData.get(Constants.KEY_CHAT_ID).getAsString(); // Read from 'data'
        String transactionId = pendingInfo.getTransactionId();

        log.info("Processing confirmed create_room request from '{}' (Transaction ID: {})", creatorChatId, transactionId);

        try {
            // 1. Extract and validate participants list (again, for safety) from 'data'
            Set<String> participants = new HashSet<>();
            JsonArray participantsArray = requestData.getAsJsonArray(Constants.KEY_PARTICIPANTS); // Read from 'data'

            participants.add(creatorChatId); // Always include the creator
            for (JsonElement element : participantsArray) {
                participants.add(element.getAsString());
            }

            if (participants.size() < 2) {
                log.warn("Invalid participants count ({}) during confirmed create_room for transaction {}", participants.size(), transactionId);
                // UdpRequestHandler will send ACK(failure)
                return false;
            }

            // 2. Verify all participants exist (using UserDAO)
            for (String chatId : participants) {
                // Optimization: Check sessionManager first if user might be online
                if (!sessionManager.isOnline(chatId) && !userDAO.userExists(chatId)) {
                     log.warn("Participant '{}' not found during confirmed create_room for transaction {}", chatId, transactionId);
                     // UdpRequestHandler will send ACK(failure) with a generic error
                     return false;
                }
            }

            // 3. Generate room ID based on participants
            String roomId = RoomManager.generateRoomId(participants);

            // 4. Create room in database
            List<String> participantsList = new ArrayList<>(participants);

            // 5. Create room in database
            if (!roomDAO.createRoomIfNotExists(roomId, creatorChatId, participantsList)) {
                log.error("Failed to create room '{}' in DB for transaction {}", roomId, transactionId);
                // UdpRequestHandler will send ACK(failure)
                return false;
            }

            // 5. Add all participants to room in DB
            boolean allAdded = true;
            for (String chatId : participants) {
                if (!roomDAO.addParticipantToRoom(roomId, chatId)) {
                    allAdded = false;
                    log.error("Failed to add participant {} to room {} in DB for transaction {}", chatId, roomId, transactionId);
                    // Consider how to handle partial failure - for now, fail the whole operation
                }
            }

            if (!allAdded) {
                 log.error("Failed to add all participants to room '{}' in DB for transaction {}", roomId, transactionId);
                 // UdpRequestHandler will send ACK(failure)
                return false;
            }

            // 6. Update in-memory room manager
            roomManager.createOrJoinRoom(roomId, participants);

            // 7. Success! UdpRequestHandler will send ACK(success)
            log.info("Room {} created successfully by {} for transaction {}", roomId, creatorChatId, transactionId);
            // Optionally: Initiate S2C flow to notify other participants?
            // notifyParticipantsRoomCreated(roomId, participants, creatorChatId);
            return true;

        } catch (Exception e) {
            log.error("Error processing confirmed create_room from {} (Transaction ID: {}): {}",
                     creatorChatId, transactionId, e.getMessage(), e);
            // UdpRequestHandler will send ACK(failure)
            return false;
        }
    }

}
