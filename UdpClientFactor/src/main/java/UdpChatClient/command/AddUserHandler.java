package UdpChatClient.command;
import com.google.gson.JsonObject;

import UdpChatClient.ClientState;
import UdpChatClient.Constants;
import UdpChatClient.HandshakeManager;
import UdpChatClient.JsonHelper;

public class AddUserHandler implements CommandHandler {

    @Override
    public void handle(String args, ClientState clientState, HandshakeManager handshakeManager) {
        String[] addUserArgs = args.split("\\s+", 2);
        if (addUserArgs.length != 2) {
            System.out.println("Usage: " + Constants.CMD_ADD_USER + " <room_id> <username>");
            System.out.print("> ");
            return;
        }

        String roomId = addUserArgs[0].trim();
        String userToAdd = addUserArgs[1].trim();

        if (clientState.getSessionKey() == null) {
            System.out.println("You must be logged in to add users to a room. Use /login <id> <pw>");
            System.out.print("> ");
            return;
        }

        // Validate inputs
        if (roomId.isEmpty() || userToAdd.isEmpty()) {
            System.out.println("Room ID and username cannot be empty.");
            System.out.print("> ");
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, clientState.getCurrentChatId());
        data.addProperty(Constants.KEY_ROOM_ID, roomId);
        data.addProperty("user_to_add", userToAdd);

        JsonObject request = JsonHelper.createRequest(Constants.ACTION_ADD_USER_TO_ROOM, data);
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_ADD_USER_TO_ROOM, clientState.getSessionKey());
        // No need to print "> " here - done in the handshakeManager
    }

    @Override
    public String getDescription() {
        return Constants.CMD_ADD_USER_DESC;
    }
}
