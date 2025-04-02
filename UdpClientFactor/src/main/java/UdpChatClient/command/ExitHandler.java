package UdpChatClient.command;

import UdpChatClient.ClientState;
import UdpChatClient.Constants;
import UdpChatClient.HandshakeManager;

public class ExitHandler implements CommandHandler {

    @Override
    public void handle(String args, ClientState clientState, HandshakeManager handshakeManager) {
        System.out.println("Exiting...");
        clientState.setRunning(false);
    }

    @Override
    public String getDescription() {
        return Constants.CMD_EXIT_DESC;
    }
}
