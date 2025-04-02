package UdpChatClient.command;

import com.google.gson.JsonObject;

import UdpChatClient.ClientState;
import UdpChatClient.Constants;
import UdpChatClient.HandshakeManager;
import UdpChatClient.JsonHelper;

public class SendHandler implements CommandHandler {

    @Override
    public void handle(String args, ClientState clientState, HandshakeManager handshakeManager) {
        String[] sendArgs = args.split("\\s+", 2);
        if (sendArgs.length != 2) {
            System.out.println("Usage: " + Constants.CMD_SEND + " <room_id> <message>");
            System.out.print("> ");
            return;
        }

        String roomId = sendArgs[0];
        String content = sendArgs[1];

        if (clientState.getSessionKey() == null) {
            System.out.println("You must be logged in to send messages. Use /login <id> <pw>");
            System.out.print("> ");
            return;
        }

        // Basic validation, server should do more thorough checks
        if (roomId.trim().isEmpty() || content.isEmpty()) {
            System.out.println("Usage: " + Constants.CMD_SEND + " <room_id> <message>");
            System.out.print("> ");
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, clientState.getCurrentChatId());
        data.addProperty(Constants.KEY_ROOM_ID, roomId.trim());
        data.addProperty(Constants.KEY_CONTENT, content);
        JsonObject request = JsonHelper.createRequest(Constants.ACTION_SEND_MESSAGE, data);
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_SEND_MESSAGE, clientState.getSessionKey());
        // No need to print "> " here
    }

    @Override
    public String getDescription() {
        return Constants.CMD_SEND_DESC;
    }
}
