/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package UdpChatClient.command;

/**
 *
 * @author nguye
 */

import com.google.gson.JsonObject;

import UdpChatClient.ClientState;
import UdpChatClient.Constants;
import UdpChatClient.HandshakeManager;
import UdpChatClient.JsonHelper;

public class AddUserHandler implements CommandHandler {

    @Override
    public void handle(String args, ClientState clientState, HandshakeManager handshakeManager) {
        if (args == null || args.trim().isEmpty()) {
            System.out.println("Usage: " + Constants.CMD_ADD + " <room_id> <target_chatid>");
            System.out.print("> ");
            return;
        }

        String[] parts = args.split("\\s+");
        if (parts.length != 2) {
            System.out.println("Usage: " + Constants.CMD_ADD + " <room_id> <target_chatid>");
            System.out.print("> ");
            return;
        }

        String roomId = parts[0].trim();
        String targetChatId = parts[1].trim();

        if (clientState.getSessionKey() == null) {
            System.out.println("You must be logged in to add a user. Use /login <id> <pw>");
            System.out.print("> ");
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, clientState.getCurrentChatId());
        data.addProperty(Constants.KEY_ROOM_ID, roomId);
        data.addProperty(Constants.KEY_TARGET_CHATID, targetChatId);

        JsonObject request = JsonHelper.createRequest(Constants.ACTION_ADD_USER, data);
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_ADD_USER, clientState.getSessionKey());
    }

    @Override
    public String getDescription() {
        return Constants.CMD_ADD + " <room_id> <target_chatid> - Add a user to a room";
    }
}
