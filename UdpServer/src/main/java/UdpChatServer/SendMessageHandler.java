package UdpChatServer;

import java.net.DatagramSocket;
import java.sql.Timestamp;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

/**
 * Handles the logic for processing confirmed "send_message" actions.
 * The initial request is handled by UdpRequestHandler, which initiates the C2S flow.
 */
public class SendMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(SendMessageHandler.class);

    private final ClientSessionManager sessionManager;
    private final RoomManager roomManager;
    private final MessageDAO messageDAO; // Needed to save the message
    private final RoomDAO roomDAO;       // Needed to check participation
    private final DatagramSocket socket; // Needed for sending potential error replies directly? (Maybe not needed anymore)
    private final UdpRequestHandler requestHandler; // To initiate S2C flow for forwarding

    public SendMessageHandler(ClientSessionManager sessionManager, RoomManager roomManager, MessageDAO messageDAO, RoomDAO roomDAO, DatagramSocket socket, UdpRequestHandler requestHandler) {
        this.sessionManager = sessionManager;
        this.roomManager = roomManager;
        this.messageDAO = messageDAO;
        this.roomDAO = roomDAO;
        this.socket = socket; // Keep for now, might remove if error handling is centralized
        this.requestHandler = requestHandler;
    }

    /**
     * Processes the send_message action AFTER the client has confirmed via CONFIRM_COUNT.
     * Saves the message to the database and forwards it to other participants using the S2C flow.
     * This method is called by UdpRequestHandler.handleConfirmCount.
     *
     * @param pendingInfo Information about the confirmed message transaction.
     * @return true if the message was saved and forwarding was initiated successfully, false otherwise.
     */
    public boolean processConfirmedSendMessage(PendingMessageInfo pendingInfo) {
        if (pendingInfo == null || pendingInfo.getDirection() != PendingMessageInfo.Direction.CLIENT_TO_SERVER ||
            !Constants.ACTION_SEND_MESSAGE.equals(pendingInfo.getOriginalAction())) {
            log.error("Invalid pending info passed to processConfirmedSendMessage: {}", pendingInfo);
            return false;
        }

        JsonObject originalRequest = pendingInfo.getOriginalMessageJson(); // Outer JSON {action: ..., data: {...}}
        JsonObject messageData = originalRequest.getAsJsonObject(Constants.KEY_DATA); // Get the inner 'data' object
        String senderChatId = messageData.get(Constants.KEY_CHAT_ID).getAsString();  // Read from 'data'
        String roomId = messageData.get(Constants.KEY_ROOM_ID).getAsString();  // Read from 'data'
        String content = messageData.get(Constants.KEY_CONTENT).getAsString(); // Read from 'data'
        String sessionKey = pendingInfo.getSessionKey(); // Key of the sender

        log.info("Processing confirmed send_message from '{}' to room '{}' (Transaction ID: {})", senderChatId, roomId, pendingInfo.getTransactionId());

        try {
            // 0. Double-check if user is still in the room (optional, but good practice)
            if (!roomDAO.isUserInRoom(roomId, senderChatId)) {
                 log.warn("User '{}' is no longer a participant in room '{}' when processing confirmed message.", senderChatId, roomId);
                 // Don't send error back here, UdpRequestHandler will send ACK(failure)
                 return false;
            }

            // 1. Save message to DB
            // Create a new Message object to save
            Timestamp timestamp = new Timestamp(System.currentTimeMillis()); // Generate timestamp now
            Message messageToSave = new Message(null, roomId, senderChatId, content, timestamp);
            boolean saved = messageDAO.saveMessage(messageToSave);

            if (saved) {
                log.info("Message from '{}' to room '{}' saved successfully.", senderChatId, roomId);
                // 2. Forward message to other online participants using S2C flow
                forwardMessageToRoom(senderChatId, roomId, content, timestamp);
                // 3. Return success - UdpRequestHandler will send the final ACK to the sender
                return true;
            } else {
                log.error("Failed to save confirmed message from '{}' to DB for transaction {}.", senderChatId, pendingInfo.getTransactionId());
                // Return failure - UdpRequestHandler will send ACK(failure)
                return false;
            }
        } catch (Exception e) {
            log.error("Error processing confirmed send_message from '{}' for room '{}' (Transaction ID: {}): {}",
                      senderChatId, roomId, pendingInfo.getTransactionId(), e.getMessage(), e);
            // Return failure - UdpRequestHandler will send ACK(failure)
            return false;
        }
    }


    /**
     * Forwards a confirmed and saved message to all other *online* participants in the room
     * by initiating the Server -> Client confirmation flow for each recipient.
     */
    private void forwardMessageToRoom(String senderChatId, String roomId, String content, Timestamp timestamp) {
        // Get participants from RoomDAO for persistence, or RoomManager for in-memory state
        // Using RoomDAO might be slightly safer if RoomManager state could be inconsistent
        Set<String> participants = roomDAO.getParticipantsInRoom(roomId);
        // Set<String> participants = roomManager.getUsersInRoom(roomId); // Alternative using in-memory state

        if (participants.isEmpty()) {
             log.warn("No participants found in RoomDAO/RoomManager for room '{}' to forward message.", roomId);
             return;
        }

        log.debug("Forwarding message in room '{}' from '{}' to participants: {}", roomId, senderChatId, participants);

        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_ROOM_ID, roomId);
        data.addProperty(Constants.KEY_SENDER_CHAT_ID, senderChatId);
        data.addProperty(Constants.KEY_CONTENT, content);
        // Format timestamp consistently, e.g., using ISO 8601 or a defined format
        data.addProperty(Constants.KEY_TIMESTAMP, timestamp.toInstant().toString()); // Example: ISO 8601 UTC

        JsonObject messageJson = JsonHelper.createReply(
            Constants.ACTION_RECEIVE_MESSAGE,
            Constants.STATUS_SUCCESS,
            "New message received.",
            data
        );

        for (String recipientChatId : participants) {
            if (!recipientChatId.equals(senderChatId)) {
                SessionInfo recipientSession = sessionManager.getSessionInfo(recipientChatId);

                if (recipientSession != null && recipientSession.getKey() != null) {
                    // Initiate S2C flow for this recipient
                    log.debug("Initiating S2C flow to forward message from {} to {} in room {}", senderChatId, recipientChatId, roomId);
                    requestHandler.initiateServerToClientFlow(
                        Constants.ACTION_RECEIVE_MESSAGE,
                        messageJson,
                        recipientSession.getIpAddress(),
                        recipientSession.getPort(),
                        recipientSession.getKey() // Use recipient's key
                    );
                } else {
                    log.debug("Recipient '{}' in room '{}' is offline or key missing. Message saved in DB, not forwarded in real-time.", recipientChatId, roomId);
                    // Message is already saved, so offline users will get it later via get_messages
                }
            }
        }
    }

    // Note: sendErrorReply might not be needed here anymore if UdpRequestHandler handles ACKs
    /**
     * Sends an error reply to the client. (Potentially deprecated if ACKs handle errors)
     */
    // private void sendErrorReply(InetAddress clientAddress, int clientPort, String action, String errorMessage, String keyToSendWith) {
    //     if (keyToSendWith == null || keyToSendWith.isEmpty()) {
    //         log.error("Cannot send error reply for action '{}' to {}:{}, key is missing!", action, clientAddress.getHostAddress(), clientPort);
    //         return;
    //     }
    //     JsonObject replyJson = JsonHelper.createErrorReply(action, errorMessage);
    //     JsonHelper.sendPacket(socket, clientAddress, clientPort, replyJson, keyToSendWith, log);
    // }
}
