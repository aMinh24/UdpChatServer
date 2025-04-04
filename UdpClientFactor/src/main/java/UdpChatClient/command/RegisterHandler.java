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
public class RegisterHandler implements CommandHandler {

    @Override
    public void handle(String args, ClientState clientState, HandshakeManager handshakeManager) {
        String[] registerArgs = args.split("\\s+", 2);
        if (registerArgs.length != 2) {
            System.out.println("Usage: " + Constants.CMD_REGISTER + " <chatid> <password>");
            System.out.print("> ");
            return;
        }

        String chatId = registerArgs[0];
        String password = registerArgs[1];

        if (clientState.getSessionKey() != null) {
            System.out.println("You are already logged in as " + clientState.getCurrentChatId() + ". Please log out before registering a new account.");
            System.out.print("> ");
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, chatId);
        data.addProperty(Constants.KEY_PASSWORD, password);
        JsonObject request = JsonHelper.createRequest(Constants.ACTION_REGISTER, data);
        // Register uses the fixed key for the initial request, similar to login
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_REGISTER, Constants.FIXED_LOGIN_KEY_STRING);
    }

    @Override
    public String getDescription() {
        return Constants.CMD_REGISTER_DESC;
    }    
}
