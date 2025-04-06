package UdpChatServer.net;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import UdpChatServer.crypto.CaesarCipher;
import UdpChatServer.manager.ClientSessionManager;
import UdpChatServer.model.Constants;
import UdpChatServer.model.PendingMessageInfo;
import UdpChatServer.model.PendingMessageInfo.Direction;
import UdpChatServer.model.PendingMessageInfo.State; // Keep RoomManager if needed for context in sending? Unlikely.
import UdpChatServer.util.JsonHelper;

public class UdpSender {

    private static final Logger log = LoggerFactory.getLogger(UdpSender.class); // Corrected logger class
    private final DatagramSocket socket;

    // Removed internal handler instances

    // Managers and DAOs (Keep only if directly needed by UdpSender's own logic)
    private final ClientSessionManager sessionManager; // Needed for transaction management
    // Removed unused DAOs and RoomManager from UdpSender fields

    // Simplified constructor - only takes dependencies needed by UdpSender itself
    public UdpSender(DatagramSocket socket, ClientSessionManager sessionManager) {
        this.socket = socket;
        this.sessionManager = sessionManager; // Needed for transaction management

         // Removed internal handler initializations
    }

    // Removed Getters for handlers

    /**
     * Initiates the Client -> Server flow upon receiving an initial request
     * (like send_message, create_room, or now login). Calculates frequencies,
     * stores pending transaction, sends CHARACTER_COUNT to client.
     *
     * @param transactionKey The key used for this transaction (session or
     * fixed).
     */
    public void initiateClientToServerFlow(String originalAction, JsonObject requestJson, String decryptedJsonString,
            InetAddress clientAddress, int clientPort, String transactionKey) {
        // Extract chatid if available (won't be for login)
        String chatid = null;
        if (!originalAction.equals(Constants.ACTION_LOGIN) && requestJson.has(Constants.KEY_DATA) && requestJson.getAsJsonObject(Constants.KEY_DATA).has(Constants.KEY_CHAT_ID)) {
            chatid = requestJson.getAsJsonObject(Constants.KEY_DATA).get(Constants.KEY_CHAT_ID).getAsString();
        }
        log.info("Initiating Client->Server flow for action '{}' from {}:{} (User: {})", originalAction, clientAddress.getHostAddress(), clientPort, chatid != null ? chatid : "N/A (Login)");

        // 1. Calculate frequencies on the received decrypted JSON string
        Map<Character, Integer> letterFrequencies = CaesarCipher.countLetterFrequencies(decryptedJsonString);

        // 2. Create and store pending transaction info
        String transactionId = sessionManager.generateTransactionId("C2S_" + originalAction);
        PendingMessageInfo pendingInfo = new PendingMessageInfo(
                transactionId,
                Direction.CLIENT_TO_SERVER,
                State.WAITING_FOR_CONFIRM, // Server is now waiting for client's CONFIRM_COUNT
                requestJson,
                decryptedJsonString,
                letterFrequencies,
                clientAddress, // Partner is the client
                clientPort,
                transactionKey // Store the key used for this transaction
        );
        sessionManager.storePendingTransaction(pendingInfo);
        log.info("Pending transaction stored. ID: '{}', Action: '{}'", transactionId, originalAction);

        // 3. Send CHARACTER_COUNT back to the client using the same key
        sendCharacterCount(clientAddress, clientPort, transactionId, letterFrequencies, transactionKey);
    }

    /**
     * Handles incoming CONFIRM_COUNT message from a client (part of Client ->
     * Server flow). Expects transaction_id and confirm within the 'data'
     * object.
     *
     * @param transactionKey The key used to decrypt this CONFIRM_COUNT message (should match the original transaction key).
     * @return The validated PendingMessageInfo if confirmation is valid and positive, null otherwise.
     *         The caller (UdpRequestHandler) is responsible for executing the action and sending the ACK.
     */
    public PendingMessageInfo handleConfirmCount(JsonObject requestJson, InetAddress clientAddress, int clientPort, String transactionKey) {
        // CONFIRM_COUNT data comes from the client, expected within 'data' object
        if (!requestJson.has(Constants.KEY_DATA)) {
            log.warn("Received CONFIRM_COUNT from {}:{} missing 'data' object.", clientAddress.getHostAddress(), clientPort);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, Constants.ERROR_MSG_MISSING_FIELD + "'data'", transactionKey);
            return null; // Indicate failure
        }
        JsonObject data = requestJson.getAsJsonObject(Constants.KEY_DATA);

        if (!data.has("transaction_id") || !data.has(Constants.KEY_CONFIRM)) {
            log.warn("Received CONFIRM_COUNT from {}:{} missing 'transaction_id' or 'confirm' field within 'data'.", clientAddress.getHostAddress(), clientPort);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, Constants.ERROR_MSG_MISSING_FIELD + "'data.transaction_id' or 'data.confirm'", transactionKey);
            return null; // Indicate failure
        }

        String transactionId = data.get("transaction_id").getAsString();
        boolean confirmed = data.get(Constants.KEY_CONFIRM).getAsBoolean();

        log.info("Received CONFIRM_COUNT for transaction '{}' from {}:{}. Confirmed: {}", transactionId, clientAddress.getHostAddress(), clientPort, confirmed);

        // Retrieve the pending transaction *without removing it yet*
        PendingMessageInfo pendingInfo = sessionManager.getPendingTransaction(transactionId);

        if (pendingInfo == null) {
            log.warn("No pending transaction found for CONFIRM_COUNT with id '{}' from {}:{}", transactionId, clientAddress.getHostAddress(), clientPort);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, Constants.ERROR_MSG_PENDING_ACTION_NOT_FOUND, transactionKey);
            return null; // Indicate failure
        }

        // Validate the key used for this message matches the stored transaction key
        if (!transactionKey.equals(pendingInfo.getTransactionKey())) {
            log.warn("Key mismatch for CONFIRM_COUNT transaction '{}'. Expected key type matches fixed: {}, Received key type matches fixed: {}",
                    transactionId, pendingInfo.getTransactionKey().equals(Constants.FIXED_LOGIN_KEY_STRING), transactionKey.equals(Constants.FIXED_LOGIN_KEY_STRING));
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, "Key mismatch for transaction.", transactionKey);
            // Do not remove pending info, might be a spoof attempt or delayed packet with old key
            return null; // Indicate failure
        }

        // Validate state and direction
        if (pendingInfo.getDirection() != Direction.CLIENT_TO_SERVER || pendingInfo.getCurrentState() != State.WAITING_FOR_CONFIRM) {
            log.warn("Invalid state ({}) or direction ({}) for CONFIRM_COUNT transaction '{}'", pendingInfo.getCurrentState(), pendingInfo.getDirection(), transactionId);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, Constants.ERROR_MSG_INVALID_STATE, transactionKey);
            // Optionally remove the invalid state transaction
            sessionManager.retrieveAndRemovePendingTransaction(transactionId);
            return null; // Indicate failure
        }

        // Validate sender matches original transaction partner
        if (!pendingInfo.getPartnerAddress().equals(clientAddress) || pendingInfo.getPartnerPort() != clientPort) {
            log.warn("CONFIRM_COUNT sender {}:{} does not match pending transaction partner {}:{} for id '{}'",
                    clientAddress.getHostAddress(), clientPort, pendingInfo.getPartnerAddress().getHostAddress(), pendingInfo.getPartnerPort(), transactionId);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, "Sender mismatch for transaction.", transactionKey);
            return null; // Indicate failure
        }

        // Process based on confirmation
        if (confirmed) {
            // Confirmation is valid and positive. Return the pendingInfo for the caller to process.
            log.info("CONFIRM_COUNT successful for transaction '{}'. Delegating action execution.", transactionId);
            // DO NOT remove the transaction here. The caller (UdpRequestHandler) will remove it after processing.
            return pendingInfo;
        } else {
            // Client cancelled
            log.info("Client cancelled action '{}' for transaction '{}'", pendingInfo.getOriginalAction(), transactionId);
            // Send ACK indicating cancellation
            sendAck(clientAddress, clientPort, transactionId, false, "Action cancelled by client.", pendingInfo.getTransactionKey());
            // Remove the cancelled transaction
            sessionManager.retrieveAndRemovePendingTransaction(transactionId);
            return null; // Indicate cancellation/failure
        }
    } // End of handleConfirmCount

    /**
     * Initiates the Server -> Client flow. Called by handlers like
     * LoginHandler, SendMessageHandler (forwarding), etc. Sends the initial
     * packet, stores pending transaction.
     *
     * @param transactionKey The session key of the client to send to.
     */
    public void initiateServerToClientFlow(String action, JsonObject messageJson, InetAddress clientAddress, int clientPort, String transactionKey) {
        log.info("Initiating Server->Client flow for action '{}' to {}:{}", action, clientAddress.getHostAddress(), clientPort);

        // 1. Generate Transaction ID *before* sending
        String transactionId = sessionManager.generateTransactionId("S2C_" + action);

        // 2. Add transaction ID to the data payload of the message to be sent
        // Ensure the 'data' object exists
        if (!messageJson.has(Constants.KEY_DATA)) {
            messageJson.add(Constants.KEY_DATA, new JsonObject());
        }
        JsonObject dataPayload = messageJson.getAsJsonObject(Constants.KEY_DATA);
        dataPayload.addProperty("transaction_id", transactionId); // Add the ID here

        // 3. Send the modified initial packet
        boolean sent = JsonHelper.sendPacket(socket, clientAddress, clientPort, messageJson, transactionKey, log);
        if (!sent) {
            log.error("Failed to send initial packet for Server->Client flow (action: {}, txId: {}) to {}:{}", action, transactionId, clientAddress.getHostAddress(), clientPort);
            // Cannot proceed with the flow if the initial packet fails
            return;
        }

        // 4. Calculate frequencies on the *sent* JSON string (before encryption)
        // Note: Client will calculate on the *decrypted* string. They should match if encryption/decryption is symmetric.
        String jsonStringToSend = messageJson.toString(); // Or use Gson instance: gson.toJson(messageJson);
        Map<Character, Integer> letterFrequencies = CaesarCipher.countLetterFrequencies(jsonStringToSend);

        // 5. Create and store pending transaction info (using the already generated transactionId)
        PendingMessageInfo pendingInfo = new PendingMessageInfo(
                transactionId, // Use the generated ID
                Direction.SERVER_TO_CLIENT,
                State.WAITING_FOR_CHAR_COUNT, // Server is now waiting for client's CHARACTER_COUNT
                messageJson,
                jsonStringToSend, // Store the string *before* encryption for frequency comparison
                letterFrequencies,
                clientAddress, // Partner is the client
                clientPort,
                transactionKey // Store the key used for this transaction
        );
        sessionManager.storePendingTransaction(pendingInfo);
    }

    /**
     * Handles incoming CHARACTER_COUNT message from a client (part of Server ->
     * Client flow). Expects transaction_id and letter_frequencies within the
     * 'data' object.
     *
     * @param transactionKey The key used to decrypt this CHARACTER_COUNT
     * message (should match the original transaction key).
     */
    public void handleCharacterCount(JsonObject requestJson, InetAddress clientAddress, int clientPort, String transactionKey) {
        // CHARACTER_COUNT data comes from the client, expected within 'data' object
        if (!requestJson.has(Constants.KEY_DATA)) {
            log.warn("Received CHARACTER_COUNT from {}:{} missing 'data' object.", clientAddress.getHostAddress(), clientPort);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CHARACTER_COUNT, Constants.ERROR_MSG_MISSING_FIELD + "'data'", transactionKey);
            return;
        }
        JsonObject data = requestJson.getAsJsonObject(Constants.KEY_DATA);

        if (!data.has("transaction_id") || !data.has(Constants.KEY_LETTER_FREQUENCIES)) {
            log.warn("Received CHARACTER_COUNT from {}:{} missing 'transaction_id' or 'letter_frequencies' field within 'data'.", clientAddress.getHostAddress(), clientPort);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CHARACTER_COUNT, Constants.ERROR_MSG_MISSING_FIELD + "'data.transaction_id' or 'data.letter_frequencies'", transactionKey);
            return;
        }

        String transactionId = data.get("transaction_id").getAsString();
        JsonObject clientFreqJson = data.getAsJsonObject(Constants.KEY_LETTER_FREQUENCIES);

        log.info("Received CHARACTER_COUNT for transaction '{}' from {}:{}", transactionId, clientAddress.getHostAddress(), clientPort);

        PendingMessageInfo pendingInfo = sessionManager.getPendingTransaction(transactionId);

        if (pendingInfo == null) {
            log.warn("No pending transaction found for CHARACTER_COUNT with id '{}' from {}:{}", transactionId, clientAddress.getHostAddress(), clientPort);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CHARACTER_COUNT, Constants.ERROR_MSG_PENDING_ACTION_NOT_FOUND, transactionKey);
            return;
        }

        // Validate the key used for this message matches the stored transaction key
        if (!transactionKey.equals(pendingInfo.getTransactionKey())) {
            log.warn("Key mismatch for CHARACTER_COUNT transaction '{}'. Expected key type matches fixed: {}, Received key type matches fixed: {}",
                    transactionId, pendingInfo.getTransactionKey().equals(Constants.FIXED_LOGIN_KEY_STRING), transactionKey.equals(Constants.FIXED_LOGIN_KEY_STRING));
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CHARACTER_COUNT, "Key mismatch for transaction.", transactionKey);
            return; // Don't remove pending info
        }

        // Validate state and direction
        if (pendingInfo.getDirection() != Direction.SERVER_TO_CLIENT || pendingInfo.getCurrentState() != State.WAITING_FOR_CHAR_COUNT) {
            log.warn("Invalid state ({}) or direction ({}) for CHARACTER_COUNT transaction '{}'", pendingInfo.getCurrentState(), pendingInfo.getDirection(), transactionId);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CHARACTER_COUNT, Constants.ERROR_MSG_INVALID_STATE, transactionKey);
            sessionManager.retrieveAndRemovePendingTransaction(transactionId); // Clean up
            return;
        }

        // Validate sender
        if (!pendingInfo.getPartnerAddress().equals(clientAddress) || pendingInfo.getPartnerPort() != clientPort) {
            log.warn("CHARACTER_COUNT sender {}:{} does not match pending transaction partner {}:{} for id '{}'",
                    clientAddress.getHostAddress(), clientPort, pendingInfo.getPartnerAddress().getHostAddress(), pendingInfo.getPartnerPort(), transactionId);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CHARACTER_COUNT, "Sender mismatch for transaction.", transactionKey);
            return;
        }

        // Compare frequencies
        Map<Character, Integer> clientFrequencies = new HashMap<>();
        try {
            for (Map.Entry<String, JsonElement> entry : clientFreqJson.entrySet()) {
                if (entry.getKey().length() == 1) { // Ensure single character keys
                    clientFrequencies.put(entry.getKey().charAt(0), entry.getValue().getAsInt());
                } else {
                    log.warn("Invalid key '{}' in frequencies map from client for transaction {}", entry.getKey(), transactionId);
                }
            }
        } catch (Exception e) {
            log.error("Error parsing client frequencies for transaction {}: {}", transactionId, e.getMessage());
            sendConfirmCount(clientAddress, clientPort, transactionId, false, transactionKey); // Send confirm=false on parsing error
            pendingInfo.setCurrentState(State.WAITING_FOR_ACK); // Move state forward
            return;
        }

        boolean frequenciesMatch = areFrequenciesEqual(clientFrequencies, pendingInfo.getExpectedLetterFrequencies());

        if (frequenciesMatch) {
            log.info("Frequency check successful for transaction '{}'", transactionId);
            sendConfirmCount(clientAddress, clientPort, transactionId, true, transactionKey);
            pendingInfo.setCurrentState(State.WAITING_FOR_ACK); // Update state: now waiting for client's ACK
        } else {
            log.warn("Frequency mismatch for transaction '{}'. Expected: {}, Received: {}",
                    transactionId, pendingInfo.getExpectedLetterFrequencies(), clientFreqJson);
            sendConfirmCount(clientAddress, clientPort, transactionId, false, transactionKey);
            pendingInfo.setCurrentState(State.WAITING_FOR_ACK); // Update state: still waiting for ACK (even if confirm=false)
        }
    }

    /**
     * Handles incoming ACK message from a client (final step of Server ->
     * Client flow). Expects transaction_id within the 'data' object and status
     * at the top level.
     *
     * @param transactionKey The key used to decrypt this ACK message (should
     * match the original transaction key).
     */
    public void handleClientAck(JsonObject requestJson, InetAddress clientAddress, int clientPort, String transactionKey) {
        // ACK data comes from the client: transaction_id expected in 'data', status at top level
        if (!requestJson.has(Constants.KEY_STATUS)) {
            log.warn("Received ACK from {}:{} missing 'status' field.", clientAddress.getHostAddress(), clientPort);
            return; // Don't send error for ACK
        }
        String status = requestJson.get(Constants.KEY_STATUS).getAsString();

        if (!requestJson.has(Constants.KEY_DATA)) {
            log.warn("Received ACK from {}:{} missing 'data' object.", clientAddress.getHostAddress(), clientPort);
            return; // Don't send error for ACK
        }
        JsonObject data = requestJson.getAsJsonObject(Constants.KEY_DATA);

        if (!data.has("transaction_id")) {
            log.warn("Received ACK from {}:{} missing 'transaction_id' field within 'data'.", clientAddress.getHostAddress(), clientPort);
            return; // Don't send error for ACK
        }
        String transactionId = data.get("transaction_id").getAsString();

        log.info("Received ACK for transaction '{}' from {}:{}. Status: {}", transactionId, clientAddress.getHostAddress(), clientPort, status);

        // Retrieve and remove the transaction
        PendingMessageInfo pendingInfo = sessionManager.retrieveAndRemovePendingTransaction(transactionId);

        if (pendingInfo == null) {
            log.warn("No pending transaction found for ACK with id '{}' from {}:{} (might be duplicate or timed out).", transactionId, clientAddress.getHostAddress(), clientPort);
            return;
        }

        // Validate the key used for this message matches the stored transaction key
        if (!transactionKey.equals(pendingInfo.getTransactionKey())) {
            log.warn("Key mismatch for ACK transaction '{}'. Expected key type matches fixed: {}, Received key type matches fixed: {}",
                    transactionId, pendingInfo.getTransactionKey().equals(Constants.FIXED_LOGIN_KEY_STRING), transactionKey.equals(Constants.FIXED_LOGIN_KEY_STRING));
            // Re-store the pending info as this ACK might be invalid/spoofed
            sessionManager.storePendingTransaction(pendingInfo);
            return;
        }

        // Validate state and direction
        if (pendingInfo.getDirection() != Direction.SERVER_TO_CLIENT || pendingInfo.getCurrentState() != State.WAITING_FOR_ACK) {
            log.warn("Invalid state ({}) or direction ({}) for ACK transaction '{}'. Removing.", pendingInfo.getCurrentState(), pendingInfo.getDirection(), transactionId);
            // Already removed, just log
            return;
        }

        // Validate sender
        if (!pendingInfo.getPartnerAddress().equals(clientAddress) || pendingInfo.getPartnerPort() != clientPort) {
            log.warn("ACK sender {}:{} does not match pending transaction partner {}:{} for id '{}'. Ignoring ACK.",
                    clientAddress.getHostAddress(), clientPort, pendingInfo.getPartnerAddress().getHostAddress(), pendingInfo.getPartnerPort(), transactionId);
            // Re-store the pending info as this ACK might be invalid/spoofed
            sessionManager.storePendingTransaction(pendingInfo);
            return;
        }

        // Log the final status reported by the client
        String clientId = "client " + clientAddress.getHostAddress() + ":" + clientPort; // Use address if chatid not applicable/available
        if (Constants.STATUS_SUCCESS.equals(status)) {
            log.info("Server->Client transaction '{}' (action: '{}') successfully ACKed by {}.",
                    transactionId, pendingInfo.getOriginalAction(), clientId);
        } else {
            log.warn("Server->Client transaction '{}' (action: '{}') ACKed with status '{}' by {}. Message: {}",
                    transactionId, pendingInfo.getOriginalAction(), status, clientId,
                    requestJson.has(Constants.KEY_MESSAGE) ? requestJson.get(Constants.KEY_MESSAGE).getAsString() : "N/A");
        }
        // Transaction is complete.
    }

    // --- Helper Methods for Sending Handshake Packets ---
    public void sendCharacterCount(InetAddress clientAddress, int clientPort, String transactionId, Map<Character, Integer> frequencies, String transactionKey) {
        log.info("Sending CHARACTER_COUNT for transaction '{}' to {}:{}", transactionId, clientAddress.getHostAddress(), clientPort);
        JsonObject data = new JsonObject();
        data.addProperty("transaction_id", transactionId); // Ensure transaction_id is included
        // Also include the original action the client sent, so client can map the response
        PendingMessageInfo originalInfo = sessionManager.getPendingTransaction(transactionId); // Get without removing
        if (originalInfo != null) {
            data.addProperty(Constants.KEY_ORIGINAL_ACTION, originalInfo.getOriginalAction());
        } else {
            log.warn("Cannot include original_action in CHARACTER_COUNT for tx {}: pending info not found", transactionId);
            // Proceeding without original_action might cause issues on client side mapping.
        }
        JsonObject freqJson = new JsonObject();
        for (Map.Entry<Character, Integer> entry : frequencies.entrySet()) {
            freqJson.addProperty(String.valueOf(entry.getKey()), entry.getValue());
        }
        data.add(Constants.KEY_LETTER_FREQUENCIES, freqJson);

        JsonObject replyJson = JsonHelper.createReply(Constants.ACTION_CHARACTER_COUNT, Constants.STATUS_SUCCESS, "Provide confirmation.", data);
        JsonHelper.sendPacket(socket, clientAddress, clientPort, replyJson, transactionKey, log); // Use the provided transactionKey
        log.info("Sent CHARACTER_COUNT for transaction '{}' to {}:{}", transactionId, clientAddress.getHostAddress(), clientPort);
    }

    public void sendConfirmCount(InetAddress clientAddress, int clientPort, String transactionId, boolean confirm, String transactionKey) {
        JsonObject data = new JsonObject();
        data.addProperty("transaction_id", transactionId); // Ensure transaction_id is included
        data.addProperty(Constants.KEY_CONFIRM, confirm);

        JsonObject replyJson = JsonHelper.createReply(Constants.ACTION_CONFIRM_COUNT, Constants.STATUS_SUCCESS,
                confirm ? "Frequencies match. Proceeding." : "Frequency mismatch.", data);
        JsonHelper.sendPacket(socket, clientAddress, clientPort, replyJson, transactionKey, log); // Use the provided transactionKey
        log.info("Sent CONFIRM_COUNT (confirm={}) for transaction '{}' to {}:{}", confirm, transactionId, clientAddress.getHostAddress(), clientPort);
    }

    public void sendAck(InetAddress clientAddress, int clientPort, String transactionId, boolean success, String message, String transactionKey) {
        JsonObject data = new JsonObject();
        data.addProperty("transaction_id", transactionId); // Ensure transaction_id is included
        // Include original action in ACK data for client context? Optional but helpful.
        // We need to get the pending info *before* it's potentially removed in handleConfirmCount if we want the original action here.
        // Let's assume originalInfo might be null here if called after removal.
        PendingMessageInfo originalInfo = sessionManager.getPendingTransaction(transactionId); // Get without removing
        if (originalInfo != null) {
            data.addProperty(Constants.KEY_ORIGINAL_ACTION, originalInfo.getOriginalAction());
        } else {
            // If original info is gone (e.g., called after removal in handleConfirmCount), we might not know the original action.
            log.warn("Original pending info not found when creating ACK for transaction {}", transactionId);
        }

        JsonObject replyJson = JsonHelper.createReply(Constants.ACTION_ACK,
                success ? Constants.STATUS_SUCCESS : (Constants.STATUS_CANCELLED.equals(message) ? Constants.STATUS_CANCELLED : Constants.STATUS_FAILURE), // Adjust status for cancellation
                message, data);

        // *** START MODIFICATION: Add session_key and chatid for successful login ACK ***
        if (success && originalInfo != null && Constants.ACTION_LOGIN.equals(originalInfo.getOriginalAction())) {
            try {
                // Retrieve chatid from the original login request data stored in pendingInfo
                String chatid = originalInfo.getOriginalMessageJson().getAsJsonObject(Constants.KEY_DATA).get(Constants.KEY_CHAT_ID).getAsString();
                // Retrieve the newly generated session key from the session manager
                String newSessionKey = sessionManager.getClientKey(chatid);
                if (newSessionKey != null) {
                    data.addProperty(Constants.KEY_CHAT_ID, chatid); // Add chatid to ACK data
                    data.addProperty(Constants.KEY_SESSION_KEY, newSessionKey); // Add session key to ACK data
                    log.info("Added session_key and chatid to successful login ACK for transaction '{}'", transactionId);
                } else {
                    log.error("Could not retrieve new session key for chatid '{}' when sending login ACK for transaction '{}'. Client will fail.", chatid, transactionId);
                    // Consider changing status back to failure? Or let client handle missing data.
                    // For now, send ACK as success but without keys, client will log error.
                }
            } catch (Exception e) {
                log.error("Error retrieving chatid/sessionKey for login ACK data (Transaction ID: {}): {}", transactionId, e.getMessage(), e);
                // Proceeding without session key in ACK data.
            }
        }
        // *** END MODIFICATION ***

        JsonHelper.sendPacket(socket, clientAddress, clientPort, replyJson, transactionKey, log); // Use the provided transactionKey
        log.info("Sent ACK (success={}) for transaction '{}' to {}:{}", success, transactionId, clientAddress.getHostAddress(), clientPort);
    }

    /**
     * Sends an error reply using the appropriate key (session or fixed).
     */
    public void sendErrorReply(InetAddress clientAddress, int clientPort, String originalAction, String errorMessage, String keyToSendWith) {
        // Avoid sending error replies for certain actions like ACK to prevent loops
        if (Constants.ACTION_ACK.equals(originalAction) || Constants.ACTION_CONFIRM_COUNT.equals(originalAction) || Constants.ACTION_CHARACTER_COUNT.equals(originalAction)) {
            log.info("Suppressing error reply for handshake action: {}", originalAction);
            return;
        }
        JsonObject errorReply = JsonHelper.createErrorReply(originalAction, errorMessage);
        JsonHelper.sendPacket(socket, clientAddress, clientPort, errorReply, keyToSendWith, log);
    }

    // --- Utility Methods ---
    private boolean areFrequenciesEqual(Map<Character, Integer> map1, Map<Character, Integer> map2) {
        if (map1 == null || map2 == null) {
            return map1 == map2;
        }
        return map1.equals(map2); // Use built-in map equality
    }

}
