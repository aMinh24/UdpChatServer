package UdpChatClient.command;

import com.google.gson.JsonObject;

import UdpChatClient.ClientState;
import UdpChatClient.Constants;
import UdpChatClient.HandshakeManager;
import UdpChatClient.JsonHelper;

public class DeleteRoomHandler implements CommandHandler {

    @Override
    public void handle(String args, ClientState clientState, HandshakeManager handshakeManager) {
        String roomId = args.trim();

        if (clientState.getSessionKey() == null) {
            System.out.println("You must be logged in to delete a room. Use /login <id> <pw>");
            System.out.print("> ");
            return;
        }

        // Validate input
        if (roomId.isEmpty()) {
            System.out.println("Room ID cannot be empty.");
            System.out.print("> ");
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, clientState.getCurrentChatId());
        data.addProperty(Constants.KEY_ROOM_ID, roomId);

        JsonObject request = JsonHelper.createRequest(Constants.ACTION_DELETE_ROOM, data);
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_DELETE_ROOM, clientState.getSessionKey());
        // No need to print "> " here - done in the handshakeManager
    }

    @Override
    public String getDescription() {
        return Constants.CMD_DELETE_ROOM_DESC;
    }
}
