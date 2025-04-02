package UdpChatClient;

import java.text.SimpleDateFormat;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class MessageProcessor {
    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);
    private final ClientState clientState; // Needed to update state on login success

    public MessageProcessor(ClientState clientState) {
        this.clientState = clientState;
    }

    /**
     * Processes the JSON content of a server message after it has been confirmed
     * by the handshake protocol (S->C flow).
     *
     * @param jsonString The confirmed JSON string received from the server.
     */
    public void processServerAction(String jsonString) {
        try {
            JsonObject responseJson = JsonParser.parseString(jsonString).getAsJsonObject();
            if (!responseJson.has(Constants.KEY_ACTION)) {
                log.error("Confirmed server action JSON missing 'action' field: {}", jsonString);
                return;
            }
            String action = responseJson.get(Constants.KEY_ACTION).getAsString();
            String status = responseJson.has(Constants.KEY_STATUS) ? responseJson.get(Constants.KEY_STATUS).getAsString() : null; // Status might not always be present in S->C initial actions
            String message = responseJson.has(Constants.KEY_MESSAGE) ? responseJson.get(Constants.KEY_MESSAGE).getAsString() : null;
            JsonObject data = responseJson.has(Constants.KEY_DATA) ? responseJson.getAsJsonObject(Constants.KEY_DATA) : null;

            log.info("Processing confirmed server action: {}", action);

            // Note: Login success is now handled directly in HandshakeManager's ACK handler
            // to update sessionKey immediately. We don't process ACTION_LOGIN_SUCCESS here.

            switch (action) {
                case Constants.ACTION_ROOM_CREATED:
                    if (Constants.STATUS_SUCCESS.equals(status) && data != null && data.has(Constants.KEY_ROOM_ID)) {
                        String roomId = data.get(Constants.KEY_ROOM_ID).getAsString();
                        System.out.println("\nRoom created successfully! ID: " + roomId);
                        System.out.println("You can now send messages using: /send " + roomId + " <your_message>");
                    } else {
                        System.out.println("\nRoom creation failed: " + (message != null ? message : "Unknown reason"));
                    }
                    break;

                case Constants.ACTION_RECEIVE_MESSAGE:
                    // RECEIVE_MESSAGE comes directly from server (S->C), status might not be relevant here, focus on data
                    if (data != null && data.has(Constants.KEY_ROOM_ID) && data.has(Constants.KEY_SENDER_CHAT_ID) && data.has(Constants.KEY_CONTENT) && data.has(Constants.KEY_TIMESTAMP)) {
                        String roomId = data.get(Constants.KEY_ROOM_ID).getAsString();
                        String sender = data.get(Constants.KEY_SENDER_CHAT_ID).getAsString();
                        String content = data.get(Constants.KEY_CONTENT).getAsString();
                        String timestampStr = data.get(Constants.KEY_TIMESTAMP).getAsString();
                        String formattedTime = formatTimestamp(timestampStr, "HH:mm:ss");
                        System.out.printf("\n[%s] %s @ %s: %s\n", roomId, sender, formattedTime, content);
                    } else {
                        log.error("Received invalid RECEIVE_MESSAGE data: {}", jsonString);
                        System.out.println("\nReceived incomplete message data from server.");
                    }
                    break;

                case Constants.ACTION_ROOMS_LIST:
                    // ROOMS_LIST comes directly from server (S->C)
                     if (data != null && data.has("rooms")) {
                        JsonArray roomsArray = data.getAsJsonArray("rooms");
                        System.out.println("\nYour rooms:");
                        if (roomsArray.size() == 0) {
                            System.out.println("  (No rooms found)");
                        } else {
                            for (int i = 0; i < roomsArray.size(); i++) {
                                System.out.println("  " + (i + 1) + ". " + roomsArray.get(i).getAsString());
                            }
                        }
                    } else {
                         log.error("Received invalid ROOMS_LIST data: {}", jsonString);
                         System.out.println("\nFailed to retrieve room list from server.");
                    }
                    break;

                case Constants.ACTION_MESSAGES_LIST:
                    // MESSAGES_LIST comes directly from server (S->C)
                    if (data != null && data.has("room_id") && data.has("messages")) {
                        String roomId = data.get("room_id").getAsString();
                        JsonArray messagesArray = data.getAsJsonArray("messages");
                        System.out.println("\nMessages in room '" + roomId + "':");
                        if (messagesArray.size() == 0) {
                            System.out.println("  (No messages found)");
                        } else {
                            for (JsonElement msgElement : messagesArray) {
                                JsonObject msgObject = msgElement.getAsJsonObject();
                                String sender = msgObject.get("sender_chatid").getAsString();
                                String content = msgObject.get("content").getAsString();
                                String timestampStr = msgObject.get("timestamp").getAsString();
                                String formattedTime = formatTimestamp(timestampStr, "yyyy-MM-dd HH:mm:ss");
                                System.out.printf("  [%s] %s: %s\n", formattedTime, sender, content);
                            }
                        }
                    } else {
                        log.error("Received invalid MESSAGES_LIST data: {}", jsonString);
                        System.out.println("\nFailed to retrieve messages from server.");
                    }
                    break;

                default:
                    log.warn("Unhandled confirmed server action: {}", action);
                    if (message != null) {
                        System.out.println("\nServer message (" + action + "): " + message);
                    } else {
                        System.out.println("\nReceived unhandled action from server: " + action);
                    }
                    break;
            }
            System.out.print("> "); // Prompt for next user input
        } catch (Exception e) {
            log.error("Error processing confirmed server JSON: {}", e.getMessage(), e);
            System.out.println("\nError processing message from server.");
            System.out.print("> ");
        }
    }

    private String formatTimestamp(String isoTimestamp, String pattern) {
        try {
            Instant instant = Instant.parse(isoTimestamp);
            // Ensure correct timezone handling if needed, default is system default
            return new SimpleDateFormat(pattern).format(java.util.Date.from(instant));
        } catch (Exception e) {
            log.warn("Failed to parse or format timestamp '{}': {}", isoTimestamp, e.getMessage());
            return isoTimestamp; // Return original string if parsing fails
        }
    }
}
