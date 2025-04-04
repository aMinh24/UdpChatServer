package UdpChatServer.handler;

import java.net.DatagramSocket;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

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

        JsonObject originalRequest = pendingInfo.getOriginalMessageJson();
        JsonObject requestData = originalRequest.getAsJsonObject(Constants.KEY_DATA);
        String creatorChatId = requestData.get(Constants.KEY_CHAT_ID).getAsString();
        String transactionId = pendingInfo.getTransactionId();

        log.info("Processing confirmed create_room request from '{}' (Transaction ID: {})", creatorChatId, transactionId);

        try {
            // 1. Extract and validate participants list (again, for safety) from 'data'
            Set<String> participants = new HashSet<>();
            JsonArray participantsArray = requestData.getAsJsonArray(Constants.KEY_PARTICIPANTS);

            participants.add(creatorChatId); // Always include the creator
            for (JsonElement element : participantsArray) {
                participants.add(element.getAsString());
            }

            if (participants.size() < 2) {
                log.warn("Invalid participants count ({}) during confirmed create_room for transaction {}", participants.size(), transactionId);
                return false;
            }

            // 2. Verify all participants exist (using UserDAO)
            for (String chatId : participants) {
                if (!sessionManager.isOnline(chatId) && !userDAO.userExists(chatId)) {
                     log.warn("Participant '{}' not found during confirmed create_room for transaction {}", chatId, transactionId);
                     return false;
                }
            }

            // 3. Generate room ID based on participants
            String roomId = RoomManager.generateRoomId(participants);

            // 4. Get room name from request, or use a default if not provided
            String roomName;
            if (requestData.has(Constants.KEY_ROOM_NAME) && !requestData.get(Constants.KEY_ROOM_NAME).getAsString().trim().isEmpty()) {
                roomName = requestData.get(Constants.KEY_ROOM_NAME).getAsString().trim();
            } else {
                // Create a default name from participant IDs (limited to first 3)
                roomName = participants.stream().limit(3).collect(Collectors.joining(", "));
                if (participants.size() > 3) {
                    roomName += " + " + (participants.size() - 3) + " more";
                }
            }

            // 5. Create room in database with name and set the creator as owner
            // *** MODIFIED LINE: Pass creatorChatId as the third argument ***
            if (!roomDAO.createRoomIfNotExists(roomId, roomName, creatorChatId)) {
                log.error("Failed to create room '{}' with name '{}' and owner '{}' in DB for transaction {}",
                          roomId, roomName, creatorChatId, transactionId); // Added owner to log message
                return false;
            }

            // 6. Add all participants to room in DB
            boolean allAdded = true;
            for (String chatId : participants) {
                if (!roomDAO.addParticipantToRoom(roomId, chatId)) {
                    allAdded = false;
                    log.error("Failed to add participant {} to room {} in DB for transaction {}",
                              chatId, roomId, transactionId);
                }
            }

            if (!allAdded) {
                log.error("Failed to add all participants to room '{}' in DB for transaction {}", roomId, transactionId);
                // Consider rolling back room creation or participant additions if critical
                return false;
            }

            // 7. Update in-memory room manager
            roomManager.createOrJoinRoom(roomId, participants);

            // 8. Success! UdpRequestHandler will send ACK(success)
            log.info("Room {} with name '{}' created successfully by {} for transaction {}",
                     roomId, roomName, creatorChatId, transactionId);
            return true;

        } catch (Exception e) {
            log.error("Error processing confirmed create_room from {} (Transaction ID: {}): {}",
                     creatorChatId, transactionId, e.getMessage(), e);
            // Consider more specific error handling or rollback
            return false;
        }
    }
}
