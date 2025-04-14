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
import UdpChatServer.model.SessionInfo;
import UdpChatServer.net.UdpSender;
import UdpChatServer.util.JsonHelper;

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
    private final UdpSender udpSender;

    public CreateRoomHandler(ClientSessionManager sessionManager, RoomManager roomManager,
                           RoomDAO roomDAO, UserDAO userDAO, DatagramSocket socket, UdpSender udpSender) {
        this.sessionManager = sessionManager;
        this.roomManager = roomManager;
        this.roomDAO = roomDAO;
        this.userDAO = userDAO;
        this.udpSender = udpSender; 
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
            participants.add(creatorChatId);
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
            forwardRoomToUser(roomId, roomName, creatorChatId, participants);
            return true;
   
        } catch (Exception e) {
            log.error("Error processing confirmed create_room from {} (Transaction ID: {}): {}",
                     creatorChatId, transactionId, e.getMessage(), e);
            // Consider more specific error handling or rollback
            return false;
        }
    }
    
    /**
     * Creates a default room for a newly registered user with the Gemini Bot.
     *
     * @param userChatId The chat ID of the newly registered user.
     * @return true if the room was created successfully, false otherwise.
     */
    public boolean createRoomWithBot(String userChatId) {
        log.info("Attempting to create default bot room for new user '{}'", userChatId);

        // 1. Check if bot user exists
        if (!userDAO.userExists(Constants.GEMINI_BOT_CHAT_ID)) {
            log.error("Cannot create default room: Bot user '{}' does not exist in the database.", Constants.GEMINI_BOT_CHAT_ID);
            return false;
        }

        // 2. Define participants
        Set<String> participants = new HashSet<>();
        participants.add(userChatId);
        participants.add(Constants.GEMINI_BOT_CHAT_ID);

        // 3. Generate room ID
        String roomId = RoomManager.generateRoomId(participants);
        String roomName = Constants.DEFAULT_BOT_ROOM_NAME;

        try {
            // 4. Create room in DB (user is the owner)
            if (!roomDAO.createRoomIfNotExists(roomId, roomName, userChatId)) {
                log.error("Failed to create default bot room '{}' with owner '{}' in DB.", roomId, userChatId);
                // Room might already exist, proceed to add participants if needed, but log error
            }

            // 5. Add participants to room in DB
            boolean userAdded = roomDAO.addParticipantToRoom(roomId, userChatId);
            boolean botAdded = roomDAO.addParticipantToRoom(roomId, Constants.GEMINI_BOT_CHAT_ID);

            if (!userAdded || !botAdded) {
                log.warn("Failed to add one or both participants ('{}', '{}') to default bot room '{}' in DB. Proceeding...", userChatId, Constants.GEMINI_BOT_CHAT_ID, roomId);
                // Continue even if adding fails, maybe they already exist
            }

            // 6. Update in-memory room manager
            roomManager.createOrJoinRoom(roomId, participants);

            // 7. Notify the new user about the room creation (S2C flow)
            log.info("Default bot room {} created/joined for user {}. Notifying user.", roomId, userChatId);
            // Pass the userChatId as the 'creator' here, as they are the one receiving the notification
            forwardRoomToUser(roomId, roomName, userChatId, participants);

            return true;

        } catch (Exception e) {
            log.error("Error creating default bot room for user '{}': {}", userChatId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Forwards room creation information to all participants
     * 
     * @param roomId The ID of the created room
     * @param roomName The name of the created room
     * @param creatorChatId The chat ID of the user who created the room
     * @param initialParticipants The initial set of participants (optional, will fetch from DB if null)
     */
    private void forwardRoomToUser(String roomId, String roomName, String creatorChatId, Set<String> initialParticipants) {
        // Get participants from RoomDAO for persistence, or use the provided set
        Set<String> participants = initialParticipants;
        if (participants == null || participants.isEmpty()) {
            participants = roomDAO.getParticipantsInRoom(roomId);
        }

        if (participants.isEmpty()) {
             log.warn("No participants found in RoomDAO/RoomManager for room '{}' to forward message.", roomId);
             return;
        }


        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_ROOM_ID, roomId);
        data.addProperty(Constants.KEY_SENDER_CHAT_ID, creatorChatId);
        data.addProperty(Constants.KEY_ROOM_NAME, roomName);
        data.add(Constants.KEY_PARTICIPANTS, JsonHelper.convertSetToJsonArray(participants)); // Convert Set to JsonArray
        JsonObject messageJson = JsonHelper.createReply(
            Constants.ACTION_RECIEVE_ROOM,
            Constants.STATUS_SUCCESS,
            "New room.",
            data
        );
        participants.add(creatorChatId); // Remove creator from participants for forwarding
        log.info("Forwarding room creation message to participants: {}", participants);
        for (String recipientChatId : participants) {
                SessionInfo recipientSession = sessionManager.getSessionInfo(recipientChatId);
                if (recipientSession != null && recipientSession.getKey() != null) {
                    // Initiate S2C flow for this recipient
                    log.debug("Initiating S2C flow to forward message from {} to {} in room {}", creatorChatId, recipientChatId, roomId);
                    udpSender.initiateServerToClientFlow( // Changed from requestHandler
                        Constants.ACTION_RECIEVE_ROOM,
                        messageJson,
                        recipientSession.getIpAddress(),
                        recipientSession.getPort(),
                        recipientSession.getKey() // Use recipient's session key
                    );
                } else {
                    log.debug("Recipient '{}' in room '{}' is offline or key missing. Message saved in DB, not forwarded in real-time.", recipientChatId, roomId);
                    // Message is already saved, so offline users will get it later via get_messages
                }
            
        }
    }
}
