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
    private final CreateRoomHandler createRoomHandler;

    public RegisterHandler(UserDAO userDAO, UdpSender udpSender, ClientSessionManager sessionManager, CreateRoomHandler createRoomHandler) {
        this.userDAO = userDAO;
        this.udpSender = udpSender;
        this.sessionManager = sessionManager;
        this.createRoomHandler = createRoomHandler;
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

            boolean botRoomCreated = createRoomHandler.createRoomWithBot(chatid);
            if (!botRoomCreated) {
                log.error("Failed to create default bot room for user '{}', but registration succeeded.", chatid);
            }

            JsonObject responseData = new JsonObject();
            responseData.addProperty(Constants.KEY_CHAT_ID, chatid);
            responseData.addProperty(Constants.KEY_MESSAGE, "Registration successful. Please login with /login chatid password");

            JsonObject responseJson = new JsonObject();
            responseJson.addProperty(Constants.KEY_ACTION, Constants.ACTION_REGISTER_SUCCESS);
            responseJson.add(Constants.KEY_DATA, responseData);

            udpSender.initiateServerToClientFlow(Constants.ACTION_REGISTER_SUCCESS, responseJson, clientAddress, clientPort, transactionKey);
            log.info("User {} registered successfully.", chatid);

            List<String> allUserIds = userDAO.getAllChatIds();
            Set<String> userIdSet = new HashSet<>(allUserIds);
            forwardUserListUpdate(userIdSet, chatid);
            return true;

        } catch (Exception e) {
            log.error("Error processing register for {}: {}", chatid, e.getMessage(), e);
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                    "Failed to register: " + e.getMessage(), transactionKey);
            return false;
        }
    }

    private void forwardUserListUpdate(Set<String> allParticipants, String newUserChatId) {
        Set<String> participants = allParticipants;

        if (participants.isEmpty()) {
            log.warn("No users found to forward user list update.");
            return;
        }

        JsonObject data = new JsonObject();
        data.add(Constants.KEY_PARTICIPANTS, JsonHelper.convertSetToJsonArray(participants));
        JsonObject messageJson = JsonHelper.createReply(
            Constants.ACTION_RECIEVE_LISTUSER,
            Constants.STATUS_SUCCESS,
            "User list updated.",
            data
        );

        log.info("Forwarding updated user list to online users (excluding new user '{}').", newUserChatId);
        for (String recipientChatId : participants) {
            if (!recipientChatId.equals(newUserChatId)) {
                SessionInfo recipientSession = sessionManager.getSessionInfo(recipientChatId);
                if (recipientSession != null && recipientSession.getKey() != null) {
                    udpSender.initiateServerToClientFlow(
                        Constants.ACTION_RECIEVE_LISTUSER,
                        messageJson,
                        recipientSession.getIpAddress(),
                        recipientSession.getPort(),
                        recipientSession.getKey()
                    );
                    log.debug("Sent updated user list to online user '{}'", recipientChatId);
                } else {
                    log.trace("User '{}' is offline or session key missing. Skipping user list update.", recipientChatId);
                }
            }
        }
    }
}
