package UdpChatServer;

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
    // private final DatagramSocket socket; // Removed: UdpRequestHandler handles sending
    private final UdpRequestHandler requestHandler; // To initiate S2C flow

    public LoginHandler(UserDAO userDAO, ClientSessionManager sessionManager, UdpRequestHandler requestHandler) {
        this.userDAO = userDAO;
        this.sessionManager = sessionManager;
        // this.socket = socket; // Removed
        this.requestHandler = requestHandler; // Store the request handler
    }

    // Removed handleLogin method as it's replaced by the C2S flow initiated in UdpRequestHandler
    // and the confirmation logic in processConfirmedLogin.

    /**
     * Sends an error reply to the client, encrypted with the specified key.
     * Used for login failures (fixed key) or other pre-login errors.
     * NOTE: This method is likely no longer needed here as UdpRequestHandler handles errors. Kept for reference.
     */
    // private void sendErrorReply(InetAddress clientAddress, int clientPort, String errorMessage, String key) {
    //     JsonObject replyJson = JsonHelper.createErrorReply(Constants.ACTION_LOGIN, errorMessage);
    //      // Encrypt with the provided key (usually FIXED key for login errors)
    //      // JsonHelper.sendPacket(socket, clientAddress, clientPort, replyJson, key, log); // Socket removed
    //      log.warn("sendErrorReply called in LoginHandler, but socket is removed. Error not sent: {}", errorMessage);
    // }

    /**
     * Processes the login action AFTER the client has confirmed via CONFIRM_COUNT.
     * Authenticates the user, generates session key, stores session, and initiates S2C flow for login_success.
     * This method is called by UdpRequestHandler.handleConfirmCount.
     *
     * @param pendingInfo Information about the confirmed login transaction.
     * @return true if authentication was successful, false otherwise.
     */
    public boolean processConfirmedLogin(PendingMessageInfo pendingInfo) {
        if (pendingInfo == null || pendingInfo.getDirection() != PendingMessageInfo.Direction.CLIENT_TO_SERVER ||
            !Constants.ACTION_LOGIN.equals(pendingInfo.getOriginalAction())) {
            log.error("Invalid pending info passed to processConfirmedLogin: {}", pendingInfo);
            return false;
        }

        JsonObject originalRequest = pendingInfo.getOriginalMessageJson();
        JsonObject requestData = originalRequest.getAsJsonObject(Constants.KEY_DATA);
        InetAddress clientAddress = pendingInfo.getPartnerAddress();
        int clientPort = pendingInfo.getPartnerPort();
        String chatid = null;
        String sessionKey;

        try {
            // 1. Extract credentials (already validated for presence by UdpRequestHandler before C2S flow)
            chatid = requestData.get(Constants.KEY_CHAT_ID).getAsString();
            String password = requestData.get(Constants.KEY_PASSWORD).getAsString();

            log.info("Processing confirmed login request for user '{}' from {}:{} (Transaction ID: {})",
                     chatid, clientAddress.getHostAddress(), clientPort, pendingInfo.getTransactionId());

            // 2. Authenticate user via UserDAO
            boolean isAuthenticated = userDAO.authenticateUser(chatid, password);

            if (isAuthenticated) {
                // 3. Generate session key for authenticated user
                String newSessionKey = KeyGenerator.generateKey();

                // 4. Add session to ClientSessionManager
                sessionManager.addSession(chatid, clientAddress, clientPort, newSessionKey);

                // 5. Prepare login_success reply JSON (to be sent via S2C flow)
                JsonObject replyData = new JsonObject();
                replyData.addProperty(Constants.KEY_CHAT_ID, chatid);
                replyData.addProperty(Constants.KEY_SESSION_KEY, newSessionKey); // Send the new key

                JsonObject replyJson = JsonHelper.createReply(
                    Constants.ACTION_LOGIN_SUCCESS,
                    Constants.STATUS_SUCCESS,
                    "Login successful. Confirm receipt.",
                    replyData
                );

                // 6. Initiate Server -> Client flow for the login_success message using the *new* session key
                log.info("Login successful for user '{}'. Initiating S2C flow for login_success with new session key.", chatid);
                requestHandler.initiateServerToClientFlow(
                    Constants.ACTION_LOGIN_SUCCESS,
                    replyJson,
                    clientAddress,
                    clientPort,
                    newSessionKey // Use the NEW session key for this flow
                );
                return true; // Indicate success to UdpRequestHandler for sending ACK(success) with fixed key

            } else {
                // 7. Authentication failed
                log.warn("Confirmed login failed for user '{}' from {}:{}", chatid, clientAddress.getHostAddress(), clientPort);
                return false; // Indicate failure to UdpRequestHandler for sending ACK(failure) with fixed key
            }

        } catch (Exception e) {
            log.error("Error processing confirmed login for user '{}' from {}:{}: {}",
                      (chatid != null ? chatid : "UNKNOWN"), clientAddress.getHostAddress(), clientPort, e.getMessage(), e);
            return false; // Indicate failure
        }
    }
}
