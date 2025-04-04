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

public class RenameRoomHandler implements CommandHandler {

    @Override
    public void handle(String args, ClientState clientState, HandshakeManager handshakeManager) {
        if (args == null || args.trim().isEmpty()) {
            System.out.println("Usage: " + Constants.CMD_RENAME + " <room_id> <new_name>");
            System.out.print("> ");
            return;
        }

        String[] parts = args.split("\\s+", 2);
        if (parts.length != 2) {
            System.out.println("Usage: " + Constants.CMD_RENAME + " <room_id> <new_name>");
            System.out.print("> ");
            return;
        }

        String roomId = parts[0].trim();
        String newName = parts[1].trim();

        if (clientState.getSessionKey() == null) {
            System.out.println("You must be logged in to rename a room. Use /login <id> <pw>");
            System.out.print("> ");
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, clientState.getCurrentChatId());
        data.addProperty(Constants.KEY_ROOM_ID, roomId);
        data.addProperty(Constants.KEY_NEW_NAME, newName);

        JsonObject request = JsonHelper.createRequest(Constants.ACTION_RENAME_ROOM, data);
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_RENAME_ROOM, clientState.getSessionKey());
    }

    @Override
    public String getDescription() {
        return Constants.CMD_RENAME + " <room_id> <new_name> - Rename a room";
    }
}
