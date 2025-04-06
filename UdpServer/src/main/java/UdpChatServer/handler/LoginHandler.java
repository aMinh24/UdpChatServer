package UdpChatServer.handler;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import UdpChatServer.crypto.KeyGenerator;
import UdpChatServer.db.MessageDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.manager.ClientSessionManager;
import UdpChatServer.model.Constants;
import UdpChatServer.model.PendingMessageInfo;
import UdpChatServer.net.UdpSender;
import UdpChatServer.util.JsonHelper;

/**
 * Handles the logic for user login requests, initiating the Server -> Client confirmation flow.
 */
public class LoginHandler {

    private static final Logger log = LoggerFactory.getLogger(LoginHandler.class);
    private static final Gson gson = new Gson();

    private final UserDAO userDAO;
    private final RoomDAO roomDAO;
    private final MessageDAO messageDAO;
    private final ClientSessionManager sessionManager;
    private final UdpSender udpSender;

    public LoginHandler(UserDAO userDAO, RoomDAO roomDAO, MessageDAO messageDAO, ClientSessionManager sessionManager, UdpSender udpSender) {
        this.userDAO = userDAO;
        this.roomDAO = roomDAO;
        this.messageDAO = messageDAO;
        this.sessionManager = sessionManager;
        this.udpSender = udpSender;
    }

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

        try {
            // 1. Extract credentials
            chatid = requestData.get(Constants.KEY_CHAT_ID).getAsString();
            String password = requestData.get(Constants.KEY_PASSWORD).getAsString();

            log.info("Processing confirmed login request for user '{}' from {}:{} (Transaction ID: {})",
                     chatid, clientAddress.getHostAddress(), clientPort, pendingInfo.getTransactionId());

            // 2. Authenticate user via UserDAO
            boolean isAuthenticated = userDAO.authenticateUser(chatid, password);

            if (isAuthenticated) {
                log.info("User '{}' authenticated successfully.", chatid);

                // 3. Generate session key
                String newSessionKey = KeyGenerator.generateKey();

                // 4. Add session to ClientSessionManager
                sessionManager.addSession(chatid, clientAddress, clientPort, newSessionKey);
                log.info("Session created for user '{}' with key ending in ...{}", chatid, newSessionKey.length() > 4 ? newSessionKey.substring(newSessionKey.length() - 4) : newSessionKey);

                // 5. Fetch user's room list
                log.info("Fetching room list for user '{}' after successful login.", chatid);
                List<Map<String, Object>> userRoomsData = roomDAO.getRoomsAndMembersByUser(chatid);
                System.out.println("---------------------------------------"+userRoomsData);
                JsonArray roomsJsonArray = gson.toJsonTree(userRoomsData).getAsJsonArray();
                System.out.println("------------------------s---------------"+roomsJsonArray);
                log.info("Retrieved {} rooms for user '{}'.", roomsJsonArray.size(), chatid);

                // 6. Fetch all users list
                log.info("Fetching list of all users.");
                List<String> allUserIds = userDAO.getAllChatIds();
                System.out.println("------------------------s---------------"+allUserIds);
                JsonArray allUsersJsonArray = gson.toJsonTree(allUserIds).getAsJsonArray();
                System.out.println("------------------------s---------------"+allUsersJsonArray);
                log.info("Retrieved {} total users.", allUsersJsonArray.size());

                // 7. Fetch all messages for user's rooms (POTENTIALLY VERY LARGE) - ADDING BACK
                log.warn("Fetching ALL messages for ALL {} rooms of user '{}'. This might be slow and generate large response!", userRoomsData.size(), chatid);
                JsonObject allMessagesByRoomJson = new JsonObject();
                int totalMessagesFetched = 0;
                for (Map<String, Object> roomInfo : userRoomsData) {
                    String currentRoomId = (String) roomInfo.get("id");
                    if (currentRoomId != null) {
                        log.debug("Fetching messages for room ID: {}", currentRoomId);
                        List<UdpChatServer.model.Message> messagesInRoom = messageDAO.getAllMessagesByRoom(currentRoomId);
                        JsonArray messagesJsonArray = new JsonArray();
                        for (UdpChatServer.model.Message msg : messagesInRoom) {
                            JsonObject msgJson = new JsonObject();
                            msgJson.addProperty("message_id", msg.getMessageId());
                            msgJson.addProperty("sender_chatid", msg.getSenderChatid());
                            msgJson.addProperty("content", msg.getContent());
                            msgJson.addProperty("timestamp", msg.getTimestamp().toInstant().toString());
                            messagesJsonArray.add(msgJson);
                        }
                        allMessagesByRoomJson.add(currentRoomId, messagesJsonArray);
                        totalMessagesFetched += messagesInRoom.size();
                        log.debug("Fetched {} messages for room {}", messagesInRoom.size(), currentRoomId);
                    }
                }
                System.out.println("------------------------s---------------"+allMessagesByRoomJson);
                log.warn("Finished fetching messages. Total messages fetched: {}. Adding to response.", totalMessagesFetched);

                // 8. Prepare login_success reply JSON data
                JsonObject replyData = new JsonObject();
                replyData.addProperty(Constants.KEY_CHAT_ID, chatid);
                replyData.addProperty(Constants.KEY_SESSION_KEY, newSessionKey); // SECURITY RISK
                replyData.add("rooms", roomsJsonArray);
                replyData.add("all_users", allUsersJsonArray);
                replyData.add("all_messages", allMessagesByRoomJson); // <-- Add all messages back

                // Create the final JSON structure to be sent in ACK message
                JsonObject replyJson = JsonHelper.createReply(
                    Constants.ACTION_LOGIN_SUCCESS, // Still use LOGIN_SUCCESS action type conceptually
                    Constants.STATUS_SUCCESS,
                    "Login successful. Room list, all users, and all messages included.", // Updated message
                    replyData
                );

                // Log the JSON being sent via ACK message
                log.debug("Sending ACTION_LOGIN_SUCCESS JSON via ACK message (INSECURE): {}", replyJson.toString());

                // 9. Send the entire replyJson as the message in the ACK packet
                log.warn("Sending login success data including session key via ACK message using fixed key - SECURITY RISK!");
                udpSender.sendAck(
                    clientAddress,                  // Use clientAddress from pendingInfo
                    clientPort,                   // Use clientPort from pendingInfo
                    pendingInfo.getTransactionId(), // Use the original C2S transaction ID
                    true,                           // Success status
                    replyJson.toString(),           // Embed the full JSON response as the message string
                    pendingInfo.getTransactionKey() // Use the key from the original C2S flow (fixed key)
                );

                return true;

            } else {
                log.warn("Confirmed login failed for user '{}' from {}:{}. Invalid credentials.", chatid, clientAddress.getHostAddress(), clientPort);
                return false;
            }

        } catch (Exception e) {
            log.error("Error processing confirmed login for user '{}' from {}:{}: {}",
                      (chatid != null ? chatid : "UNKNOWN"), clientAddress.getHostAddress(), clientPort, e.getMessage(), e);
            return false;
        }
    }
    
}
