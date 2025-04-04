package UdpChatClient;

import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class HandshakeManager {
    private static final Logger log = LoggerFactory.getLogger(HandshakeManager.class);
    private static final Gson gson = new Gson(); // For converting frequency map

    private final ClientState clientState;
    private final MessageProcessor messageProcessor; // To process confirmed server actions

    // --- State Management for Handshake ---
    // Key: Client-generated temporary UUID for C->S flow
    private final ConcurrentHashMap<String, ClientPendingRequest> pendingClientRequestsByTempId = new ConcurrentHashMap<>();
    // Key: Server-generated transactionId for C->S flow (used after CHARACTER_COUNT is received)
    private final ConcurrentHashMap<String, ClientPendingRequest> pendingClientRequestsByServerId = new ConcurrentHashMap<>();
    // Key: Server-generated transactionId for S->C flow
    private final ConcurrentHashMap<String, String> pendingServerActionsJson = new ConcurrentHashMap<>();

    // Inner class to hold pending request state
    private static class ClientPendingRequest {
        final String originalAction;
        final String originalSentJson;
        final CountDownLatch latch;
        JsonObject ackData; // Stores the final ACK or ERROR response
        String serverTransactionId; // Set when CHARACTER_COUNT is received

        ClientPendingRequest(String action, String sentJson) {
            this.originalAction = action;
            this.originalSentJson = sentJson;
            this.latch = new CountDownLatch(1);
        }
    }

    public HandshakeManager(ClientState clientState, MessageProcessor messageProcessor) {
        this.clientState = clientState;
        this.messageProcessor = messageProcessor;
    }

    // --- Handling Incoming Handshake Messages ---

    public void handleCharacterCountResponse(JsonObject response, InetAddress serverAddress, int serverPort) {
        if (!response.has(Constants.KEY_DATA)) {
            log.error("Received CHARACTER_COUNT missing 'data' object.");
            return;
        }
        JsonObject data = response.getAsJsonObject(Constants.KEY_DATA);

        if (!data.has("transaction_id") || !data.has(Constants.KEY_LETTER_FREQUENCIES) || !data.has(Constants.KEY_ORIGINAL_ACTION)) {
            log.error("Received CHARACTER_COUNT missing transaction_id, frequencies, or original_action within 'data'.");
            return;
        }
        String transactionId = data.get("transaction_id").getAsString();
        String originalAction = data.get(Constants.KEY_ORIGINAL_ACTION).getAsString();
        JsonObject serverFrequenciesJson = data.getAsJsonObject(Constants.KEY_LETTER_FREQUENCIES);
        log.info("Received CHARACTER_COUNT for original action '{}', server tx ID: {}", originalAction, transactionId);

        ClientPendingRequest pendingReq = null;
        String tempIdToRemove = null;
        for (Map.Entry<String, ClientPendingRequest> entry : pendingClientRequestsByTempId.entrySet()) {
            if (entry.getValue().originalAction.equals(originalAction) && entry.getValue().serverTransactionId == null) {
                pendingReq = entry.getValue();
                tempIdToRemove = entry.getKey();
                log.info("Found matching pending request (TempID: {}) for original action {}", tempIdToRemove, originalAction);
                break;
            }
        }

        if (pendingReq == null) {
            log.warn("Received CHARACTER_COUNT for original action '{}', but no matching pending request found or it was already processed (Server TxID: {}).", originalAction, transactionId);
            return;
        }

        pendingReq.serverTransactionId = transactionId;
        if (tempIdToRemove != null) {
            pendingClientRequestsByTempId.remove(tempIdToRemove);
        } else {
             log.warn("Could not find tempIdToRemove while processing CHARACTER_COUNT for tx {}", transactionId);
        }
        pendingClientRequestsByServerId.put(transactionId, pendingReq);
        log.info("Associated server tx ID {} with pending action {} (TempID: {})", transactionId, originalAction, tempIdToRemove);

        Map<Character, Integer> clientCalculatedFrequencies = CaesarCipher.countLetterFrequencies(pendingReq.originalSentJson);
        Map<Character, Integer> serverFrequencies = parseFrequencyJson(serverFrequenciesJson);
        boolean isValid = areFrequenciesEqual(clientCalculatedFrequencies, serverFrequencies);

        if (!isValid) {
            log.warn("Frequency check failed for transaction: {}. Client: {}, Server: {}",
                     transactionId, clientCalculatedFrequencies, serverFrequencies);
        } else {
             log.info("Frequency check successful for transaction: {}", transactionId);
        }

        JsonObject confirmData = new JsonObject();
        confirmData.addProperty("transaction_id", transactionId);
        confirmData.addProperty(Constants.KEY_CONFIRM, isValid);

        JsonObject confirmRequest = JsonHelper.createRequest(Constants.ACTION_CONFIRM_COUNT, confirmData);
        String key = clientState.getSessionKey() != null ? clientState.getSessionKey() : Constants.FIXED_LOGIN_KEY_STRING;
        JsonHelper.sendPacket(clientState.getSocket(), serverAddress, serverPort, confirmRequest, key, log);
        log.info("Sent CONFIRM_COUNT (confirmed: {}) for transaction: {}", isValid, transactionId);
    }

    public void handleConfirmCountResponse(JsonObject response, InetAddress serverAddress, int serverPort) {
        if (!response.has(Constants.KEY_DATA)) {
            log.error("Received CONFIRM_COUNT missing 'data' object.");
            return;
        }
        JsonObject data = response.getAsJsonObject(Constants.KEY_DATA);

        if (!data.has("transaction_id") || !data.has(Constants.KEY_CONFIRM)) {
            log.error("Received CONFIRM_COUNT missing 'transaction_id' or 'confirm' field within 'data'.");
            return;
        }
        String transactionId = data.get("transaction_id").getAsString();
        boolean confirmed = data.get(Constants.KEY_CONFIRM).getAsBoolean();
        log.info("Received CONFIRM_COUNT for transaction: {} (confirmed: {})", transactionId, confirmed);

        String pendingJson = pendingServerActionsJson.remove(transactionId);
        if (pendingJson == null) {
            log.warn("No pending server action found for transaction: {}", transactionId);
        }

        String ackStatus = Constants.STATUS_FAILURE;
        String ackMessage = null;

        if (confirmed) {
            if (pendingJson != null) {
                // Delegate processing to MessageProcessor
                messageProcessor.processServerAction(pendingJson);
                ackStatus = Constants.STATUS_SUCCESS;
            } else {
                ackMessage = "Client lost original action state.";
                log.warn("Cannot process action for transaction {} because pending JSON was lost.", transactionId);
            }
        } else {
            ackStatus = Constants.STATUS_CANCELLED;
            ackMessage = "Frequency mismatch detected by server.";
            log.warn("Server indicated frequency mismatch for transaction: {}, not processing", transactionId);
        }
        sendAck(transactionId, ackStatus, ackMessage, serverAddress, serverPort);
    }

    public void handleServerAck(JsonObject responseJson) {
        if (!responseJson.has(Constants.KEY_STATUS)) {
            log.error("Received ACK missing status field.");
            return;
        }
        String status = responseJson.get(Constants.KEY_STATUS).getAsString();

        if (!responseJson.has(Constants.KEY_DATA)) {
            log.error("Received ACK missing 'data' object.");
            return;
        }
        JsonObject data = responseJson.getAsJsonObject(Constants.KEY_DATA);

        if (!data.has("transaction_id")) {
            log.error("Received ACK missing 'transaction_id' field within 'data'.");
            return;
        }
        String transactionId = data.get("transaction_id").getAsString();
        String originalAction = data.has(Constants.KEY_ORIGINAL_ACTION) ? data.get(Constants.KEY_ORIGINAL_ACTION).getAsString() : "unknown";
        log.info("Received Server ACK for transaction: {} (Original Action: {}) with status: {}", transactionId, originalAction, status);

        ClientPendingRequest pendingReq = pendingClientRequestsByServerId.remove(transactionId);

        if (pendingReq != null) {
            pendingReq.ackData = responseJson; // Store the full ACK response

            if (Constants.ACTION_LOGIN.equals(originalAction)) {
                if (Constants.STATUS_SUCCESS.equals(status)) {
                    if (data.has(Constants.KEY_SESSION_KEY) && data.has(Constants.KEY_CHAT_ID)) {
                        clientState.setSessionKey(data.get(Constants.KEY_SESSION_KEY).getAsString());
                        clientState.setCurrentChatId(data.get(Constants.KEY_CHAT_ID).getAsString());
                        log.info("Login successful via ACK! Updated sessionKey for user '{}'. Session: {}", clientState.getCurrentChatId(), clientState.getSessionKey());
                        System.out.println("\nLogin successful! Welcome " + clientState.getCurrentChatId() + ".");
                        System.out.println("Type /help");
                    } else {
                        log.error("Login ACK successful but missing session_key or chatid in data!");
                        System.out.println("\nLogin successful, but server response was incomplete. Please try again.");
                    }
                } else {
                    String message = responseJson.has(Constants.KEY_MESSAGE) ? responseJson.get(Constants.KEY_MESSAGE).getAsString() : "Unknown reason";
                    log.warn("Login failed via ACK. Status: {}, Message: {}", status, message);
                    System.out.println("\nLogin failed: " + message + " (Status: " + status + ")");
                }
                pendingReq.latch.countDown();
                log.info("Signaled completion for pending login request associated with transaction {}", transactionId);

            } else {
                // Handle ACK for other actions
                pendingReq.latch.countDown();
                log.info("Signaled completion for pending request (Action: {}) associated with transaction {}", originalAction, transactionId);
            }
        } else {
            log.warn("Received ACK for unknown, timed-out, or already processed transaction: {}", transactionId);
        }
    }

    public void handleServerError(JsonObject responseJson) {
        String errorMessage = responseJson.has(Constants.KEY_MESSAGE) ? responseJson.get(Constants.KEY_MESSAGE).getAsString() : "Unknown server error";
        String originalAction = responseJson.has(Constants.KEY_ORIGINAL_ACTION) ? responseJson.get(Constants.KEY_ORIGINAL_ACTION).getAsString() : "unknown";
        log.error("Received ERROR from server for action '{}': {}", originalAction, errorMessage);
        System.out.println("\nServer Error (" + originalAction + "): " + errorMessage);

        ClientPendingRequest pendingReqToFail = null;
        String tempIdToFail = null;
        String serverIdToFail = null;

        if (responseJson.has(Constants.KEY_DATA)) {
            JsonObject data = responseJson.getAsJsonObject(Constants.KEY_DATA);
            if (data.has("transaction_id")) {
                serverIdToFail = data.get("transaction_id").getAsString();
                pendingReqToFail = pendingClientRequestsByServerId.get(serverIdToFail);
            }
        }

        if (pendingReqToFail == null) {
            for (Map.Entry<String, ClientPendingRequest> entry : pendingClientRequestsByTempId.entrySet()) {
                 if (entry.getValue().originalAction.equals(originalAction)) {
                     pendingReqToFail = entry.getValue();
                     tempIdToFail = entry.getKey();
                     serverIdToFail = pendingReqToFail.serverTransactionId;
                     break;
                 }
            }
        }

        if (pendingReqToFail != null) {
             log.warn("Signaling failure for pending action {} due to server error.", originalAction);
             pendingReqToFail.ackData = responseJson; // Store error info
             pendingReqToFail.latch.countDown(); // Signal completion (as failure)
             if (tempIdToFail != null) pendingClientRequestsByTempId.remove(tempIdToFail);
             if (serverIdToFail != null) pendingClientRequestsByServerId.remove(serverIdToFail);
        } else {
             log.warn("Could not find pending request for action '{}' to signal server error.", originalAction);
        }
        System.out.print("> ");
    }

    // --- Handling Server-Initiated Actions (S->C Flow) ---

    public void handleInitialServerAction(String decryptedJsonString, JsonObject responseJson, InetAddress serverAddress, int serverPort) {
        log.info("Received initial action '{}' from server, starting S->C flow", responseJson.get(Constants.KEY_ACTION).getAsString());
        String transactionId = null;
        if (responseJson.has(Constants.KEY_DATA)) {
            JsonObject data = responseJson.getAsJsonObject(Constants.KEY_DATA);
            if (data.has("transaction_id")) {
                transactionId = data.get("transaction_id").getAsString();
            }
        }

        if (transactionId == null) {
            log.error("Server message action '{}' missing 'transaction_id'. Cannot proceed.", responseJson.get(Constants.KEY_ACTION).getAsString());
            return;
        }

        pendingServerActionsJson.put(transactionId, decryptedJsonString);
        sendCharacterCount(decryptedJsonString, transactionId, serverAddress, serverPort);
    }

    // --- Sending Handshake Messages ---

    private void sendCharacterCount(String receivedJsonString, String transactionId, InetAddress serverAddress, int serverPort) {
        try {
            Map<Character, Integer> freqMap = CaesarCipher.countLetterFrequencies(receivedJsonString);
            JsonObject frequenciesJson = new JsonObject();
            for (Map.Entry<Character, Integer> entry : freqMap.entrySet()) {
                frequenciesJson.addProperty(String.valueOf(entry.getKey()), entry.getValue());
            }
            JsonObject data = new JsonObject();
            data.addProperty("transaction_id", transactionId);
            data.add(Constants.KEY_LETTER_FREQUENCIES, frequenciesJson);
            JsonObject request = JsonHelper.createRequest(Constants.ACTION_CHARACTER_COUNT, data);
            // Use sessionKey if available, otherwise fixed key (should only be null for S->C before login)
            String key = clientState.getSessionKey() != null ? clientState.getSessionKey() : Constants.FIXED_LOGIN_KEY_STRING;
            JsonHelper.sendPacket(clientState.getSocket(), serverAddress, serverPort, request, key, log);
            log.info("Sent CHARACTER_COUNT for server-initiated transaction: {}", transactionId);
        } catch (Exception e) {
            log.error("Error sending CHARACTER_COUNT for transaction {}: {}", transactionId, e.getMessage(), e);
        }
    }

    private void sendAck(String transactionId, String status, String message, InetAddress serverAddress, int serverPort) {
        try {
            JsonObject data = new JsonObject();
            data.addProperty("transaction_id", transactionId);
            JsonObject request = JsonHelper.createReply(Constants.ACTION_ACK, status, message, data);
            // Use sessionKey if available
            String key = clientState.getSessionKey() != null ? clientState.getSessionKey() : Constants.FIXED_LOGIN_KEY_STRING;
            JsonHelper.sendPacket(clientState.getSocket(), serverAddress, serverPort, request, key, log);
            log.info("Sent ACK for transaction: {} with status: {}", transactionId, status);
        } catch (Exception e) {
             log.error("Error sending ACK for transaction {}: {}", transactionId, e.getMessage(), e);
        }
    }

    // --- Sending Client-Initiated Requests with Handshake ---

    public void sendClientRequestWithAck(JsonObject request, String action, String encryptionKey) {
        String tempId = UUID.randomUUID().toString();
        System.out.println("\nSending action: " + action + " (TempID: " + tempId + ")");
        String jsonToSend = gson.toJson(request);
        System.out.println("---------------------"+jsonToSend);
        ClientPendingRequest pendingReq = new ClientPendingRequest(action, jsonToSend);
        pendingClientRequestsByTempId.put(tempId, pendingReq);

        try {
            JsonHelper.sendPacket(clientState.getSocket(), clientState.getServerAddress(), clientState.getServerPort(), request, encryptionKey, log);
            log.info("Sent action: {} (TempID: {}) - waiting for server CHARACTER_COUNT...", action, tempId);

            boolean completed = pendingReq.latch.await(15, TimeUnit.SECONDS);

            pendingClientRequestsByTempId.remove(tempId);
            if (pendingReq.serverTransactionId != null) {
                 pendingClientRequestsByServerId.remove(pendingReq.serverTransactionId);
            }

            if (!completed) {
                log.warn("Timeout waiting for server ACK for action: {} (TempID: {})", action, tempId);
                System.out.println("\nRequest timed out. Server did not respond.");
            } else {
                JsonObject ackResponse = pendingReq.ackData;
                if (ackResponse != null && ackResponse.has(Constants.KEY_STATUS)) {
                    String status = ackResponse.get(Constants.KEY_STATUS).getAsString();
                    if (!Constants.STATUS_SUCCESS.equals(status)) {
                        String serverMessage = ackResponse.has(Constants.KEY_MESSAGE) ? ackResponse.get(Constants.KEY_MESSAGE).getAsString() : "No details";
                        log.warn("Action {} (TempID: {}) failed on server. Status: {}, Message: {}", action, tempId, status, serverMessage);
                        // Display error, but don't re-process login failure here (handled in handleServerAck)
                        if (!action.equals(Constants.ACTION_LOGIN)) {
                             System.out.println("\nServer couldn't process request: " + serverMessage + " (Status: " + status + ")");
                        }
                    } else {
                        log.info("Action {} (TempID: {}) acknowledged successfully by server.", action, tempId);
                        // Specific success messages for non-login actions
                        if (action.equals(Constants.ACTION_SEND_MESSAGE)) System.out.println("\nMessage sent successfully!");
                        else if (action.equals(Constants.ACTION_CREATE_ROOM)) System.out.println("\nRoom creation request acknowledged."); // Room ID comes via S->C flow now
                        // Login success message is handled in handleServerAck
                    }
                } else if (ackResponse != null && ackResponse.has(Constants.KEY_ACTION) && Constants.ACTION_ERROR.equals(ackResponse.get(Constants.KEY_ACTION).getAsString())) {
                    // Error was already logged by handleServerError, just log completion here
                    log.warn("Action {} (TempID: {}) completed with server ERROR.", action, tempId);
                }
                 else {
                     log.error("ACK/ERROR received for action {} (TempID: {}) but status/format missing/invalid.", action, tempId);
                     System.out.println("\nReceived invalid response from server.");
                }
            }
        } catch (InterruptedException e) {
             log.warn("Interrupted waiting for ACK for {} (TempID: {})", action, tempId);
             System.out.println("\nRequest interrupted.");
             pendingClientRequestsByTempId.remove(tempId);
             Thread.currentThread().interrupt();
        } catch (Exception e) {
             log.error("Unexpected error sending {} (TempID: {}): {}", action, tempId, e.getMessage(), e);
             System.out.println("Error: " + e.getMessage());
             pendingClientRequestsByTempId.remove(tempId);
        } finally {
             System.out.print("> ");
        }
    }

    // --- Utility Methods ---

    private Map<Character, Integer> parseFrequencyJson(JsonObject freqJson) {
        Map<Character, Integer> map = new ConcurrentHashMap<>();
        if (freqJson != null) {
            for (Map.Entry<String, JsonElement> entry : freqJson.entrySet()) {
                if (entry.getKey().length() == 1) {
                    try {
                        map.put(entry.getKey().charAt(0), entry.getValue().getAsInt());
                    } catch (Exception e) {
                        log.warn("Invalid frequency value for key '{}': {}", entry.getKey(), entry.getValue());
                    }
                } else {
                     log.warn("Invalid frequency key (not single char): '{}'", entry.getKey());
                }
            }
        }
        return map;
    }

    private boolean areFrequenciesEqual(Map<Character, Integer> map1, Map<Character, Integer> map2) {
        // Ensure null safety and handle empty maps correctly
        if (map1 == null && map2 == null) return true;
        if (map1 == null || map2 == null) return false;
        return map1.equals(map2);
    }

    // Method to clean up pending requests on shutdown (optional but good practice)
    public void shutdown() {
        log.info("Shutting down HandshakeManager, clearing pending requests.");
        pendingClientRequestsByTempId.clear();
        pendingClientRequestsByServerId.clear();
        pendingServerActionsJson.clear();
    }
}
