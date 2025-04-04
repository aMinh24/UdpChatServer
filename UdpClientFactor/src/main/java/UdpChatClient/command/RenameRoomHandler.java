package UdpChatClient.command;

import com.google.gson.JsonObject;

import UdpChatClient.ClientState;
import UdpChatClient.Constants;
import UdpChatClient.HandshakeManager;
import UdpChatClient.JsonHelper;

public class RenameRoomHandler implements CommandHandler {

    @Override
    public void handle(String args, ClientState clientState, HandshakeManager handshakeManager) {
        String[] renameRoomArgs = args.split("\\s+", 2);
        if (renameRoomArgs.length != 2) {
            System.out.println("Usage: " + Constants.CMD_RENAME_ROOM + " <room_id> <new_room_name>");
            System.out.print("> ");
            return;
        }

        String roomId = renameRoomArgs[0].trim();
        String newRoomName = renameRoomArgs[1].trim();

        if (clientState.getSessionKey() == null) {
            System.out.println("You must be logged in to rename a room. Use /login <id> <pw>");
            System.out.print("> ");
            return;
        }

        // Validate inputs
        if (roomId.isEmpty() || newRoomName.isEmpty()) {
            System.out.println("Room ID and new room name cannot be empty.");
            System.out.print("> ");
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, clientState.getCurrentChatId());
        data.addProperty(Constants.KEY_ROOM_ID, roomId);
        data.addProperty(Constants.KEY_ROOM_NAME, newRoomName);

        JsonObject request = JsonHelper.createRequest(Constants.ACTION_RENAME_ROOM, data);
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_RENAME_ROOM, clientState.getSessionKey());
        // No need to print "> " here - done in the handshakeManager
    }

    @Override
    public String getDescription() {
        return Constants.CMD_RENAME_ROOM_DESC;
    }
}
