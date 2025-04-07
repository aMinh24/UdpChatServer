/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package UdpChatServer.handler;

/**
 *
 * @author nguye
 */
import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import UdpChatServer.db.UserDAO;
import UdpChatServer.manager.ClientSessionManager;
import UdpChatServer.model.Constants;
import UdpChatServer.model.PendingMessageInfo;
import UdpChatServer.model.SessionInfo;
import UdpChatServer.net.UdpSender;
import UdpChatServer.util.JsonHelper;

public class RegisterHandler {
    private static final Logger log = LoggerFactory.getLogger(RegisterHandler.class);
    private final UserDAO userDAO;
    private final UdpSender udpSender;
    private final ClientSessionManager sessionManager;
    private final Gson gson = new Gson(); // Added Gson instance

    public RegisterHandler(UserDAO userDAO, UdpSender udpSender, ClientSessionManager sessionManager) {
        this.userDAO = userDAO;
        this.udpSender = udpSender;
        this.sessionManager = sessionManager;
    }

    public boolean processConfirmedRegister(PendingMessageInfo pendingInfo) {
        InetAddress clientAddress = pendingInfo.getPartnerAddress();
        int clientPort = pendingInfo.getPartnerPort();
        JsonObject data = pendingInfo.getOriginalMessageJson().getAsJsonObject(Constants.KEY_DATA);
        String transactionKey = pendingInfo.getTransactionKey();

        String chatid = data.get(Constants.KEY_CHAT_ID).getAsString();
        String password = data.get(Constants.KEY_PASSWORD).getAsString();

        log.info("Processing confirmed register request for {} from {}:{}", chatid, clientAddress.getHostAddress(), clientPort);

        try {
            if (userDAO.userExists(chatid)) {
                log.warn("Chatid {} already exists.", chatid);
                udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                        "Chatid already exists.", transactionKey);
                return false;
            }

            boolean success = userDAO.addUser(chatid, password);
            if (!success) {
                log.error("Failed to add user {} to database.", chatid);
                udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                        "Database error during registration.", transactionKey);
                return false;
            }

            JsonObject responseData = new JsonObject();
            responseData.addProperty(Constants.KEY_CHAT_ID, chatid);
            responseData.addProperty(Constants.KEY_MESSAGE, "Registration successful. Please login with /login chatid password");

            JsonObject responseJson = new JsonObject();
            responseJson.addProperty(Constants.KEY_ACTION, Constants.ACTION_REGISTER_SUCCESS); // Dùng hằng số
            responseJson.add(Constants.KEY_DATA, responseData);

            udpSender.initiateServerToClientFlow(Constants.ACTION_REGISTER_SUCCESS, responseJson, clientAddress, clientPort, transactionKey);
            log.info("User {} registered successfully.", chatid);

            List<String> allUserIds = userDAO.getAllChatIds();
            System.out.println("------------------------s---------------"+allUserIds);
            JsonArray allUsersJsonArray = gson.toJsonTree(allUserIds).getAsJsonArray();
            // Convert List to Set before passing to the forwardRoomToUser method
            Set<String> userIdSet = new HashSet<>(allUserIds);
            forwardRoomToUser(userIdSet, chatid);
            return true;

        } catch (Exception e) {
            log.error("Error processing register for {}: {}", chatid, e.getMessage(), e);
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                    "Failed to register: " + e.getMessage(), transactionKey);
            return false;
        }
    }
     private void forwardRoomToUser(Set<String> initialParticipants, String creatorChatId) {
        // Get participants from RoomDAO for persistence, or use the provided set
        Set<String> participants = initialParticipants;

        JsonObject data = new JsonObject();
        data.add(Constants.KEY_PARTICIPANTS, JsonHelper.convertSetToJsonArray(participants)); // Convert Set to JsonArray
        JsonObject messageJson = JsonHelper.createReply(
            Constants.ACTION_RECIEVE_LISTUSER,
            Constants.STATUS_SUCCESS,
            "New room.",
            data
        );

        for (String recipientChatId : participants) {
            if (!recipientChatId.equals(creatorChatId)) {
                SessionInfo recipientSession = sessionManager.getSessionInfo(recipientChatId);
                if (recipientSession != null && recipientSession.getKey() != null) {
                    udpSender.initiateServerToClientFlow( // Changed from requestHandler
                        Constants.ACTION_RECIEVE_LISTUSER,
                        messageJson,
                        recipientSession.getIpAddress(),
                        recipientSession.getPort(),
                        recipientSession.getKey() // Use recipient's session key
                    );
                } else {
                    log.debug("Recipient '{}' in room '{}' is offline or key missing. Message saved in DB, not forwarded in real-time.", recipientChatId);
                    // Message is already saved, so offline users will get it later via get_messages
                }
            }
        }
    }
}
