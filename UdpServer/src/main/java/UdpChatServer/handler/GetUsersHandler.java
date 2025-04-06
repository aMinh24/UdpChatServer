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
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import UdpChatServer.db.UserDAO;
import UdpChatServer.manager.ClientSessionManager;
import UdpChatServer.model.Constants;
import UdpChatServer.model.PendingMessageInfo;
import UdpChatServer.net.UdpSender;

public class GetUsersHandler {
    private static final Logger log = LoggerFactory.getLogger(GetUsersHandler.class);
    private final ClientSessionManager sessionManager;
    private final UserDAO userDAO;
    private final UdpSender udpSender;

    public GetUsersHandler(ClientSessionManager sessionManager, UserDAO userDAO, UdpSender udpSender) {
        this.sessionManager = sessionManager;
        this.userDAO = userDAO;
        this.udpSender = udpSender;
    }

    public boolean processConfirmedGetUsers(PendingMessageInfo pendingInfo) {
        InetAddress clientAddress = pendingInfo.getPartnerAddress();
        int clientPort = pendingInfo.getPartnerPort();
        String chatid = pendingInfo.getOriginalMessageJson()
                .getAsJsonObject(Constants.KEY_DATA)
                .get(Constants.KEY_CHAT_ID)
                .getAsString();
        String transactionKey = pendingInfo.getTransactionKey();

        log.info("Processing confirmed get_users request from {} ({}:{})", chatid, clientAddress.getHostAddress(), clientPort);

        if (!sessionManager.validateSession(chatid, clientAddress, clientPort)) {
            log.warn("Invalid session for chatid {} from {}:{}", chatid, clientAddress.getHostAddress(), clientPort);
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                    "Invalid session.", transactionKey);
            return false;
        }

        try {
            List<String> users = userDAO.getAllChatIds();
            if (users == null || users.isEmpty()) {
                log.info("No users found in the system.");
            } else {
                log.info("Found {} users in the system.", users.size());
            }

            JsonObject responseData = new JsonObject();
            JsonArray usersArray = new JsonArray();
            // Add null check before iterating
            if (users != null) {
                for (String user : users) {
                    usersArray.add(user);
                }
            }
            responseData.add("users", usersArray);

            JsonObject responseJson = new JsonObject();
            responseJson.addProperty(Constants.KEY_ACTION, Constants.ACTION_USERS_LIST); // Dùng hằng số
            responseJson.add(Constants.KEY_DATA, responseData);

            udpSender.initiateServerToClientFlow(Constants.ACTION_USERS_LIST, responseJson, clientAddress, clientPort, transactionKey);
            return true;

        } catch (Exception e) {
            log.error("Error processing get_users for {}: {}", chatid, e.getMessage(), e);
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                    "Failed to retrieve users: " + e.getMessage(), transactionKey);
            return false;
        }
    }
}
