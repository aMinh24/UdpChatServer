/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package UdpChatClient.command;

/**
 *
 * @author nguye
 */
import UdpChatClient.ClientState;
import UdpChatClient.Constants;
import UdpChatClient.HandshakeManager;
import UdpChatClient.JsonHelper;
import com.google.gson.JsonObject;

public class GetUsersHandler implements CommandHandler {

    @Override
    public void handle(String args, ClientState clientState, HandshakeManager handshakeManager) {
        if (!args.trim().isEmpty()) {
            System.out.println("Usage: " + Constants.CMD_GET_USERS + " (no arguments required)");
            System.out.print("> ");
            return;
        }

        if (clientState.getSessionKey() == null) {
            System.out.println("You must log in first using " + Constants.CMD_LOGIN + ".");
            System.out.print("> ");
            return;
        }

        String chatId = clientState.getCurrentChatId();
        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, chatId);
        JsonObject request = JsonHelper.createRequest(Constants.ACTION_GET_USERS, data);
        // Use session key after login
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_GET_USERS, clientState.getSessionKey());
    }

    @Override
    public String getDescription() {
        return Constants.CMD_GET_USERS_DESC;
    }
}
