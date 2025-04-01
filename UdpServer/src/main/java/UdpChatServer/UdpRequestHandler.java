package UdpChatServer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import UdpChatServer.JsonHelper.DecryptedResult;
import UdpChatServer.PendingMessageInfo.Direction;
import UdpChatServer.PendingMessageInfo.State;

public class UdpRequestHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(UdpRequestHandler.class);

    private final DatagramSocket socket;
    private final ExecutorService requestThreadPool; // Pool for handling individual requests
    private final ScheduledExecutorService cleanupExecutor; // Separate executor for cleanup tasks
    private volatile boolean running = true;

    // Handlers for specific initial actions
    private final LoginHandler loginHandler;
    private final SendMessageHandler sendMessageHandler;
    private final CreateRoomHandler createRoomHandler;
    private final RoomMessageHandler roomMessageHandler;

    // Managers and DAOs
    private final ClientSessionManager sessionManager;
    private final RoomManager roomManager;
    private final MessageDAO messageDAO;
    private final UserDAO userDAO;
    private final RoomDAO roomDAO;

    public UdpRequestHandler(int port, ClientSessionManager sessionManager, RoomManager roomManager,
                           UserDAO userDAO, RoomDAO roomDAO, MessageDAO messageDAO) throws SocketException {
        this.socket = new DatagramSocket(port);
        int poolSize = Runtime.getRuntime().availableProcessors();
        this.requestThreadPool = Executors.newFixedThreadPool(poolSize);
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setDaemon(true); // Allow JVM to exit if only this thread is running
            t.setName("Server-Cleanup-Thread");
            return t;
        });
        log.info("UDP Request Handler initialized. Listening on port: {}. Request pool size: {}", port, poolSize);

        // Assign dependencies
        this.sessionManager = sessionManager;
        this.roomManager = roomManager;
        this.userDAO = userDAO;
        this.roomDAO = roomDAO;
        this.messageDAO = messageDAO;

        // Initialize action handlers
        this.loginHandler = new LoginHandler(userDAO, sessionManager, socket, this); // Pass 'this' for sending server-initiated messages
        this.sendMessageHandler = new SendMessageHandler(sessionManager, roomManager, messageDAO, roomDAO, socket, this);
        this.createRoomHandler = new CreateRoomHandler(sessionManager, roomManager, roomDAO, userDAO, socket, this);
        this.roomMessageHandler = new RoomMessageHandler(sessionManager, roomManager, roomDAO, messageDAO, socket, this);

        startCleanupTasks();
    }

    @Override
    public void run() {
        log.info("UDP Request Handler started. Listening for incoming packets...");
        byte[] receiveData = new byte[Constants.MAX_UDP_PACKET_SIZE];

        while (running) {
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            try {
                socket.receive(receivePacket); // Blocking call
                // Create a final copy for the lambda expression
                final DatagramPacket packetCopy = new DatagramPacket(
                    receivePacket.getData().clone(), // Clone data to avoid race conditions
                    receivePacket.getOffset(),
                    receivePacket.getLength(),
                    receivePacket.getAddress(),
                    receivePacket.getPort()
                );
                // Submit processing to the thread pool
                requestThreadPool.submit(() -> processPacket(packetCopy));
            } catch (SocketException se) {
                if (running) {
                    log.error("SocketException in main listener loop (socket closed?): {}", se.getMessage());
                    running = false; // Stop the loop if socket is unexpectedly closed
                } else {
                    log.info("Socket closed normally during shutdown.");
                }
            } catch (IOException e) {
                if (running) {
                    log.error("IOException receiving packet: {}", e.getMessage(), e);
                    // Potentially recoverable, continue loop unless severe
                }
            } catch (Exception e) {
                // Catch unexpected errors to prevent the listener thread from dying
                if (running) {
                    log.error("Unexpected error in UDP listener loop: {}", e.getMessage(), e);
                }
            }
        }
        log.info("UDP Request Handler listener loop stopped.");
    }

    /**
     * Processes a single received UDP packet.
     * Handles decryption, action routing, and state management for the new flow.
     */
    private void processPacket(DatagramPacket packet) {
        InetAddress clientAddress = packet.getAddress();
        int clientPort = packet.getPort();
        log.debug("Processing packet from {}:{}", clientAddress.getHostAddress(), clientPort);

        DecryptedResult decryptedResult = null;
        String sessionKey = null;
        String action = null;
        String chatid = null; // Chat ID from the packet, if available

        // --- Decryption ---
        // 1. Try session key based on address
        sessionKey = sessionManager.getClientKeyByAddress(clientAddress, clientPort);
        if (sessionKey != null) {
            decryptedResult = JsonHelper.decryptAndParse(packet, sessionKey, log);
            if (decryptedResult != null) {
                 log.trace("Successfully decrypted with session key for {}:{}", clientAddress.getHostAddress(), clientPort);
            } else {
                log.warn("Failed decryption with session key for {}:{}, trying fixed key.", clientAddress.getHostAddress(), clientPort);
                sessionKey = null; // Reset key as it failed
            }
        }

        // 2. Try fixed key if session key failed or wasn't found
        if (decryptedResult == null) {
            decryptedResult = JsonHelper.decryptAndParse(packet, Constants.FIXED_LOGIN_KEY_STRING, log);
            if (decryptedResult != null) {
                log.trace("Successfully decrypted with fixed key for {}:{}", clientAddress.getHostAddress(), clientPort);
                // Use fixed key only for login action validation later
            } else {
                log.error("Failed to decrypt packet from {}:{} with any key.", clientAddress.getHostAddress(), clientPort);
                // Cannot reliably send error back as we don't know which key (if any) client expects
                return; // Stop processing this packet
            }
        }

        // --- Basic Validation and Action Extraction ---
        JsonObject requestJson = decryptedResult.jsonObject;
        String decryptedJsonString = decryptedResult.decryptedJsonString;

        if (!requestJson.has(Constants.KEY_ACTION)) {
            log.error("Decrypted packet from {}:{} is missing 'action' field. Content: {}", clientAddress.getHostAddress(), clientPort, decryptedJsonString);
            sendErrorReply(clientAddress, clientPort, "unknown", Constants.ERROR_MSG_MISSING_FIELD + Constants.KEY_ACTION, sessionKey != null ? sessionKey : Constants.FIXED_LOGIN_KEY_STRING);
            return;
        }
        action = requestJson.get(Constants.KEY_ACTION).getAsString();

        // --- Action Routing and State Management ---
        // chatid will be extracted later depending on the action type
        try {
            // Handle specific handshake actions first
            // Also handle terminal actions (ACK, ERROR) that don't start new flows
            switch (action) {
                case Constants.ACTION_CHARACTER_COUNT: // Received from Client (response to Server-initiated message)
                    handleCharacterCount(requestJson, clientAddress, clientPort, sessionKey);
                    return; // Handled

                case Constants.ACTION_CONFIRM_COUNT: // Received from Client (response to Server-sent CHARACTER_COUNT)
                    handleConfirmCount(requestJson, clientAddress, clientPort, sessionKey, chatid);
                    return; // Handled

                case Constants.ACTION_ACK: // Received from Client (response to Server-sent CONFIRM_COUNT)
                    handleClientAck(requestJson, clientAddress, clientPort, sessionKey, chatid);
                    return; // Handled

                case Constants.ACTION_ERROR: // Received from Client? Should not happen often, but log it.
                    log.warn("Received ERROR action from client {}:{}. Message: {}",
                             clientAddress.getHostAddress(), clientPort,
                             requestJson.has(Constants.KEY_MESSAGE) ? requestJson.get(Constants.KEY_MESSAGE).getAsString() : "N/A");
                    // Do not initiate any flow back.
                    return; // Handled (by logging)
            }

            // --- Handle Initial Client Requests ---
            // These actions start the Client -> Server flow

            // Special case: Login uses fixed key initially
            if (Constants.ACTION_LOGIN.equals(action)) {
                if (sessionKey != null) { // Login should NOT use an existing session key
                    log.warn("Login attempt from {}:{} using an existing session key. Denying.", clientAddress.getHostAddress(), clientPort);
                    sendErrorReply(clientAddress, clientPort, action, "Login cannot use existing session key.", Constants.FIXED_LOGIN_KEY_STRING);
                    return;
                }
                // Proceed with login using fixed key
                loginHandler.handleLogin(packet, requestJson); // Login handler will now initiate the new flow if successful
                return;
            }

            // --- All other initial client requests require a valid session key ---
            if (sessionKey == null) {
                log.warn("Received action '{}' from {}:{} without a valid session key (and not login). Denying.", action, clientAddress.getHostAddress(), clientPort);
                // Cannot reliably send error without knowing the key
                return;
            }

            // Extract chatid from the 'data' object for these actions
            if (!requestJson.has(Constants.KEY_DATA) || !requestJson.getAsJsonObject(Constants.KEY_DATA).has(Constants.KEY_CHAT_ID)) {
                log.warn("Action '{}' from {}:{} requires '{}' field within the 'data' object. Denying.", action, clientAddress.getHostAddress(), clientPort, Constants.KEY_CHAT_ID);
                sendErrorReply(clientAddress, clientPort, action, Constants.ERROR_MSG_MISSING_FIELD + "'data." + Constants.KEY_CHAT_ID + "'", sessionKey);
                return;
            }
            // Extract chatid from data object
            chatid = requestJson.getAsJsonObject(Constants.KEY_DATA).get(Constants.KEY_CHAT_ID).getAsString();

            // Validate session using the extracted chatid
            if (!sessionManager.validateSession(chatid, clientAddress, clientPort)) {
                 log.warn("Session validation failed for action '{}'. Packet chatid '{}' from {}:{} does not match session owner or address/port.", action, chatid, clientAddress.getHostAddress(), clientPort);
                 sendErrorReply(clientAddress, clientPort, action, "Session validation failed.", sessionKey);
                 return;
            }

            // Initiate the Client -> Server flow: Calculate frequencies and send CHARACTER_COUNT back
            // Pass the correctly extracted chatid
            initiateClientToServerFlow(action, requestJson, decryptedJsonString, clientAddress, clientPort, sessionKey, chatid);

        } catch (JsonSyntaxException e) {
             log.error("Invalid JSON syntax in packet from {}:{}: {}. Content: {}", clientAddress.getHostAddress(), clientPort, e.getMessage(), decryptedJsonString);
             // Try sending error with the key we used for decryption
             sendErrorReply(clientAddress, clientPort, action != null ? action : "unknown", Constants.ERROR_MSG_INVALID_JSON, sessionKey != null ? sessionKey : Constants.FIXED_LOGIN_KEY_STRING);
        } catch (Exception e) {
            log.error("Error processing action '{}' from {}:{}: {}",
                      action != null ? action : "unknown", clientAddress.getHostAddress(), clientPort, e.getMessage(), e);
            // Try sending error with the key we used for decryption
            sendErrorReply(clientAddress, clientPort, action != null ? action : "unknown", Constants.ERROR_MSG_INTERNAL_SERVER_ERROR, sessionKey != null ? sessionKey : Constants.FIXED_LOGIN_KEY_STRING);
        }
    }

    /**
     * Initiates the Client -> Server flow upon receiving an initial request.
     * Calculates frequencies, stores pending transaction, sends CHARACTER_COUNT to client.
     */
    private void initiateClientToServerFlow(String originalAction, JsonObject requestJson, String decryptedJsonString,
                                           InetAddress clientAddress, int clientPort, String sessionKey, String chatid) {
        log.debug("Initiating Client->Server flow for action '{}' from {}", originalAction, chatid);

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
            sessionKey
        );
        sessionManager.storePendingTransaction(pendingInfo);

        // 3. Send CHARACTER_COUNT back to the client
        sendCharacterCount(clientAddress, clientPort, transactionId, letterFrequencies, sessionKey);
    }

    /**
     * Handles incoming CONFIRM_COUNT message from a client (part of Client -> Server flow).
     * Expects transaction_id and confirm within the 'data' object.
     */
    private void handleConfirmCount(JsonObject requestJson, InetAddress clientAddress, int clientPort, String sessionKey, String chatid) {
        // CONFIRM_COUNT data comes from the client, expected within 'data' object
        if (!requestJson.has(Constants.KEY_DATA)) {
            log.warn("Received CONFIRM_COUNT from {}:{} missing 'data' object.", clientAddress.getHostAddress(), clientPort);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, Constants.ERROR_MSG_MISSING_FIELD + "'data'", sessionKey);
            return;
        }
        JsonObject data = requestJson.getAsJsonObject(Constants.KEY_DATA);

        if (!data.has("transaction_id") || !data.has(Constants.KEY_CONFIRM)) {
            log.warn("Received CONFIRM_COUNT from {}:{} missing 'transaction_id' or 'confirm' field within 'data'.", clientAddress.getHostAddress(), clientPort);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, Constants.ERROR_MSG_MISSING_FIELD + "'data.transaction_id' or 'data.confirm'", sessionKey);
            return;
        }

        String transactionId = data.get("transaction_id").getAsString();
        boolean confirmed = data.get(Constants.KEY_CONFIRM).getAsBoolean();

        log.debug("Received CONFIRM_COUNT for transaction '{}' from {}:{}. Confirmed: {}", transactionId, clientAddress.getHostAddress(), clientPort, confirmed);

        // Retrieve the pending transaction *without removing it yet*
        PendingMessageInfo pendingInfo = sessionManager.getPendingTransaction(transactionId);

        if (pendingInfo == null) {
            log.warn("No pending transaction found for CONFIRM_COUNT with id '{}' from {}:{}", transactionId, clientAddress.getHostAddress(), clientPort);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, Constants.ERROR_MSG_PENDING_ACTION_NOT_FOUND, sessionKey);
            return;
        }

        // Validate state and direction
        if (pendingInfo.getDirection() != Direction.CLIENT_TO_SERVER || pendingInfo.getCurrentState() != State.WAITING_FOR_CONFIRM) {
             log.warn("Invalid state ({}) or direction ({}) for CONFIRM_COUNT transaction '{}'", pendingInfo.getCurrentState(), pendingInfo.getDirection(), transactionId);
             sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, Constants.ERROR_MSG_INVALID_STATE, sessionKey);
             // Optionally remove the invalid state transaction
             sessionManager.retrieveAndRemovePendingTransaction(transactionId);
             return;
        }

        // Validate sender matches original transaction partner
        if (!pendingInfo.getPartnerAddress().equals(clientAddress) || pendingInfo.getPartnerPort() != clientPort) {
             log.warn("CONFIRM_COUNT sender {}:{} does not match pending transaction partner {}:{} for id '{}'",
                      clientAddress.getHostAddress(), clientPort, pendingInfo.getPartnerAddress().getHostAddress(), pendingInfo.getPartnerPort(), transactionId);
             sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, "Sender mismatch for transaction.", sessionKey);
             return;
        }

        // Process based on confirmation
        if (confirmed) {
            // --- Execute the original action ---
            String originalAction = pendingInfo.getOriginalAction();
            log.info("Processing confirmed action '{}' for transaction '{}'", originalAction, transactionId);
            boolean actionSuccess = false;
            try {
                switch (originalAction) {
                    case Constants.ACTION_SEND_MESSAGE:
                        actionSuccess = sendMessageHandler.processConfirmedSendMessage(pendingInfo);
                        break;
                    case Constants.ACTION_CREATE_ROOM:
                        actionSuccess = createRoomHandler.processConfirmedCreateRoom(pendingInfo);
                        break;
                    // Add cases for other client-initiated actions that need confirmation
                    case Constants.ACTION_GET_ROOMS: // Example if get_rooms needed confirmation (it doesn't currently)
                       actionSuccess = roomMessageHandler.processConfirmedGetRooms(pendingInfo);
                       break;
                    case Constants.ACTION_GET_MESSAGES: // Example if get_messages needed confirmation (it doesn't currently)
                       actionSuccess = roomMessageHandler.processConfirmedGetMessages(pendingInfo);
                       break;
                    default:
                        log.error("No handler defined for confirmed action '{}' in transaction '{}'", originalAction, transactionId);
                        // Send ACK with failure status
                        sendAck(clientAddress, clientPort, transactionId, false, "Unhandled confirmed action", sessionKey);
                        sessionManager.retrieveAndRemovePendingTransaction(transactionId); // Clean up
                        return;
                }
            } catch (Exception e) {
                 log.error("Error executing confirmed action '{}' for transaction '{}': {}", originalAction, transactionId, e.getMessage(), e);
                 actionSuccess = false;
            }

            // Send ACK based on action execution result
            sendAck(clientAddress, clientPort, transactionId, actionSuccess,
                    actionSuccess ? "Action processed successfully." : "Action processing failed.", sessionKey);

        } else {
            // Client cancelled
            log.info("Client cancelled action '{}' for transaction '{}'", pendingInfo.getOriginalAction(), transactionId);
            sendAck(clientAddress, clientPort, transactionId, false, "Action cancelled by client.", sessionKey);
        }

        // Remove the completed or cancelled transaction
        sessionManager.retrieveAndRemovePendingTransaction(transactionId);
    }


    /**
     * Initiates the Server -> Client flow. Called by handlers like LoginHandler, SendMessageHandler (forwarding), etc.
     * Sends the initial packet, stores pending transaction.
     */
    public void initiateServerToClientFlow(String action, JsonObject messageJson, InetAddress clientAddress, int clientPort, String sessionKey) {
        log.debug("Initiating Server->Client flow for action '{}' to {}:{}", action, clientAddress.getHostAddress(), clientPort);

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
        boolean sent = JsonHelper.sendPacket(socket, clientAddress, clientPort, messageJson, sessionKey, log);
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
            sessionKey
        );
        sessionManager.storePendingTransaction(pendingInfo);
    }

    /**
     * Handles incoming CHARACTER_COUNT message from a client (part of Server -> Client flow).
     * Expects transaction_id and letter_frequencies within the 'data' object.
     */
    private void handleCharacterCount(JsonObject requestJson, InetAddress clientAddress, int clientPort, String sessionKey) {
         // CHARACTER_COUNT data comes from the client, expected within 'data' object
         if (!requestJson.has(Constants.KEY_DATA)) {
            log.warn("Received CHARACTER_COUNT from {}:{} missing 'data' object.", clientAddress.getHostAddress(), clientPort);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CHARACTER_COUNT, Constants.ERROR_MSG_MISSING_FIELD + "'data'", sessionKey);
            return;
        }
        JsonObject data = requestJson.getAsJsonObject(Constants.KEY_DATA);

         if (!data.has("transaction_id") || !data.has(Constants.KEY_LETTER_FREQUENCIES)) {
            log.warn("Received CHARACTER_COUNT from {}:{} missing 'transaction_id' or 'letter_frequencies' field within 'data'.", clientAddress.getHostAddress(), clientPort);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CHARACTER_COUNT, Constants.ERROR_MSG_MISSING_FIELD + "'data.transaction_id' or 'data.letter_frequencies'", sessionKey);
            return;
        }

        String transactionId = data.get("transaction_id").getAsString();
        JsonObject clientFreqJson = data.getAsJsonObject(Constants.KEY_LETTER_FREQUENCIES);

        log.debug("Received CHARACTER_COUNT for transaction '{}' from {}:{}", transactionId, clientAddress.getHostAddress(), clientPort);

        PendingMessageInfo pendingInfo = sessionManager.getPendingTransaction(transactionId);

        if (pendingInfo == null) {
            log.warn("No pending transaction found for CHARACTER_COUNT with id '{}' from {}:{}", transactionId, clientAddress.getHostAddress(), clientPort);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CHARACTER_COUNT, Constants.ERROR_MSG_PENDING_ACTION_NOT_FOUND, sessionKey);
            return;
        }

        // Validate state and direction
        if (pendingInfo.getDirection() != Direction.SERVER_TO_CLIENT || pendingInfo.getCurrentState() != State.WAITING_FOR_CHAR_COUNT) {
             log.warn("Invalid state ({}) or direction ({}) for CHARACTER_COUNT transaction '{}'", pendingInfo.getCurrentState(), pendingInfo.getDirection(), transactionId);
             sendErrorReply(clientAddress, clientPort, Constants.ACTION_CHARACTER_COUNT, Constants.ERROR_MSG_INVALID_STATE, sessionKey);
             sessionManager.retrieveAndRemovePendingTransaction(transactionId); // Clean up
             return;
        }

        // Validate sender
        if (!pendingInfo.getPartnerAddress().equals(clientAddress) || pendingInfo.getPartnerPort() != clientPort) {
             log.warn("CHARACTER_COUNT sender {}:{} does not match pending transaction partner {}:{} for id '{}'",
                      clientAddress.getHostAddress(), clientPort, pendingInfo.getPartnerAddress().getHostAddress(), pendingInfo.getPartnerPort(), transactionId);
             sendErrorReply(clientAddress, clientPort, Constants.ACTION_CHARACTER_COUNT, "Sender mismatch for transaction.", sessionKey);
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
             sendConfirmCount(clientAddress, clientPort, transactionId, false, sessionKey); // Send confirm=false on parsing error
             pendingInfo.setCurrentState(State.WAITING_FOR_ACK); // Move state forward
             return;
        }


        boolean frequenciesMatch = areFrequenciesEqual(clientFrequencies, pendingInfo.getExpectedLetterFrequencies());

        if (frequenciesMatch) {
            log.debug("Frequency check successful for transaction '{}'", transactionId);
            sendConfirmCount(clientAddress, clientPort, transactionId, true, sessionKey);
            pendingInfo.setCurrentState(State.WAITING_FOR_ACK); // Update state: now waiting for client's ACK
        } else {
            log.warn("Frequency mismatch for transaction '{}'. Expected: {}, Received: {}",
                     transactionId, pendingInfo.getExpectedLetterFrequencies(), clientFreqJson);
            sendConfirmCount(clientAddress, clientPort, transactionId, false, sessionKey);
            pendingInfo.setCurrentState(State.WAITING_FOR_ACK); // Update state: still waiting for ACK (even if confirm=false)
        }
    }

    /**
     * Handles incoming ACK message from a client (final step of Server -> Client flow).
     * Expects transaction_id within the 'data' object and status at the top level.
     */
    private void handleClientAck(JsonObject requestJson, InetAddress clientAddress, int clientPort, String sessionKey, String chatid) {
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

        log.debug("Received ACK for transaction '{}' from {}:{}. Status: {}", transactionId, clientAddress.getHostAddress(), clientPort, status);

        // Retrieve and remove the transaction
        PendingMessageInfo pendingInfo = sessionManager.retrieveAndRemovePendingTransaction(transactionId);

        if (pendingInfo == null) {
            log.warn("No pending transaction found for ACK with id '{}' from {}:{} (might be duplicate or timed out).", transactionId, clientAddress.getHostAddress(), clientPort);
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
             // Put the transaction back? Or just ignore the ACK? Ignoring is simpler.
             // sessionManager.storePendingTransaction(pendingInfo); // Re-store if ignoring ACK
             return;
        }

        // Log the final status reported by the client
        if (Constants.STATUS_SUCCESS.equals(status)) {
            log.info("Server->Client transaction '{}' (action: '{}') successfully ACKed by client {}.",
                     transactionId, pendingInfo.getOriginalAction(), chatid);
        } else {
            log.warn("Server->Client transaction '{}' (action: '{}') ACKed with status '{}' by client {}. Message: {}",
                     transactionId, pendingInfo.getOriginalAction(), status, chatid,
                     requestJson.has(Constants.KEY_MESSAGE) ? requestJson.get(Constants.KEY_MESSAGE).getAsString() : "N/A");
        }
        // Transaction is complete.
    }


    // --- Helper Methods for Sending Handshake Packets ---

    private void sendCharacterCount(InetAddress clientAddress, int clientPort, String transactionId, Map<Character, Integer> frequencies, String sessionKey) {
        JsonObject data = new JsonObject();
        data.addProperty("transaction_id", transactionId); // Ensure transaction_id is included
        // Also include the original action the client sent, so client can map the response
        PendingMessageInfo originalInfo = sessionManager.getPendingTransaction(transactionId);
        if (originalInfo != null) {
             data.addProperty(Constants.KEY_ORIGINAL_ACTION, originalInfo.getOriginalAction());
        } else {
             log.warn("Cannot include original_action in CHARACTER_COUNT for tx {}: pending info not found", transactionId);
             // Proceed without original_action? Or send error? Sending without is problematic for client.
             // Let's assume for now the info should always be there.
        }
        JsonObject freqJson = new JsonObject();
        for (Map.Entry<Character, Integer> entry : frequencies.entrySet()) {
            freqJson.addProperty(String.valueOf(entry.getKey()), entry.getValue());
        }
        data.add(Constants.KEY_LETTER_FREQUENCIES, freqJson);

        JsonObject replyJson = JsonHelper.createReply(Constants.ACTION_CHARACTER_COUNT, Constants.STATUS_SUCCESS, "Provide confirmation.", data);
        JsonHelper.sendPacket(socket, clientAddress, clientPort, replyJson, sessionKey, log);
        log.debug("Sent CHARACTER_COUNT for transaction '{}' to {}:{}", transactionId, clientAddress.getHostAddress(), clientPort);
    }

    private void sendConfirmCount(InetAddress clientAddress, int clientPort, String transactionId, boolean confirm, String sessionKey) {
        JsonObject data = new JsonObject();
        data.addProperty("transaction_id", transactionId); // Ensure transaction_id is included
        data.addProperty(Constants.KEY_CONFIRM, confirm);

        JsonObject replyJson = JsonHelper.createReply(Constants.ACTION_CONFIRM_COUNT, Constants.STATUS_SUCCESS,
                                                     confirm ? "Frequencies match. Proceeding." : "Frequency mismatch.", data);
        JsonHelper.sendPacket(socket, clientAddress, clientPort, replyJson, sessionKey, log);
        log.debug("Sent CONFIRM_COUNT (confirm={}) for transaction '{}' to {}:{}", confirm, transactionId, clientAddress.getHostAddress(), clientPort);
    }

    private void sendAck(InetAddress clientAddress, int clientPort, String transactionId, boolean success, String message, String sessionKey) {
        JsonObject data = new JsonObject();
        data.addProperty("transaction_id", transactionId); // Ensure transaction_id is included
        // Include original action in ACK data for client context? Optional but helpful.
        PendingMessageInfo originalInfo = sessionManager.getPendingTransaction(transactionId); // Need to get it before removing if we want original action
        if(originalInfo != null) {
             data.addProperty(Constants.KEY_ORIGINAL_ACTION, originalInfo.getOriginalAction());
        } else {
             // If original info is gone, we might not know the original action for the ACK data
             log.warn("Original pending info not found when creating ACK for transaction {}", transactionId);
        }


        JsonObject replyJson = JsonHelper.createReply(Constants.ACTION_ACK,
                                                     success ? Constants.STATUS_SUCCESS : Constants.STATUS_FAILURE,
                                                     message, data);
        JsonHelper.sendPacket(socket, clientAddress, clientPort, replyJson, sessionKey, log);
        log.debug("Sent ACK (success={}) for transaction '{}' to {}:{}", success, transactionId, clientAddress.getHostAddress(), clientPort);
    }

    /**
     * Sends an error reply. Tries to use sessionKey if available, otherwise falls back to fixed key.
     */
    private void sendErrorReply(InetAddress clientAddress, int clientPort, String originalAction, String errorMessage, String sessionKey) {
        String keyToSendWith = (sessionKey != null) ? sessionKey : Constants.FIXED_LOGIN_KEY_STRING;
        // Avoid sending error replies for certain actions like ACK to prevent loops
        if (Constants.ACTION_ACK.equals(originalAction)) {
            log.debug("Suppressing error reply for ACK action.");
            return;
        }
        JsonObject errorReply = JsonHelper.createErrorReply(originalAction, errorMessage);
        JsonHelper.sendPacket(socket, clientAddress, clientPort, errorReply, keyToSendWith, log);
    }

    // --- Utility Methods ---

    private boolean areFrequenciesEqual(Map<Character, Integer> map1, Map<Character, Integer> map2) {
        if (map1 == null || map2 == null) return map1 == map2;
        return map1.equals(map2); // Use built-in map equality
    }

    // --- Lifecycle Methods ---

    private void startCleanupTasks() {
        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                log.trace("Running cleanup tasks..."); // Use trace for frequent tasks
                sessionManager.cleanupInactiveSessionsAndTransactions(
                    Constants.SESSION_MAX_INACTIVE_INTERVAL_MS,
                    Constants.PENDING_MESSAGE_TIMEOUT_MS
                );
                log.trace("Cleanup tasks finished.");
            } catch (Exception e) {
                log.error("Error during scheduled cleanup tasks: {}", e.getMessage(), e);
            }
        }, Constants.SESSION_CLEANUP_INTERVAL_MS, Constants.SESSION_CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("Scheduled cleanup tasks started (Session Interval: {} ms, Transaction Timeout: {} ms)",
                 Constants.SESSION_CLEANUP_INTERVAL_MS, Constants.PENDING_MESSAGE_TIMEOUT_MS);
    }

    public void stop() {
        if (!running) return; // Prevent multiple shutdowns
        running = false;
        log.info("Stopping UDP Request Handler...");

        // Shutdown cleanup executor first
        shutdownExecutor(cleanupExecutor, "CleanupExecutor");

        // Close the socket to interrupt the blocking receive() call
        if (socket != null && !socket.isClosed()) {
            log.debug("Closing UDP socket...");
            socket.close();
        }

        // Shutdown the request processing pool
        shutdownExecutor(requestThreadPool, "RequestThreadPool");

        log.info("UDP Request Handler stopped.");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null && !executor.isShutdown()) {
            log.info("Shutting down {}...", name);
            executor.shutdown(); // Disable new tasks from being submitted
            try {
                // Wait a while for existing tasks to terminate
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("{} did not terminate in 10 seconds. Forcing shutdown...", name);
                    executor.shutdownNow(); // Cancel currently executing tasks
                    // Wait a while for tasks to respond to being cancelled
                    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                        log.error("{} did not terminate even after forcing.", name);
                    }
                }
                log.info("{} shut down.", name);
            } catch (InterruptedException ie) {
                log.error("Interrupted while shutting down {}. Forcing shutdown.", name, ie);
                executor.shutdownNow(); // Re-cancel if current thread was interrupted
                Thread.currentThread().interrupt(); // Preserve interrupt status
            }
        }
    }
}
