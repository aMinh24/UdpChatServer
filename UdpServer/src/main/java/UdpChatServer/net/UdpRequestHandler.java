package UdpChatServer.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

// Import DAOs and Managers
import UdpChatServer.db.MessageDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.manager.ClientSessionManager;
import UdpChatServer.manager.RoomManager;
// Import Handlers
import UdpChatServer.handler.*; // Import all handlers
// Import Models and Utils
import UdpChatServer.model.Constants;
import UdpChatServer.model.PendingMessageInfo;
import UdpChatServer.util.JsonHelper;
import UdpChatServer.util.JsonHelper.DecryptedResult;

public class UdpRequestHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(UdpRequestHandler.class);

    private final DatagramSocket socket;
    private final ExecutorService requestThreadPool;
    private final ScheduledExecutorService cleanupExecutor;
    private volatile boolean running = true;

    // Managers and DAOs (passed in constructor)
    private final ClientSessionManager sessionManager;
    private final RoomManager roomManager;
    private final UserDAO userDAO;
    private final RoomDAO roomDAO;
    private final MessageDAO messageDAO;

    // Handlers (created here)
    private final UdpSender udpSender;
    private final LoginHandler loginHandler;
    private final RegisterHandler registerHandler;
    private final SendMessageHandler sendMessageHandler;
    private final CreateRoomHandler createRoomHandler;
    private final RoomMessageHandler roomMessageHandler;
    private final GetUsersHandler getUsersHandler;
    private final RoomManagementHandler roomManagementHandler;


    public UdpRequestHandler(int port, ClientSessionManager sessionManager, RoomManager roomManager,
            UserDAO userDAO, RoomDAO roomDAO, MessageDAO messageDAO) throws SocketException {
        this.socket = new DatagramSocket(port);
        this.sessionManager = sessionManager;
        this.roomManager = roomManager;
        this.userDAO = userDAO;
        this.roomDAO = roomDAO;
        this.messageDAO = messageDAO;

        // Initialize UdpSender with its required dependencies
        this.udpSender = new UdpSender(this.socket, this.sessionManager);

        // Initialize all handlers, passing dependencies (including udpSender)
        this.loginHandler = new LoginHandler(this.userDAO, this.sessionManager, this.udpSender);
        this.registerHandler = new RegisterHandler(this.userDAO, this.udpSender);
        this.sendMessageHandler = new SendMessageHandler(this.sessionManager, this.roomManager, this.messageDAO, this.roomDAO, this.udpSender);
        this.createRoomHandler = new CreateRoomHandler(this.sessionManager, this.roomManager, this.roomDAO, this.userDAO, this.socket); // Needs socket? Check handler impl. Assuming yes for now.
        this.roomMessageHandler = new RoomMessageHandler(this.sessionManager, this.roomManager, this.roomDAO, this.messageDAO, this.socket, this.udpSender); // Needs socket? Check handler impl. Assuming yes for now.
        this.getUsersHandler = new GetUsersHandler(this.sessionManager, this.userDAO, this.udpSender);
        this.roomManagementHandler = new RoomManagementHandler(this.sessionManager, this.roomManager, this.roomDAO, this.udpSender);


        // Initialize thread pools
        int poolSize = Runtime.getRuntime().availableProcessors();
        this.requestThreadPool = Executors.newFixedThreadPool(poolSize);
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setDaemon(true);
            t.setName("Server-Cleanup-Thread");
            return t;
        });
        log.info("UDP Request Handler initialized. Listening on port: {}. Request pool size: {}", port, poolSize);

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
                final DatagramPacket packetCopy = new DatagramPacket(
                        receivePacket.getData().clone(),
                        receivePacket.getOffset(),
                        receivePacket.getLength(),
                        receivePacket.getAddress(),
                        receivePacket.getPort()
                );
                requestThreadPool.submit(() -> processPacket(packetCopy));
            } catch (SocketException se) {
                if (running) {
                    log.error("SocketException in main listener loop (socket closed?): {}", se.getMessage());
                    running = false;
                } else {
                    log.info("Socket closed normally during shutdown.");
                }
            } catch (IOException e) {
                if (running) {
                    log.error("IOException receiving packet: {}", e.getMessage(), e);
                }
            } catch (Exception e) {
                if (running) {
                    log.error("Unexpected error in UDP listener loop: {}", e.getMessage(), e);
                }
            }
        }
        log.info("UDP Request Handler listener loop stopped.");
    }

    /**
     * Processes a single received UDP packet. Handles decryption, action routing, and state management.
     */
    private void processPacket(DatagramPacket packet) {
        InetAddress clientAddress = packet.getAddress();
        int clientPort = packet.getPort();
        log.info("Processing packet from {}:{}", clientAddress.getHostAddress(), clientPort);

        DecryptedResult decryptedResult = null;
        String sessionKey = null;
        String action = null;

        // --- Decryption ---
        sessionKey = sessionManager.getClientKeyByAddress(clientAddress, clientPort);
        if (sessionKey != null) {
            decryptedResult = JsonHelper.decryptAndParse(packet, sessionKey, log);
            if (decryptedResult == null) {
                log.warn("Failed decryption with session key for {}:{}, trying fixed key.", clientAddress.getHostAddress(), clientPort);
                sessionKey = null; // Reset key
            }
        }
        if (decryptedResult == null) {
            decryptedResult = JsonHelper.decryptAndParse(packet, Constants.FIXED_LOGIN_KEY_STRING, log);
            if (decryptedResult == null) {
                log.error("Failed to decrypt packet from {}:{} with any key.", clientAddress.getHostAddress(), clientPort);
                return;
            }
        }

        // --- Basic Validation and Action Extraction ---
        JsonObject requestJson = decryptedResult.jsonObject;
        String decryptedJsonString = decryptedResult.decryptedJsonString;
        String transactionKey = (sessionKey != null) ? sessionKey : Constants.FIXED_LOGIN_KEY_STRING;

        if (!requestJson.has(Constants.KEY_ACTION)) {
            log.error("Decrypted packet from {}:{} is missing 'action' field.", clientAddress.getHostAddress(), clientPort);
            udpSender.sendErrorReply(clientAddress, clientPort, "unknown", Constants.ERROR_MSG_MISSING_FIELD + Constants.KEY_ACTION, transactionKey);
            return;
        }
        action = requestJson.get(Constants.KEY_ACTION).getAsString();

        // --- Action Routing ---
        try {
            switch (action) {
                // --- Handshake/Flow Control Actions (Handled by UdpSender) ---
                case Constants.ACTION_CHARACTER_COUNT:
                    udpSender.handleCharacterCount(requestJson, clientAddress, clientPort, transactionKey);
                    break;
                case Constants.ACTION_CONFIRM_COUNT:
                    // UdpSender validates and returns PendingMessageInfo if confirmed=true
                    PendingMessageInfo confirmedInfo = udpSender.handleConfirmCount(requestJson, clientAddress, clientPort, transactionKey);
                    if (confirmedInfo != null) {
                        // If confirmation is valid, process the original action
                        processConfirmedAction(confirmedInfo);
                    }
                    // If null, it means confirmation failed or client cancelled; UdpSender already handled ACK/cleanup.
                    break;
                case Constants.ACTION_ACK:
                    udpSender.handleClientAck(requestJson, clientAddress, clientPort, transactionKey);
                    break;
                case Constants.ACTION_ERROR:
                    log.warn("Received ERROR action from client {}:{}. Message: {}",
                            clientAddress.getHostAddress(), clientPort,
                            requestJson.has(Constants.KEY_MESSAGE) ? requestJson.get(Constants.KEY_MESSAGE).getAsString() : "N/A");
                    break;

                // --- Initial Client Requests (Start C2S Flow) ---
                case Constants.ACTION_LOGIN:
                case Constants.ACTION_REGISTER:
                    if (!transactionKey.equals(Constants.FIXED_LOGIN_KEY_STRING)) {
                        log.warn("Action '{}' from {}:{} was not decrypted with the fixed key. Denying.", action, clientAddress.getHostAddress(), clientPort);
                        udpSender.sendErrorReply(clientAddress, clientPort, action, "Action requires fixed key.", Constants.FIXED_LOGIN_KEY_STRING);
                        return;
                    }
                    udpSender.initiateClientToServerFlow(action, requestJson, decryptedJsonString, clientAddress, clientPort, Constants.FIXED_LOGIN_KEY_STRING);
                    break;

                default: // All other initial actions require a valid session
                    if (sessionKey == null) {
                        log.warn("Received action '{}' from {}:{} without a valid session key. Denying.", action, clientAddress.getHostAddress(), clientPort);
                        if (transactionKey.equals(Constants.FIXED_LOGIN_KEY_STRING)) {
                            udpSender.sendErrorReply(clientAddress, clientPort, action, Constants.ERROR_MSG_NOT_LOGGED_IN, Constants.FIXED_LOGIN_KEY_STRING);
                        }
                        return;
                    }
                    // Extract chatid and validate session for other actions
                    if (!requestJson.has(Constants.KEY_DATA) || !requestJson.getAsJsonObject(Constants.KEY_DATA).has(Constants.KEY_CHAT_ID)) {
                        log.warn("Action '{}' from {}:{} requires '{}' field within 'data'. Denying.", action, clientAddress.getHostAddress(), clientPort, Constants.KEY_CHAT_ID);
                        udpSender.sendErrorReply(clientAddress, clientPort, action, Constants.ERROR_MSG_MISSING_FIELD + "'data." + Constants.KEY_CHAT_ID + "'", sessionKey);
                        return;
                    }
                    String chatid = requestJson.getAsJsonObject(Constants.KEY_DATA).get(Constants.KEY_CHAT_ID).getAsString();
                    if (!sessionManager.validateSession(chatid, clientAddress, clientPort)) {
                        log.warn("Session validation failed for action '{}'. Packet chatid '{}' from {}:{} does not match.", action, chatid, clientAddress.getHostAddress(), clientPort);
                        udpSender.sendErrorReply(clientAddress, clientPort, action, "Session validation failed.", sessionKey);
                        return;
                    }
                    String expectedKey = sessionManager.getClientKey(chatid);
                    if (!sessionKey.equals(expectedKey)) {
                         log.warn("Session key mismatch for action '{}' from chatid '{}'. Denying.", action, chatid);
                         udpSender.sendErrorReply(clientAddress, clientPort, action, Constants.ERROR_MSG_DECRYPTION_FAILED, sessionKey);
                         return;
                    }
                    // Initiate C2S flow for valid session actions
                    udpSender.initiateClientToServerFlow(action, requestJson, decryptedJsonString, clientAddress, clientPort, sessionKey);
                    break;
            }
        } catch (JsonSyntaxException e) {
            log.error("Invalid JSON syntax in packet from {}:{}: {}. Content: {}", clientAddress.getHostAddress(), clientPort, e.getMessage(), decryptedJsonString);
            udpSender.sendErrorReply(clientAddress, clientPort, action != null ? action : "unknown", Constants.ERROR_MSG_INVALID_JSON, transactionKey);
        } catch (Exception e) {
            log.error("Error processing action '{}' from {}:{}: {}",
                    action != null ? action : "unknown", clientAddress.getHostAddress(), clientPort, e.getMessage(), e);
            udpSender.sendErrorReply(clientAddress, clientPort, action != null ? action : "unknown", Constants.ERROR_MSG_INTERNAL_SERVER_ERROR, transactionKey);
        }
    }

    /**
     * Executes the confirmed action based on the original request stored in PendingMessageInfo.
     * Sends an ACK back to the client.
     * Removes the pending transaction.
     *
     * @param pendingInfo The validated PendingMessageInfo returned by udpSender.handleConfirmCount.
     */
    private void processConfirmedAction(PendingMessageInfo pendingInfo) {
        String originalAction = pendingInfo.getOriginalAction();
        String transactionId = pendingInfo.getTransactionId();
        InetAddress clientAddress = pendingInfo.getPartnerAddress();
        int clientPort = pendingInfo.getPartnerPort();
        String transactionKey = pendingInfo.getTransactionKey(); // Use the key stored in pending info

        log.info("Processing confirmed action '{}' for transaction '{}'", originalAction, transactionId);
        boolean actionSuccess = false;
        try {
            // Delegate to the appropriate handler
            actionSuccess = switch (originalAction) {
                case Constants.ACTION_LOGIN -> loginHandler.processConfirmedLogin(pendingInfo);
                case Constants.ACTION_REGISTER -> registerHandler.processConfirmedRegister(pendingInfo);
                case Constants.ACTION_SEND_MESSAGE -> sendMessageHandler.processConfirmedSendMessage(pendingInfo);
                case Constants.ACTION_CREATE_ROOM -> createRoomHandler.processConfirmedCreateRoom(pendingInfo);
                case Constants.ACTION_GET_ROOMS -> roomMessageHandler.processConfirmedGetRooms(pendingInfo);
                case Constants.ACTION_GET_MESSAGES -> roomMessageHandler.processConfirmedGetMessages(pendingInfo);
                case Constants.ACTION_GET_USERS -> getUsersHandler.processConfirmedGetUsers(pendingInfo);
                // Room Management Actions
                case Constants.ACTION_ADD_USER_TO_ROOM -> roomManagementHandler.processConfirmedAddUserToRoom(pendingInfo);
                case Constants.ACTION_REMOVE_USER_FROM_ROOM -> roomManagementHandler.processConfirmedRemoveUserFromRoom(pendingInfo);
                case Constants.ACTION_DELETE_ROOM -> roomManagementHandler.processConfirmedDeleteRoom(pendingInfo);
                case Constants.ACTION_RENAME_ROOM -> roomManagementHandler.processConfirmedRenameRoom(pendingInfo);
                default -> {
                    log.error("No handler defined for confirmed action '{}' in transaction '{}'", originalAction, transactionId);
                    yield false; // Indicate failure for the default case
                }
            };
        } catch (Exception e) {
            log.error("Error executing confirmed action '{}' for transaction '{}': {}", originalAction, transactionId, e.getMessage(), e);
            actionSuccess = false; // Ensure actionSuccess is false on exception
        }

        // Send ACK based on action execution result using the correct key
        udpSender.sendAck(clientAddress, clientPort, transactionId, actionSuccess,
                actionSuccess ? "Action processed successfully." : "Action processing failed.", transactionKey);

        // Remove the completed transaction
        sessionManager.retrieveAndRemovePendingTransaction(transactionId);
        log.info("Completed processing and removed transaction '{}'", transactionId);
    }


    // --- Lifecycle Methods ---
    private void startCleanupTasks() {
        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                log.trace("Running cleanup tasks...");
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
        if (!running) return;
        running = false;
        log.info("Stopping UDP Request Handler...");
        shutdownExecutor(cleanupExecutor, "CleanupExecutor");
        if (socket != null && !socket.isClosed()) {
            log.info("Closing UDP socket...");
            socket.close();
        }
        shutdownExecutor(requestThreadPool, "RequestThreadPool");
        log.info("UDP Request Handler stopped.");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null && !executor.isShutdown()) {
            log.info("Shutting down {}...", name);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("{} did not terminate in 10 seconds. Forcing shutdown...", name);
                    executor.shutdownNow();
                    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                        log.error("{} did not terminate even after forcing.", name);
                    }
                }
                log.info("{} shut down.", name);
            } catch (InterruptedException ie) {
                log.error("Interrupted while shutting down {}. Forcing shutdown.", name, ie);
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
