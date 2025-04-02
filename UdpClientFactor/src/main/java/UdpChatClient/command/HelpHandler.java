package UdpChatClient.command;

import UdpChatClient.ClientState;
import UdpChatClient.CommandProcessor;
import UdpChatClient.Constants;
import UdpChatClient.HandshakeManager;

public class HelpHandler implements CommandHandler {
    private final CommandProcessor commandProcessor;
    
    public HelpHandler(CommandProcessor commandProcessor) {
        this.commandProcessor = commandProcessor;
    }

    @Override
    public void handle(String args, ClientState clientState, HandshakeManager handshakeManager) {
        commandProcessor.showHelp();
    }

    @Override
    public String getDescription() {
        return Constants.CMD_HELP_DESC;
    }
}
