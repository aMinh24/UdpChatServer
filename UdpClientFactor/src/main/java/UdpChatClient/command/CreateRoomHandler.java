package UdpChatClient.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import UdpChatClient.ClientState;
import UdpChatClient.Constants;
import UdpChatClient.HandshakeManager;
import UdpChatClient.JsonHelper;

public class CreateRoomHandler implements CommandHandler {

    @Override
    public void handle(String args, ClientState clientState, HandshakeManager handshakeManager) {
        if (args == null || args.trim().isEmpty()) {
            System.out.println("Usage: " + Constants.CMD_CREATE_ROOM + " <participant1> [participant2...]");
            System.out.print("> ");
            return;
        }

        String[] participants = args.split("\\s+");

        if (clientState.getSessionKey() == null) {
            System.out.println("You must be logged in to create a room. Use /login <id> <pw>");
            System.out.print("> ");
            return;
        }

        // Server-side validation is more robust, but a basic client check is helpful.
        if (participants.length == 0) {
             System.out.println("Usage: " + Constants.CMD_CREATE_ROOM + " <participant1> [participant2...]");
             System.out.print("> ");
             return;
        }

        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, clientState.getCurrentChatId());
        JsonArray participantsArray = new JsonArray();
        for (String p : participants) {
            if (!p.trim().isEmpty()) { // Avoid adding empty strings if there are multiple spaces
                participantsArray.add(p.trim());
            }
        }

        // Check again after trimming potential empty strings
        if (participantsArray.size() == 0) {
             System.out.println("Usage: " + Constants.CMD_CREATE_ROOM + " <participant1> [participant2...]");
             System.out.print("> ");
             return;
        }


        data.add(Constants.KEY_PARTICIPANTS, participantsArray);
        JsonObject request = JsonHelper.createRequest(Constants.ACTION_CREATE_ROOM, data);
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_CREATE_ROOM, clientState.getSessionKey());
        // No need to print "> " here
    }

    @Override
    public String getDescription() {
        return Constants.CMD_CREATE_ROOM_DESC;
    }
}
