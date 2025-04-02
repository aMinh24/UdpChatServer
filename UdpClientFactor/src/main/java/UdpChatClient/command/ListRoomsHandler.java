package UdpChatClient.command;

import com.google.gson.JsonObject;

import UdpChatClient.ClientState;
import UdpChatClient.Constants;
import UdpChatClient.HandshakeManager;
import UdpChatClient.JsonHelper;

public class ListRoomsHandler implements CommandHandler {

    @Override
    public void handle(String args, ClientState clientState, HandshakeManager handshakeManager) {
        // This command doesn't take arguments, but we check for login status.
        if (clientState.getSessionKey() == null) {
            System.out.println("You must be logged in to list rooms. Use /login <id> <pw>");
            System.out.print("> ");
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, clientState.getCurrentChatId());
        JsonObject request = JsonHelper.createRequest(Constants.ACTION_GET_ROOMS, data);
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_GET_ROOMS, clientState.getSessionKey());
        // No need to print "> " here
    }

    @Override
    public String getDescription() {
        return Constants.CMD_LIST_ROOMS_DESC;
    }
}
