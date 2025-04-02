package UdpChatClient;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.SocketException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

public class MessageListener implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(MessageListener.class);

    private final ClientState clientState;
    private final HandshakeManager handshakeManager;

    public MessageListener(ClientState clientState, HandshakeManager handshakeManager) {
        this.clientState = clientState;
        this.handshakeManager = handshakeManager;
    }

    @Override
    public void run() {
        byte[] receiveData = new byte[Constants.MAX_UDP_PACKET_SIZE];
        log.info("Message listener started.");

        while (clientState.isRunning()) {
            try {
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                // Blocking call - waits for a packet
                clientState.getSocket().receive(receivePacket);

                // Determine decryption key (session key if logged in, otherwise fixed key)
                String decryptionKey = clientState.getSessionKey() != null ? clientState.getSessionKey() : Constants.FIXED_LOGIN_KEY_STRING;

                // Attempt decryption and parsing
                JsonHelper.DecryptedResult decryptedResult = JsonHelper.decryptAndParse(receivePacket, decryptionKey, log);

                // If decryption failed with session key, try the fixed key (might be a late login response)
                if (decryptedResult == null && clientState.getSessionKey() != null) {
                    log.warn("Decryption failed with session key, trying fixed key...");
                    decryptionKey = Constants.FIXED_LOGIN_KEY_STRING;
                    decryptedResult = JsonHelper.decryptAndParse(receivePacket, decryptionKey, log);
                }

                // If still failed, log error and skip packet
                if (decryptedResult == null) {
                    log.error("Failed to decrypt or parse packet from server {}:{}.", receivePacket.getAddress().getHostAddress(), receivePacket.getPort());
                    continue; // Skip to next packet
                }

                JsonObject responseJson = decryptedResult.jsonObject;
                String decryptedJsonString = decryptedResult.decryptedJsonString;
                log.info("Received decrypted JSON: {}", decryptedJsonString);

                // Basic validation: Check for 'action' field
                if (!responseJson.has(Constants.KEY_ACTION)) {
                    log.error("Received packet missing 'action' field: {}", decryptedJsonString);
                    continue; // Skip invalid packet
                }
                String action = responseJson.get(Constants.KEY_ACTION).getAsString();

                // --- Dispatch based on Action ---
                // Handshake-related actions are handled by HandshakeManager
                switch (action) {
                    case Constants.ACTION_CHARACTER_COUNT:
                        handshakeManager.handleCharacterCountResponse(responseJson, receivePacket.getAddress(), receivePacket.getPort());
                        break;
                    case Constants.ACTION_CONFIRM_COUNT:
                        handshakeManager.handleConfirmCountResponse(responseJson, receivePacket.getAddress(), receivePacket.getPort());
                        break;
                    case Constants.ACTION_ACK:
                        handshakeManager.handleServerAck(responseJson);
                        break;
                    case Constants.ACTION_ERROR:
                        handshakeManager.handleServerError(responseJson);
                        break;
                    default:
                        // If it's not a handshake action, it must be an initial action from the server (S->C flow)
                        handshakeManager.handleInitialServerAction(decryptedJsonString, responseJson, receivePacket.getAddress(), receivePacket.getPort());
                        break;
                }

            } catch (SocketException se) {
                // SocketException usually means the socket was closed intentionally
                if (clientState.isRunning()) {
                    // If the client is supposed to be running, this is unexpected
                    log.error("Socket closed unexpectedly: {}", se.getMessage());
                    clientState.setRunning(false); // Stop the client
                } else {
                    // If the client is shutting down, this is expected
                    log.info("Socket closed.");
                }
            } catch (IOException e) {
                // Other I/O errors during receive
                if (clientState.isRunning()) {
                    log.error("IOException receiving packet: {}", e.getMessage(), e);
                    // Consider if we should stop the client on persistent I/O errors
                }
            } catch (JsonSyntaxException e) {
                // Error during JSON parsing (should be caught by JsonHelper, but good to have here too)
                log.error("Failed to parse received JSON: {}", e.getMessage());
            } catch (Exception e) {
                // Catch any other unexpected exceptions to prevent the listener thread from dying
                if (clientState.isRunning()) {
                    log.error("Unexpected error in listener loop: {}", e.getMessage(), e);
                }
            }
        } // end while(running)

        log.info("Message listener thread stopped.");
    }
}
