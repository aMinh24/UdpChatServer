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

import UdpChatServer.db.MessageDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.manager.ClientSessionManager;
import UdpChatServer.manager.RoomManager;
import UdpChatServer.model.Constants;
import UdpChatServer.util.JsonHelper;
import UdpChatServer.util.JsonHelper.DecryptedResult;

public class UdpRequestHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(UdpRequestHandler.class);

    private final DatagramSocket socket;
    private final ExecutorService requestThreadPool; // Pool for handling individual requests
    private final ScheduledExecutorService cleanupExecutor; // Separate executor for cleanup tasks
    private volatile boolean running = true;

    private final UdpSender udpSender; // UdpSender instance for sending messages back to clients

    private final ClientSessionManager sessionManager;

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

        this.sessionManager = sessionManager;
        this.udpSender = new UdpSender(this.socket, sessionManager, roomManager,
                userDAO, roomDAO, messageDAO);
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
     * Processes a single received UDP packet. Handles decryption, action
     * routing, and state management for the new flow.
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
            udpSender.sendErrorReply(clientAddress, clientPort, "unknown", Constants.ERROR_MSG_MISSING_FIELD + Constants.KEY_ACTION, sessionKey != null ? sessionKey : Constants.FIXED_LOGIN_KEY_STRING);
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
                    udpSender.handleCharacterCount(requestJson, clientAddress, clientPort, transactionKey); // Pass the determined key
                    return; // Handled
                }
                case Constants.ACTION_CONFIRM_COUNT -> { // Received from Client (response to Server-sent CHARACTER_COUNT)
                    // chatid is not available/relevant at this top level for CONFIRM_COUNT, it's retrieved from pendingInfo inside the handler
                    udpSender.handleConfirmCount(requestJson, clientAddress, clientPort, transactionKey); // Pass the determined key
                    return; // Handled
                }
                case Constants.ACTION_ACK -> { // Received from Client (response to Server-sent CONFIRM_COUNT)
                    // chatid is not available/relevant at this top level for ACK, it's retrieved from pendingInfo inside the handler
                    udpSender.handleClientAck(requestJson, clientAddress, clientPort, transactionKey); // Pass the determined key
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
            if (Constants.ACTION_LOGIN.equals(action) || Constants.ACTION_REGISTER.equals(action)) {
                // transactionKey should be FIXED_LOGIN_KEY_STRING if decryption succeeded with it
                if (!transactionKey.equals(Constants.FIXED_LOGIN_KEY_STRING)) {
                    log.warn("Login attempt from {}:{} was not decrypted with the fixed key. Denying.", clientAddress.getHostAddress(), clientPort);
                    udpSender.sendErrorReply(clientAddress, clientPort, action, "Login must use fixed key.", Constants.FIXED_LOGIN_KEY_STRING); // Send error with fixed key
                    return;
                }
                // Initiate C2S flow for login using the fixed key
                log.info("Initiating C2S flow for LOGIN action from {}:{}", clientAddress.getHostAddress(), clientPort);
                // Pass null for chatid as it's not known/validated yet
                udpSender.initiateClientToServerFlow(action, requestJson, decryptedJsonString, clientAddress, clientPort, Constants.FIXED_LOGIN_KEY_STRING); // Removed chatid param
                return;
            }

            // --- All other initial client requests require a valid session key ---
            // If sessionKey is null here, it means decryption failed with session key OR fixed key was used for non-login action
            if (sessionKey == null) {
                log.warn("Received action '{}' from {}:{} without a valid session key (or used fixed key incorrectly). Denying.", action, clientAddress.getHostAddress(), clientPort);
                // Cannot reliably send error without knowing the key if sessionKey was null initially
                if (transactionKey.equals(Constants.FIXED_LOGIN_KEY_STRING)) { // If fixed key was used incorrectly
                    udpSender.sendErrorReply(clientAddress, clientPort, action, Constants.ERROR_MSG_NOT_LOGGED_IN, Constants.FIXED_LOGIN_KEY_STRING);
                }
                return;
            }

            // Extract chatid from the 'data' object for these actions
            if (!requestJson.has(Constants.KEY_DATA) || !requestJson.getAsJsonObject(Constants.KEY_DATA).has(Constants.KEY_CHAT_ID)) {
                log.warn("Action '{}' from {}:{} requires '{}' field within the 'data' object. Denying.", action, clientAddress.getHostAddress(), clientPort, Constants.KEY_CHAT_ID);
                udpSender.sendErrorReply(clientAddress, clientPort, action, Constants.ERROR_MSG_MISSING_FIELD + "'data." + Constants.KEY_CHAT_ID + "'", sessionKey);
                return;
            }
            // Extract chatid from data object
            chatid = requestJson.getAsJsonObject(Constants.KEY_DATA).get(Constants.KEY_CHAT_ID).getAsString();

            // Validate session using the extracted chatid
            if (!sessionManager.validateSession(chatid, clientAddress, clientPort)) {
                log.warn("Session validation failed for action '{}'. Packet chatid '{}' from {}:{} does not match session owner or address/port.", action, chatid, clientAddress.getHostAddress(), clientPort);
                udpSender.sendErrorReply(clientAddress, clientPort, action, "Session validation failed.", sessionKey);
                return;
            }

            // Additional check: Ensure the key used for decryption matches the session's key
            String expectedKey = sessionManager.getClientKey(chatid);
            if (!sessionKey.equals(expectedKey)) {
                log.warn("Session key mismatch for action '{}' from chatid '{}'. Packet decrypted with different key than expected. Denying.", action, chatid);
                udpSender.sendErrorReply(clientAddress, clientPort, action, Constants.ERROR_MSG_DECRYPTION_FAILED, sessionKey); // Use sessionKey for error
                return;
            }

            // Initiate the Client -> Server flow: Calculate frequencies and send CHARACTER_COUNT back
            udpSender.initiateClientToServerFlow(action, requestJson, decryptedJsonString, clientAddress, clientPort, sessionKey); // Removed chatid param

        } catch (JsonSyntaxException e) {
            log.error("Invalid JSON syntax in packet from {}:{}: {}. Content: {}", clientAddress.getHostAddress(), clientPort, e.getMessage(), decryptedJsonString);
            udpSender.sendErrorReply(clientAddress, clientPort, action != null ? action : "unknown", Constants.ERROR_MSG_INVALID_JSON, transactionKey); // Use the key determined earlier
        } catch (Exception e) {
            log.error("Error processing action '{}' from {}:{}: {}",
                    action != null ? action : "unknown", clientAddress.getHostAddress(), clientPort, e.getMessage(), e);
                    udpSender.sendErrorReply(clientAddress, clientPort, action != null ? action : "unknown", Constants.ERROR_MSG_INTERNAL_SERVER_ERROR, transactionKey); // Use the key determined earlier
        }
    } // End of processPacket method brace - Ensure this brace is correctly placed

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
        if (!running) {
            return; // Prevent multiple shutdowns

                }running = false;
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
