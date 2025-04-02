package UdpChatServer.model;
import java.net.InetAddress;
import java.util.Map;

import com.google.gson.JsonObject;

/**
 * Holds information about a pending message transaction that requires multiple steps (e.g., count, confirm, ack).
 * This includes the original message content and metadata needed for validation, state tracking, and processing.
 */
public class PendingMessageInfo {

    // Enum to represent the direction of the initial message in the transaction
    public enum Direction {
        CLIENT_TO_SERVER,
        SERVER_TO_CLIENT
    }

    // Enum to represent the current state of the transaction
    public enum State {
        INITIAL_SENT,             // Initial packet sent (either by client or server)
        WAITING_FOR_CHAR_COUNT,   // Server waiting for CHARACTER_COUNT from Client (Server->Client flow)
        WAITING_FOR_CONFIRM,      // Server waiting for CONFIRM_COUNT from Client (Client->Server flow)
        WAITING_FOR_ACK           // Server waiting for ACK from Client (Server->Client flow)
                                  // Client waiting for ACK from Server (Client->Server flow) - Server sends ACK immediately after processing or confirm=false
    }

    private final String transactionId; // Unique ID for this transaction
    private final Direction direction;
    private State currentState;
    private final JsonObject originalMessageJson; // The JSON of the *initial* message that started the flow
    private final String originalDecryptedJsonString; // The full decrypted string of the initial message
    private final Map<Character, Integer> expectedLetterFrequencies; // Frequencies calculated from originalDecryptedJsonString
    private final InetAddress partnerAddress; // The IP address of the other party (Client for S->C, Server for C->S - though server address is fixed)
    private final int partnerPort; // The port of the other party (Client for S->C, Server for C->S)
    private final String transactionKey; // Key used for this transaction (session or fixed)
    private final long creationTimestamp;
    private long lastUpdateTime;

    public PendingMessageInfo(String transactionId, Direction direction, State initialState,
                            JsonObject originalMessageJson, String originalDecryptedJsonString,
                            Map<Character, Integer> expectedLetterFrequencies,
                            InetAddress partnerAddress, int partnerPort, String transactionKey) {
        this.transactionId = transactionId;
        this.direction = direction;
        this.currentState = initialState;
        this.originalMessageJson = originalMessageJson;
        this.originalDecryptedJsonString = originalDecryptedJsonString;
        this.expectedLetterFrequencies = expectedLetterFrequencies;
        this.partnerAddress = partnerAddress;
        this.partnerPort = partnerPort;
        this.transactionKey = transactionKey; // Store the key used for this transaction
        this.creationTimestamp = System.currentTimeMillis();
        this.lastUpdateTime = this.creationTimestamp;
    }

    // --- Getters ---

    public String getTransactionId() {
        return transactionId;
    }

    public Direction getDirection() {
        return direction;
    }

    public State getCurrentState() {
        return currentState;
    }

    public JsonObject getOriginalMessageJson() {
        return originalMessageJson;
    }

    public String getOriginalDecryptedJsonString() {
        return originalDecryptedJsonString;
    }

    public Map<Character, Integer> getExpectedLetterFrequencies() {
        return expectedLetterFrequencies;
    }

    public InetAddress getPartnerAddress() {
        return partnerAddress;
    }

    public int getPartnerPort() {
        return partnerPort;
    }

    public String getTransactionKey() {
        return transactionKey;
    }

    public long getCreationTimestamp() {
        return creationTimestamp;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    // --- Setters ---

    public void setCurrentState(State currentState) {
        this.currentState = currentState;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    // --- Utility ---

    public String getOriginalAction() {
        if (originalMessageJson != null && originalMessageJson.has(Constants.KEY_ACTION)) {
            return originalMessageJson.get(Constants.KEY_ACTION).getAsString();
        }
        return "unknown"; // Or throw exception
    }

    @Override
    public String toString() {
        return "PendingMessageInfo{" +
               "transactionId='" + transactionId + '\'' +
               ", direction=" + direction +
               ", currentState=" + currentState +
               ", originalAction=" + getOriginalAction() +
               ", partner=" + partnerAddress.getHostAddress() + ":" + partnerPort +
               ", created=" + creationTimestamp +
               ", updated=" + lastUpdateTime +
               '}';
    }
}
