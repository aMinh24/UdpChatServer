package UdpChatServer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

/**
 * Handles the logic for user login requests, initiating the Server -> Client confirmation flow.
 */
public class LoginHandler {

    private static final Logger log = LoggerFactory.getLogger(LoginHandler.class);

    private final UserDAO userDAO;
    private final ClientSessionManager sessionManager;
    private final DatagramSocket socket; // Still needed for sending potential error replies with fixed key
    private final UdpRequestHandler requestHandler; // To initiate S2C flow

    public LoginHandler(UserDAO userDAO, ClientSessionManager sessionManager, DatagramSocket socket, UdpRequestHandler requestHandler) {
        this.userDAO = userDAO;
        this.sessionManager = sessionManager;
        this.socket = socket;
        this.requestHandler = requestHandler; // Store the request handler
    }

    /**
     * Processes a login request received via UDP.
     * Assumes the requestJson has already been decrypted using the FIXED_LOGIN_KEY.
     * Authenticates the user, generates a session key, stores the session,
     * and initiates the Server -> Client confirmation flow by sending the login success message.
     *
     * @param requestPacket The original DatagramPacket (needed for address/port).
     * @param requestJson   The decrypted JsonObject from the request packet.
     */
    public void handleLogin(DatagramPacket requestPacket, JsonObject requestJson) {
        InetAddress clientAddress = requestPacket.getAddress();
        int clientPort = requestPacket.getPort();
        String chatid = null; // Initialize to null
        String sessionKey = null;

        try {
            // 1. Extract credentials from the 'data' object within the decrypted JSON
            if (!requestJson.has(Constants.KEY_DATA) || !requestJson.getAsJsonObject(Constants.KEY_DATA).has(Constants.KEY_CHAT_ID) || !requestJson.getAsJsonObject(Constants.KEY_DATA).has(Constants.KEY_PASSWORD)) {
                log.warn("Login request missing 'data' object or 'chatid'/'password' within data from {}:{}", clientAddress.getHostAddress(), clientPort);
                // Send error using fixed key as login hasn't succeeded
                sendErrorReply(clientAddress, clientPort, Constants.ERROR_MSG_MISSING_FIELD + "'data', 'chatid' or 'password'", Constants.FIXED_LOGIN_KEY_STRING);
                return;
            }
            JsonObject data = requestJson.getAsJsonObject(Constants.KEY_DATA);
            chatid = data.get(Constants.KEY_CHAT_ID).getAsString();
            String password = data.get(Constants.KEY_PASSWORD).getAsString();

            log.info("Processing login request for user '{}' from {}:{}", chatid, clientAddress.getHostAddress(), clientPort);

            // 2. Authenticate user via UserDAO
            boolean isAuthenticated = userDAO.authenticateUser(chatid, password);

            if (isAuthenticated) {
                // 3. Generate session key
                sessionKey = KeyGenerator.generateKey();

                // 4. Add session to ClientSessionManager
                sessionManager.addSession(chatid, clientAddress, clientPort, sessionKey);

                // 5. Prepare success reply JSON
                JsonObject replyData = new JsonObject();
                replyData.addProperty(Constants.KEY_CHAT_ID, chatid);
                replyData.addProperty(Constants.KEY_SESSION_KEY, sessionKey); // Send the new key

                JsonObject replyJson = JsonHelper.createReply(
                    Constants.ACTION_LOGIN_SUCCESS,
                    Constants.STATUS_SUCCESS,
                    "Login successful. Confirm receipt.", // Updated message
                    replyData
                );

                // 6. Initiate Server -> Client flow using the *new* session key
                log.info("Login successful for user '{}'. Initiating S2C flow with new session key.", chatid);
                requestHandler.initiateServerToClientFlow(
                    Constants.ACTION_LOGIN_SUCCESS,
                    replyJson,
                    clientAddress,
                    clientPort,
                    Constants.FIXED_LOGIN_KEY_STRING // Use the fixed key for login success
                );

            } else {
                // 7. Send authentication failure reply, encrypted with the fixed key
                log.warn("Login failed for user '{}' from {}:{}", chatid, clientAddress.getHostAddress(), clientPort);
                sendErrorReply(clientAddress, clientPort, Constants.ERROR_MSG_AUTHENTICATION_FAILED, Constants.FIXED_LOGIN_KEY_STRING);
            }

        } catch (Exception e) {
            // Catch potential exceptions during JSON parsing or processing
            log.error("Error processing login request for user '{}' from {}:{}: {}",
                      (chatid != null ? chatid : "UNKNOWN"), clientAddress.getHostAddress(), clientPort, e.getMessage(), e);
            // Send error using fixed key
            sendErrorReply(clientAddress, clientPort, Constants.ERROR_MSG_INTERNAL_SERVER_ERROR, Constants.FIXED_LOGIN_KEY_STRING);
        }
    }

    /**
     * Sends an error reply to the client, encrypted with the specified key.
     * Used for login failures (fixed key) or other pre-login errors.
     */
    private void sendErrorReply(InetAddress clientAddress, int clientPort, String errorMessage, String key) {
        JsonObject replyJson = JsonHelper.createErrorReply(Constants.ACTION_LOGIN, errorMessage);
         // Encrypt with the provided key (usually FIXED key for login errors)
         JsonHelper.sendPacket(socket, clientAddress, clientPort, replyJson, key, log);
    }
}
