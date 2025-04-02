/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package UdpChatServer.handler;

/**
 *
 * @author nguye
 */
import UdpChatServer.db.UserDAO;
import UdpChatServer.model.Constants;
import UdpChatServer.model.PendingMessageInfo;
import UdpChatServer.net.UdpSender;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.InetAddress;

public class RegisterHandler {
    private static final Logger log = LoggerFactory.getLogger(RegisterHandler.class);
    private final UserDAO userDAO;
    private final UdpSender udpSender;

    public RegisterHandler(UserDAO userDAO, UdpSender udpSender) {
        this.userDAO = userDAO;
        this.udpSender = udpSender;
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
            responseData.addProperty(Constants.KEY_MESSAGE, "Registration successful. Please login with /login <chatid> <password>");

            JsonObject responseJson = new JsonObject();
            responseJson.addProperty(Constants.KEY_ACTION, Constants.ACTION_REGISTER_SUCCESS); // Dùng hằng số
            responseJson.add(Constants.KEY_DATA, responseData);

            udpSender.initiateServerToClientFlow(Constants.ACTION_REGISTER_SUCCESS, responseJson, clientAddress, clientPort, transactionKey);
            log.info("User {} registered successfully.", chatid);
            return true;

        } catch (Exception e) {
            log.error("Error processing register for {}: {}", chatid, e.getMessage(), e);
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                    "Failed to register: " + e.getMessage(), transactionKey);
            return false;
        }
    }
}
