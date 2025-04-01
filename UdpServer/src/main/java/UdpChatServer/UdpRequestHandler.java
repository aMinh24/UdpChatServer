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
    @SuppressWarnings("unused") private final RoomManager roomManager; // Keep if needed later
    @SuppressWarnings("unused") private final MessageDAO messageDAO; // Keep if needed later
    @SuppressWarnings("unused") private final UserDAO userDAO; // Keep if needed later
    @SuppressWarnings("unused") private final RoomDAO roomDAO; // Keep if needed later

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
        this.roomManager = roomManager; // Assign even if unused for now
        this.userDAO = userDAO;         // Assign even if unused for now
        this.roomDAO = roomDAO;         // Assign even if unused for now
        this.messageDAO = messageDAO;   // Assign even if unused for now

        // Initialize action handlers
        this.loginHandler = new LoginHandler(userDAO, sessionManager, this); // Pass 'this', socket removed as LoginHandler won't send directly
        this.sendMessageHandler = new SendMessageHandler(sessionManager, roomManager, messageDAO, roomDAO, this); // Pass 'this', socket removed
        this.createRoomHandler = new CreateRoomHandler(sessionManager, roomManager, roomDAO, userDAO, this.socket, this); // Pass 'this' and socket
        this.roomMessageHandler = new RoomMessageHandler(sessionManager, roomManager, roomDAO, messageDAO, this.socket, this); // Pass 'this' and socket

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
        log.info("Processing packet from {}:{}", clientAddress.getHostAddress(), clientPort);

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
        String transactionKey = (sessionKey != null) ? sessionKey : Constants.FIXED_LOGIN_KEY_STRING; // Determine the key used for decryption, declare outside try
        try {
            // Handle specific handshake actions first
            // Also handle terminal actions (ACK, ERROR) that don't start new flows
            switch (action) {
                case Constants.ACTION_CHARACTER_COUNT -> { // Received from Client (response to Server-initiated message)
                    handleCharacterCount(requestJson, clientAddress, clientPort, transactionKey); // Pass the determined key
                    return; // Handled
                }
                case Constants.ACTION_CONFIRM_COUNT -> { // Received from Client (response to Server-sent CHARACTER_COUNT)
                    // chatid is not available/relevant at this top level for CONFIRM_COUNT, it's retrieved from pendingInfo inside the handler
                    handleConfirmCount(requestJson, clientAddress, clientPort, transactionKey); // Pass the determined key
                    return; // Handled
                }
                case Constants.ACTION_ACK -> { // Received from Client (response to Server-sent CONFIRM_COUNT)
                    // chatid is not available/relevant at this top level for ACK, it's retrieved from pendingInfo inside the handler
                    handleClientAck(requestJson, clientAddress, clientPort, transactionKey); // Pass the determined key
                    return; // Handled
                }
                case Constants.ACTION_ERROR -> { // Received from Client? Should not happen often, but log it.
                    log.warn("Received ERROR action from client {}:{}. Message: {}",
                            clientAddress.getHostAddress(), clientPort,
                            requestJson.has(Constants.KEY_MESSAGE) ? requestJson.get(Constants.KEY_MESSAGE).getAsString() : "N/A");
                    // Do not initiate any flow back.
                    return; // Handled (by logging)
                }
            }

            // --- Handle Initial Client Requests ---
            // These actions start the Client -> Server flow

            // Special case: Login uses fixed key initially and starts C2S flow
            if (Constants.ACTION_LOGIN.equals(action)) {
                // transactionKey should be FIXED_LOGIN_KEY_STRING if decryption succeeded with it
                if (!transactionKey.equals(Constants.FIXED_LOGIN_KEY_STRING)) {
                    log.warn("Login attempt from {}:{} was not decrypted with the fixed key. Denying.", clientAddress.getHostAddress(), clientPort);
                    sendErrorReply(clientAddress, clientPort, action, "Login must use fixed key.", Constants.FIXED_LOGIN_KEY_STRING); // Send error with fixed key
                    return;
                }
                // Initiate C2S flow for login using the fixed key
                log.info("Initiating C2S flow for LOGIN action from {}:{}", clientAddress.getHostAddress(), clientPort);
                // Pass null for chatid as it's not known/validated yet
                initiateClientToServerFlow(action, requestJson, decryptedJsonString, clientAddress, clientPort, Constants.FIXED_LOGIN_KEY_STRING); // Removed chatid param
                return;
            }

            // --- All other initial client requests require a valid session key ---
            // If sessionKey is null here, it means decryption failed with session key OR fixed key was used for non-login action
            if (sessionKey == null) {
                log.warn("Received action '{}' from {}:{} without a valid session key (or used fixed key incorrectly). Denying.", action, clientAddress.getHostAddress(), clientPort);
                // Cannot reliably send error without knowing the key if sessionKey was null initially
                if (transactionKey.equals(Constants.FIXED_LOGIN_KEY_STRING)) { // If fixed key was used incorrectly
                     sendErrorReply(clientAddress, clientPort, action, Constants.ERROR_MSG_NOT_LOGGED_IN, Constants.FIXED_LOGIN_KEY_STRING);
                }
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
            // Validate session using the extracted chatid AND the key used for decryption (which must be the sessionKey at this point)
            if (!sessionManager.validateSession(chatid, clientAddress, clientPort)) {
                 log.warn("Session validation failed for action '{}'. Packet chatid '{}' from {}:{} does not match session owner or address/port.", action, chatid, clientAddress.getHostAddress(), clientPort);
                 sendErrorReply(clientAddress, clientPort, action, "Session validation failed.", sessionKey); // Use sessionKey for error
                 return;
            }
             // Additional check: Ensure the key used for decryption matches the session's key
            String expectedKey = sessionManager.getClientKey(chatid);
            if (!sessionKey.equals(expectedKey)) {
                 log.warn("Session key mismatch for action '{}' from chatid '{}'. Packet decrypted with different key than expected. Denying.", action, chatid);
                 sendErrorReply(clientAddress, clientPort, action, Constants.ERROR_MSG_DECRYPTION_FAILED, sessionKey); // Use sessionKey for error
                 return;
            }

            // Initiate the Client -> Server flow: Calculate frequencies and send CHARACTER_COUNT back
            initiateClientToServerFlow(action, requestJson, decryptedJsonString, clientAddress, clientPort, sessionKey); // Removed chatid param

        } catch (JsonSyntaxException e) {
             log.error("Invalid JSON syntax in packet from {}:{}: {}. Content: {}", clientAddress.getHostAddress(), clientPort, e.getMessage(), decryptedJsonString);
             sendErrorReply(clientAddress, clientPort, action != null ? action : "unknown", Constants.ERROR_MSG_INVALID_JSON, transactionKey); // Use the key determined earlier
        } catch (Exception e) {
            log.error("Error processing action '{}' from {}:{}: {}",
                      action != null ? action : "unknown", clientAddress.getHostAddress(), clientPort, e.getMessage(), e);
             sendErrorReply(clientAddress, clientPort, action != null ? action : "unknown", Constants.ERROR_MSG_INTERNAL_SERVER_ERROR, transactionKey); // Use the key determined earlier
        }
    } // End of processPacket method brace - Ensure this brace is correctly placed

    /**
     * Initiates the Client -> Server flow upon receiving an initial request (like send_message, create_room, or now login).
     * Calculates frequencies, stores pending transaction, sends CHARACTER_COUNT to client.
     * @param transactionKey The key used for this transaction (session or fixed).
     */
    private void initiateClientToServerFlow(String originalAction, JsonObject requestJson, String decryptedJsonString,
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
     * Handles incoming CONFIRM_COUNT message from a client (part of Client -> Server flow).
     * Expects transaction_id and confirm within the 'data' object.
     * @param transactionKey The key used to decrypt this CONFIRM_COUNT message (should match the original transaction key).
     */
    private void handleConfirmCount(JsonObject requestJson, InetAddress clientAddress, int clientPort, String transactionKey) {
        // CONFIRM_COUNT data comes from the client, expected within 'data' object
        if (!requestJson.has(Constants.KEY_DATA)) {
            log.warn("Received CONFIRM_COUNT from {}:{} missing 'data' object.", clientAddress.getHostAddress(), clientPort);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, Constants.ERROR_MSG_MISSING_FIELD + "'data'", transactionKey);
            return;
        }
        JsonObject data = requestJson.getAsJsonObject(Constants.KEY_DATA);

        if (!data.has("transaction_id") || !data.has(Constants.KEY_CONFIRM)) {
            log.warn("Received CONFIRM_COUNT from {}:{} missing 'transaction_id' or 'confirm' field within 'data'.", clientAddress.getHostAddress(), clientPort);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, Constants.ERROR_MSG_MISSING_FIELD + "'data.transaction_id' or 'data.confirm'", transactionKey);
            return;
        }

        String transactionId = data.get("transaction_id").getAsString();
        boolean confirmed = data.get(Constants.KEY_CONFIRM).getAsBoolean();

        log.info("Received CONFIRM_COUNT for transaction '{}' from {}:{}. Confirmed: {}", transactionId, clientAddress.getHostAddress(), clientPort, confirmed);

        // Retrieve the pending transaction *without removing it yet*
        PendingMessageInfo pendingInfo = sessionManager.getPendingTransaction(transactionId);

        if (pendingInfo == null) {
            log.warn("No pending transaction found for CONFIRM_COUNT with id '{}' from {}:{}", transactionId, clientAddress.getHostAddress(), clientPort);
            sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, Constants.ERROR_MSG_PENDING_ACTION_NOT_FOUND, transactionKey);
            return;
        }

        // Validate the key used for this message matches the stored transaction key
        if (!transactionKey.equals(pendingInfo.getTransactionKey())) {
             log.warn("Key mismatch for CONFIRM_COUNT transaction '{}'. Expected key type matches fixed: {}, Received key type matches fixed: {}",
                      transactionId, pendingInfo.getTransactionKey().equals(Constants.FIXED_LOGIN_KEY_STRING), transactionKey.equals(Constants.FIXED_LOGIN_KEY_STRING));
             sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, "Key mismatch for transaction.", transactionKey);
             // Do not remove pending info, might be a spoof attempt or delayed packet with old key
             return;
        }

        // Validate state and direction
        if (pendingInfo.getDirection() != Direction.CLIENT_TO_SERVER || pendingInfo.getCurrentState() != State.WAITING_FOR_CONFIRM) {
             log.warn("Invalid state ({}) or direction ({}) for CONFIRM_COUNT transaction '{}'", pendingInfo.getCurrentState(), pendingInfo.getDirection(), transactionId);
             sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, Constants.ERROR_MSG_INVALID_STATE, transactionKey);
             // Optionally remove the invalid state transaction
             sessionManager.retrieveAndRemovePendingTransaction(transactionId);
             return;
        }

        // Validate sender matches original transaction partner
        if (!pendingInfo.getPartnerAddress().equals(clientAddress) || pendingInfo.getPartnerPort() != clientPort) {
             log.warn("CONFIRM_COUNT sender {}:{} does not match pending transaction partner {}:{} for id '{}'",
                      clientAddress.getHostAddress(), clientPort, pendingInfo.getPartnerAddress().getHostAddress(), pendingInfo.getPartnerPort(), transactionId);
             sendErrorReply(clientAddress, clientPort, Constants.ACTION_CONFIRM_COUNT, "Sender mismatch for transaction.", transactionKey);
             return;
        }

        // Process based on confirmation
        if (confirmed) {
            // --- Execute the original action ---
            String originalAction = pendingInfo.getOriginalAction();
            log.info("Processing confirmed action '{}' for transaction '{}'", originalAction, transactionId);
            boolean actionSuccess = false;
            try {
                actionSuccess = switch (originalAction) {
                    case Constants.ACTION_LOGIN ->
                        // Note: processConfirmedLogin initiates its own S2C flow for login_success on success
                        loginHandler.processConfirmedLogin(pendingInfo);
                    case Constants.ACTION_SEND_MESSAGE ->
                        sendMessageHandler.processConfirmedSendMessage(pendingInfo);
                    case Constants.ACTION_CREATE_ROOM ->
                        createRoomHandler.processConfirmedCreateRoom(pendingInfo);
                    case Constants.ACTION_GET_ROOMS ->
                        roomMessageHandler.processConfirmedGetRooms(pendingInfo);
                    case Constants.ACTION_GET_MESSAGES ->
                        roomMessageHandler.processConfirmedGetMessages(pendingInfo);
                    default -> {
                        log.error("No handler defined for confirmed action '{}' in transaction '{}'", originalAction, transactionId);
                        // Send ACK with failure status using the correct key
                        sendAck(clientAddress, clientPort, transactionId, false, "Unhandled confirmed action", pendingInfo.getTransactionKey());
                        sessionManager.retrieveAndRemovePendingTransaction(transactionId); // Clean up
                        // Return false from the switch expression block
                        yield false; // Indicate failure for the default case
                    }
                };
            } catch (Exception e) {
                 log.error("Error executing confirmed action '{}' for transaction '{}': {}", originalAction, transactionId, e.getMessage(), e);
                 actionSuccess = false; // Ensure actionSuccess is false on exception
            }

            // Send ACK based on action execution result using the correct key
            sendAck(clientAddress, clientPort, transactionId, actionSuccess,
                    actionSuccess ? "Action processed successfully." : "Action processing failed.", pendingInfo.getTransactionKey());

        } else {
            // Client cancelled
            log.info("Client cancelled action '{}' for transaction '{}'", pendingInfo.getOriginalAction(), transactionId);
            sendAck(clientAddress, clientPort, transactionId, false, "Action cancelled by client.", pendingInfo.getTransactionKey());
        }

        // Remove the completed or cancelled transaction
        sessionManager.retrieveAndRemovePendingTransaction(transactionId);
    }


    /**
     * Initiates the Server -> Client flow. Called by handlers like LoginHandler, SendMessageHandler (forwarding), etc.
     * Sends the initial packet, stores pending transaction.
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
     * Handles incoming CHARACTER_COUNT message from a client (part of Server -> Client flow).
     * Expects transaction_id and letter_frequencies within the 'data' object.
     * @param transactionKey The key used to decrypt this CHARACTER_COUNT message (should match the original transaction key).
     */
    private void handleCharacterCount(JsonObject requestJson, InetAddress clientAddress, int clientPort, String transactionKey) {
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
     * Handles incoming ACK message from a client (final step of Server -> Client flow).
     * Expects transaction_id within the 'data' object and status at the top level.
     * @param transactionKey The key used to decrypt this ACK message (should match the original transaction key).
     */
    private void handleClientAck(JsonObject requestJson, InetAddress clientAddress, int clientPort, String transactionKey) {
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

    private void sendCharacterCount(InetAddress clientAddress, int clientPort, String transactionId, Map<Character, Integer> frequencies, String transactionKey) {
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

    private void sendConfirmCount(InetAddress clientAddress, int clientPort, String transactionId, boolean confirm, String transactionKey) {
        JsonObject data = new JsonObject();
        data.addProperty("transaction_id", transactionId); // Ensure transaction_id is included
        data.addProperty(Constants.KEY_CONFIRM, confirm);

        JsonObject replyJson = JsonHelper.createReply(Constants.ACTION_CONFIRM_COUNT, Constants.STATUS_SUCCESS,
                                                     confirm ? "Frequencies match. Proceeding." : "Frequency mismatch.", data);
        JsonHelper.sendPacket(socket, clientAddress, clientPort, replyJson, transactionKey, log); // Use the provided transactionKey
        log.info("Sent CONFIRM_COUNT (confirm={}) for transaction '{}' to {}:{}", confirm, transactionId, clientAddress.getHostAddress(), clientPort);
    }

    private void sendAck(InetAddress clientAddress, int clientPort, String transactionId, boolean success, String message, String transactionKey) {
        JsonObject data = new JsonObject();
        data.addProperty("transaction_id", transactionId); // Ensure transaction_id is included
        // Include original action in ACK data for client context? Optional but helpful.
        // We need to get the pending info *before* it's potentially removed in handleConfirmCount if we want the original action here.
        // Let's assume originalInfo might be null here if called after removal.
        PendingMessageInfo originalInfo = sessionManager.getPendingTransaction(transactionId); // Get without removing
        if(originalInfo != null) {
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
    private void sendErrorReply(InetAddress clientAddress, int clientPort, String originalAction, String errorMessage, String keyToSendWith) {
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
            log.info("Closing UDP socket...");
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
