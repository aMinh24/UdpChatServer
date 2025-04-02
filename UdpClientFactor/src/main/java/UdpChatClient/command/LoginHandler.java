package UdpChatClient.command;

import UdpChatClient.ClientState;
import UdpChatClient.Constants;
import UdpChatClient.HandshakeManager;
import UdpChatClient.JsonHelper;
import com.google.gson.JsonObject;

public class LoginHandler implements CommandHandler {

    @Override
    public void handle(String args, ClientState clientState, HandshakeManager handshakeManager) {
        String[] loginArgs = args.split("\\s+", 2);
        if (loginArgs.length != 2) {
            System.out.println("Usage: " + Constants.CMD_LOGIN + " <chatid> <password>");
            System.out.print("> ");
            return;
        }

        String chatId = loginArgs[0];
        String password = loginArgs[1];

        if (clientState.getSessionKey() != null) {
            System.out.println("Already logged in as " + clientState.getCurrentChatId() + ".");
            System.out.print("> ");
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, chatId);
        data.addProperty(Constants.KEY_PASSWORD, password);
        JsonObject request = JsonHelper.createRequest(Constants.ACTION_LOGIN, data);
        // Login uses the fixed key for the initial request
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_LOGIN, Constants.FIXED_LOGIN_KEY_STRING);
        // No need to print "> " here, the response handler will do it or the loop continues
    }

    @Override
    public String getDescription() {
        return Constants.CMD_LOGIN_DESC;
    }
}
