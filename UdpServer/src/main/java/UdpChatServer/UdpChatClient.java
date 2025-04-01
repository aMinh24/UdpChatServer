package UdpChatServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger; // Import UUID for temporary client-side IDs
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

public class UdpChatClient {
    private static final Logger log = LoggerFactory.getLogger(UdpChatClient.class);
    private static final Gson gson = new Gson(); // For converting frequency map

    // --- Client Specific Constants ---
    public static final String DEFAULT_SERVER_HOST = "localhost";
    public static final String CMD_LOGIN = "/login";
    public static final String CMD_CREATE_ROOM = "/create";
    public static final String CMD_SEND = "/send";
    public static final String CMD_HELP = "/help";
    public static final String CMD_EXIT = "/exit";
    public static final String CMD_LIST_ROOMS = "/rooms";
    public static final String CMD_LIST_MESSAGES = "/messages";
    public static final String CMD_LOGIN_DESC = "/login <chatid> <password> - Đăng nhập vào hệ thống";
    public static final String CMD_CREATE_ROOM_DESC = "/create <user2> [user3 ...] - Tạo phòng chat với các người dùng được chỉ định";
    public static final String CMD_SEND_DESC = "/send <room_id> <message> - Gửi tin nhắn đến phòng chat";
    public static final String CMD_HELP_DESC = "/help - Hiển thị hướng dẫn này";
    public static final String CMD_EXIT_DESC = "/exit - Thoát chương trình";
    public static final String CMD_LIST_ROOMS_DESC = "/rooms - Hiển thị danh sách phòng chat của bạn";
    public static final String CMD_LIST_MESSAGES_DESC = "/messages <room_id> [time_option] - Hiển thị tin nhắn trong phòng chat";
    public static final String TIME_OPTION_HOURS = "hours";
    public static final String TIME_OPTION_DAYS = "days";
    public static final String TIME_OPTION_WEEKS = "weeks";
    public static final String TIME_OPTION_ALL = "all";
    // --- End Client Specific Constants ---

    private final String serverHost;
    private final int serverPort;
    private final DatagramSocket socket;
    private String sessionKey;
    private String currentChatId;
    private volatile boolean running = true;
    private Thread listenerThread;
    private InetAddress serverAddress;

    // --- State Management for Handshake ---
    // Key: Client-generated temporary UUID for C->S flow
    private final ConcurrentHashMap<String, ClientPendingRequest> pendingClientRequestsByTempId = new ConcurrentHashMap<>();
    // Key: Server-generated transactionId for C->S flow (used after CHARACTER_COUNT is received)
    private final ConcurrentHashMap<String, ClientPendingRequest> pendingClientRequestsByServerId = new ConcurrentHashMap<>();
    // Key: Server-generated transactionId for S->C flow
    private final ConcurrentHashMap<String, String> pendingServerActionsJson = new ConcurrentHashMap<>();

    private static class ClientPendingRequest {
        final String originalAction;
        final String originalSentJson;
        final CountDownLatch latch;
        JsonObject ackData;
        String serverTransactionId; // Set when CHARACTER_COUNT is received

        ClientPendingRequest(String action, String sentJson) {
            this.originalAction = action;
            this.originalSentJson = sentJson;
            this.latch = new CountDownLatch(1);
        }
    }
    // --- End State Management ---

    public UdpChatClient(String serverHost, int serverPort) throws SocketException, UnknownHostException {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.socket = new DatagramSocket();
        this.serverAddress = InetAddress.getByName(serverHost);
        log.info("UDP Chat Client initialized. Server: {}:{}", serverHost, serverPort);
    }

    private class MessageListener implements Runnable {
        @Override
        public void run() {
            byte[] receiveData = new byte[Constants.MAX_UDP_PACKET_SIZE];
            log.info("Message listener started.");

            while (running) {
                try {
                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                    socket.receive(receivePacket);

                    String decryptionKey = sessionKey != null ? sessionKey : Constants.FIXED_LOGIN_KEY_STRING;
                    // Use unified JsonHelper
                    JsonHelper.DecryptedResult decryptedResult = JsonHelper.decryptAndParse(receivePacket, decryptionKey, log);

                    if (decryptedResult == null && sessionKey != null) {
                        log.warn("Decryption failed with session key, trying fixed key...");
                        decryptionKey = Constants.FIXED_LOGIN_KEY_STRING;
                        decryptedResult = JsonHelper.decryptAndParse(receivePacket, decryptionKey, log);
                    }

                    if (decryptedResult == null) {
                        log.error("Failed to decrypt packet from server {}:{}.", receivePacket.getAddress().getHostAddress(), receivePacket.getPort());
                        continue;
                    }

                    JsonObject responseJson = decryptedResult.jsonObject;
                    String decryptedJsonString = decryptedResult.decryptedJsonString;
                    log.debug("Received decrypted JSON: {}", decryptedJsonString);

                    if (!responseJson.has(Constants.KEY_ACTION)) {
                        log.error("Received packet missing 'action' field: {}", decryptedJsonString);
                        continue;
                    }
                    String action = responseJson.get(Constants.KEY_ACTION).getAsString();

                    switch (action) {
                        case Constants.ACTION_CHARACTER_COUNT:
                            handleCharacterCountResponse(responseJson, receivePacket.getAddress(), receivePacket.getPort());
                            continue;
                        case Constants.ACTION_CONFIRM_COUNT:
                            handleConfirmCountResponse(responseJson, receivePacket.getAddress(), receivePacket.getPort());
                            continue;
                        case Constants.ACTION_ACK:
                            handleServerAck(responseJson);
                            continue;
                        case Constants.ACTION_ERROR:
                            handleServerError(responseJson);
                            continue;
                    }

                    // --- Special Handling for Login Success ---
                    if (Constants.ACTION_LOGIN_SUCCESS.equals(action)) {
                        String status = responseJson.has(Constants.KEY_STATUS) ? responseJson.get(Constants.KEY_STATUS).getAsString() : null;
                        if (Constants.STATUS_SUCCESS.equals(status) && responseJson.has(Constants.KEY_DATA)) {
                            JsonObject data = responseJson.getAsJsonObject(Constants.KEY_DATA);
                            if (data.has(Constants.KEY_SESSION_KEY) && data.has(Constants.KEY_CHAT_ID) && data.has("transaction_id")) {
                                // Update session key and chat ID IMMEDIATELY
                                sessionKey = data.get(Constants.KEY_SESSION_KEY).getAsString();
                                currentChatId = data.get(Constants.KEY_CHAT_ID).getAsString();
                                String loginTxId = data.get("transaction_id").getAsString();
                                log.info("Login successful! Updated sessionKey for user '{}'. Session: {}", currentChatId, sessionKey);
                                System.out.println("\nLogin successful! (Session: " + sessionKey + ")");
                                System.out.println("Type /help");
                                System.out.print("> ");

                                // Store pending action and initiate handshake using the NEW key
                                pendingServerActionsJson.put(loginTxId, decryptedJsonString);
                                sendCharacterCount(decryptedJsonString, loginTxId, receivePacket.getAddress(), receivePacket.getPort());
                                continue; // Skip generic S->C handling below
                            } else {
                                log.error("Login success message missing required fields (session_key, chatid, transaction_id) in data.");
                                // Don't proceed with handshake if data is incomplete
                                continue;
                            }
                        } else {
                            // Handle login failure case if needed (though usually sent as ERROR)
                            log.warn("Received login action with status '{}', not processing as success.", status);
                            System.out.println("\nLogin failed: " + (responseJson.has(Constants.KEY_MESSAGE) ? responseJson.get(Constants.KEY_MESSAGE).getAsString() : "Unknown reason"));
                            System.out.print("> ");
                            continue; // Don't proceed
                        }
                    }

                    // --- Handle Other Initial Server Actions (Start of S->C Flow) ---
                    log.info("Received initial action '{}' from server, starting S->C flow", action);
                    String transactionId = null;
                    if (responseJson.has(Constants.KEY_DATA)) {
                        JsonObject data = responseJson.getAsJsonObject(Constants.KEY_DATA);
                        // Server now includes transaction_id in the data payload for S2C initiated messages
                        if (data.has("transaction_id")) {
                            transactionId = data.get("transaction_id").getAsString();
                        }
                    }

                    if (transactionId == null) {
                        log.error("Server message action '{}' missing 'transaction_id'. Cannot proceed.", action);
                        continue;
                    }

                    pendingServerActionsJson.put(transactionId, decryptedJsonString);
                    sendCharacterCount(decryptedJsonString, transactionId, receivePacket.getAddress(), receivePacket.getPort());

                } catch (SocketException se) {
                    if (running) log.error("Socket closed: {}", se.getMessage()); else log.info("Socket closed.");
                    running = false;
                } catch (IOException e) { // Catch IOException specifically
                    if (running) log.error("IOException receiving packet: {}", e.getMessage(), e);
                } catch (JsonSyntaxException e) {
                    log.error("Failed to parse received JSON: {}", e.getMessage());
                } catch (Exception e) { // Catch other potential runtime exceptions
                    if (running) log.error("Unexpected error in listener loop: {}", e.getMessage(), e);
                }
            }
            log.info("Message listener thread stopped.");
        }
    }

    private void handleCharacterCountResponse(JsonObject response, InetAddress serverAddress, int serverPort) {
        if (!response.has(Constants.KEY_DATA)) {
            log.error("Received CHARACTER_COUNT missing 'data' object.");
            return;
        }
        JsonObject data = response.getAsJsonObject(Constants.KEY_DATA);
        
        if (!data.has("transaction_id") || !data.has(Constants.KEY_LETTER_FREQUENCIES) || !data.has(Constants.KEY_ORIGINAL_ACTION)) {
            log.error("Received CHARACTER_COUNT missing transaction_id, frequencies, or original_action within 'data'.");
            return;
        }
        String transactionId = data.get("transaction_id").getAsString();
        String originalAction = data.get(Constants.KEY_ORIGINAL_ACTION).getAsString(); // Get original action from data
        JsonObject serverFrequenciesJson = data.getAsJsonObject(Constants.KEY_LETTER_FREQUENCIES);
        log.debug("Received CHARACTER_COUNT for original action '{}', server tx ID: {}", originalAction, transactionId);

        // Find the pending request using the originalAction received from the server.
        // Iterate through requests stored by temp ID to find the one matching the original action
        // AND hasn't been assigned a server transaction ID yet.
        ClientPendingRequest pendingReq = null;
        String tempIdToRemove = null;
        for (Map.Entry<String, ClientPendingRequest> entry : pendingClientRequestsByTempId.entrySet()) {
            // Match based on the action the server says this CHARACTER_COUNT is for
            // AND ensure we haven't already assigned a server ID to this request
            if (entry.getValue().originalAction.equals(originalAction) && entry.getValue().serverTransactionId == null) {
                pendingReq = entry.getValue();
                tempIdToRemove = entry.getKey();
                log.debug("Found matching pending request (TempID: {}) for original action {}", tempIdToRemove, originalAction);
                break; // Found the first matching pending request
            }
        }

        if (pendingReq == null) {
            log.warn("Received CHARACTER_COUNT for original action '{}', but no matching pending request found or it was already processed (Server TxID: {}).", originalAction, transactionId);
            return;
        }

        // Associate server's transaction ID and move the request to be keyed by it
        pendingReq.serverTransactionId = transactionId;
        if (tempIdToRemove != null) {
            pendingClientRequestsByTempId.remove(tempIdToRemove); // Remove from temp map
        } else {
             log.warn("Could not find tempIdToRemove while processing CHARACTER_COUNT for tx {}", transactionId); // Should not happen if pendingReq was found
        }
        pendingClientRequestsByServerId.put(transactionId, pendingReq); // Store by server ID
        log.debug("Associated server tx ID {} with pending action {} (TempID: {})", transactionId, originalAction, tempIdToRemove);


        // Compare frequencies
        Map<Character, Integer> clientCalculatedFrequencies = CaesarCipher.countLetterFrequencies(pendingReq.originalSentJson);
        Map<Character, Integer> serverFrequencies = parseFrequencyJson(serverFrequenciesJson);
        boolean isValid = areFrequenciesEqual(clientCalculatedFrequencies, serverFrequencies);

        if (!isValid) {
            log.warn("Frequency check failed for transaction: {}. Client: {}, Server: {}",
                     transactionId, clientCalculatedFrequencies, serverFrequencies);
        } else {
             log.debug("Frequency check successful for transaction: {}", transactionId);
        }

        // Send CONFIRM_COUNT back
        JsonObject confirmData = new JsonObject();
        confirmData.addProperty("transaction_id", transactionId);
        confirmData.addProperty(Constants.KEY_CONFIRM, isValid);

        JsonObject confirmRequest = JsonHelper.createRequest(Constants.ACTION_CONFIRM_COUNT, confirmData);
        String key = sessionKey != null ? sessionKey : Constants.FIXED_LOGIN_KEY_STRING;
        JsonHelper.sendPacket(socket, serverAddress, serverPort, confirmRequest, key, log);
        log.info("Sent CONFIRM_COUNT (confirmed: {}) for transaction: {}", isValid, transactionId);
    }

    private void handleConfirmCountResponse(JsonObject response, InetAddress serverAddress, int serverPort) {
        if (!response.has(Constants.KEY_DATA)) {
            log.error("Received CONFIRM_COUNT missing 'data' object.");
            return;
        }
        JsonObject data = response.getAsJsonObject(Constants.KEY_DATA);
        
        if (!data.has("transaction_id") || !data.has(Constants.KEY_CONFIRM)) {
            log.error("Received CONFIRM_COUNT missing 'transaction_id' or 'confirm' field within 'data'.");
            return;
        }
        String transactionId = data.get("transaction_id").getAsString();
        boolean confirmed = data.get(Constants.KEY_CONFIRM).getAsBoolean();
        log.debug("Received CONFIRM_COUNT for transaction: {} (confirmed: {})", transactionId, confirmed);

        String pendingJson = pendingServerActionsJson.remove(transactionId); // Use correct map
        if (pendingJson == null) {
            log.warn("No pending server action found for transaction: {}", transactionId);
        }

        String ackStatus = Constants.STATUS_FAILURE;
        String ackMessage = null;

        if (confirmed) {
            if (pendingJson != null) {
                processServerAction(pendingJson);
                ackStatus = Constants.STATUS_SUCCESS;
            } else {
                ackMessage = "Client lost original action state.";
                log.warn("Cannot process action for transaction {} because pending JSON was lost.", transactionId);
            }
        } else {
            ackStatus = Constants.STATUS_CANCELLED;
            ackMessage = "Frequency mismatch detected by server.";
            log.warn("Server indicated frequency mismatch for transaction: {}, not processing", transactionId);
        }
        sendAck(transactionId, ackStatus, ackMessage, serverAddress, serverPort);
    }

    private void handleServerAck(JsonObject responseJson) {
        // For ACK, status is at top level, but transaction_id is in data
        if (!responseJson.has(Constants.KEY_STATUS)) {
            log.error("Received ACK missing status field.");
            return;
        }
        String status = responseJson.get(Constants.KEY_STATUS).getAsString();
        
        if (!responseJson.has(Constants.KEY_DATA)) {
            log.error("Received ACK missing 'data' object.");
            return;
        }
        JsonObject data = responseJson.getAsJsonObject(Constants.KEY_DATA);
        
        if (!data.has("transaction_id")) {
            log.error("Received ACK missing 'transaction_id' field within 'data'.");
            return;
        }
        String transactionId = data.get("transaction_id").getAsString();
        log.info("Received Server ACK for transaction: {} with status: {}", transactionId, status);

        // Find the pending request using the server's transaction ID
        ClientPendingRequest pendingReq = pendingClientRequestsByServerId.remove(transactionId); // Use the correct map

        if (pendingReq != null) {
            pendingReq.ackData = responseJson;
            pendingReq.latch.countDown();
            log.debug("Signaled completion for pending request associated with transaction {}", transactionId);
        } else {
            log.warn("Received ACK for unknown, timed-out, or already processed transaction: {}", transactionId);
        }
    }

    private void handleServerError(JsonObject responseJson) {
        String errorMessage = responseJson.has(Constants.KEY_MESSAGE) ? responseJson.get(Constants.KEY_MESSAGE).getAsString() : "Unknown server error";
        String originalAction = responseJson.has(Constants.KEY_ORIGINAL_ACTION) ? responseJson.get(Constants.KEY_ORIGINAL_ACTION).getAsString() : "unknown";
        log.error("Received ERROR from server for action '{}': {}", originalAction, errorMessage);
        System.out.println("\nServer Error (" + originalAction + "): " + errorMessage);

        // Attempt to find and signal failure for a pending request
        ClientPendingRequest pendingReqToFail = null;
        String tempIdToFail = null;
        String serverIdToFail = null;

        // Try finding by server transaction ID first (if error includes it in data)
        if (responseJson.has(Constants.KEY_DATA)) {
            JsonObject data = responseJson.getAsJsonObject(Constants.KEY_DATA);
            if (data.has("transaction_id")) {
                serverIdToFail = data.get("transaction_id").getAsString();
                pendingReqToFail = pendingClientRequestsByServerId.get(serverIdToFail);
            }
        }

        // If not found by server ID, try finding by original action in the temp map
        if (pendingReqToFail == null) {
            for (Map.Entry<String, ClientPendingRequest> entry : pendingClientRequestsByTempId.entrySet()) {
                 if (entry.getValue().originalAction.equals(originalAction)) {
                     pendingReqToFail = entry.getValue();
                     tempIdToFail = entry.getKey();
                     // If found here, it might already have a server ID associated
                     serverIdToFail = pendingReqToFail.serverTransactionId;
                     break;
                 }
            }
        }

        if (pendingReqToFail != null) {
             log.warn("Signaling failure for pending action {} due to server error.", originalAction);
             pendingReqToFail.ackData = responseJson; // Store error info
             pendingReqToFail.latch.countDown(); // Signal completion (as failure)
             // Clean up from maps using the IDs we found
             if (tempIdToFail != null) pendingClientRequestsByTempId.remove(tempIdToFail);
             if (serverIdToFail != null) pendingClientRequestsByServerId.remove(serverIdToFail);

        } else {
             log.warn("Could not find pending request for action '{}' to signal server error.", originalAction);
        }

        System.out.print("> ");
    }

    private void processServerAction(String jsonString) {
        // (Logic remains the same, uses Constants already)
        try {
            JsonObject responseJson = JsonParser.parseString(jsonString).getAsJsonObject();
            String action = responseJson.get(Constants.KEY_ACTION).getAsString();
            String status = responseJson.has(Constants.KEY_STATUS) ? responseJson.get(Constants.KEY_STATUS).getAsString() : null;
            String message = responseJson.has(Constants.KEY_MESSAGE) ? responseJson.get(Constants.KEY_MESSAGE).getAsString() : null;
            log.debug("Processing confirmed server action: {}", action);
            switch (action) {
                 case Constants.ACTION_LOGIN_SUCCESS: if (Constants.STATUS_SUCCESS.equals(status)) { JsonObject d=responseJson.getAsJsonObject(Constants.KEY_DATA); sessionKey=d.get(Constants.KEY_SESSION_KEY).getAsString(); currentChatId=d.get(Constants.KEY_CHAT_ID).getAsString(); System.out.println("\nLogin successful! (Session: "+sessionKey+")"); System.out.println("Type /help"); } else { System.out.println("\nLogin failed: "+message); } break;
                 case Constants.ACTION_ROOM_CREATED: if (Constants.STATUS_SUCCESS.equals(status)) { JsonObject d=responseJson.getAsJsonObject(Constants.KEY_DATA); String r=d.get(Constants.KEY_ROOM_ID).getAsString(); System.out.println("\nRoom created! ID: "+r); System.out.println("Use: /send "+r+" <msg>"); } else { System.out.println("\nRoom creation failed: "+message); } break;
                 case Constants.ACTION_RECEIVE_MESSAGE: if (Constants.STATUS_SUCCESS.equals(status)) { JsonObject d=responseJson.getAsJsonObject(Constants.KEY_DATA); String r=d.get(Constants.KEY_ROOM_ID).getAsString(); String s=d.get(Constants.KEY_SENDER_CHAT_ID).getAsString(); String c=d.get(Constants.KEY_CONTENT).getAsString(); String t=d.get(Constants.KEY_TIMESTAMP).getAsString(); String dt=t; try { dt=new SimpleDateFormat("HH:mm:ss").format(java.util.Date.from(Instant.parse(t))); } catch (Exception e){} System.out.printf("\n[%s] %s @ %s: %s\n",r,s,dt,c); } break;
                 case Constants.ACTION_ROOMS_LIST: if (Constants.STATUS_SUCCESS.equals(status)) { JsonObject d=responseJson.getAsJsonObject(Constants.KEY_DATA); JsonArray r=d.getAsJsonArray("rooms"); System.out.println("\nYour rooms:"); if(r.size()==0)System.out.println("None."); else for(int i=0;i<r.size();i++)System.out.println((i+1)+". "+r.get(i).getAsString()); } else { System.out.println("\nFailed list rooms: "+message); } break;
                 case Constants.ACTION_MESSAGES_LIST: if (Constants.STATUS_SUCCESS.equals(status)) { JsonObject d=responseJson.getAsJsonObject(Constants.KEY_DATA); String r=d.get("room_id").getAsString(); JsonArray m=d.getAsJsonArray("messages"); System.out.println("\nMsgs in "+r+":"); if(m.size()==0)System.out.println("None."); else for(JsonElement e:m){JsonObject o=e.getAsJsonObject(); String s=o.get("sender_chatid").getAsString(); String c=o.get("content").getAsString(); String t=o.get("timestamp").getAsString(); String dt=t; try { dt=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date.from(Instant.parse(t))); } catch (Exception ex){} System.out.printf("[%s] %s: %s\n",dt,s,c); } } else { System.out.println("\nFailed get msgs: "+message); } break;
                 default: log.warn("Unhandled server action: {}", action); if (message!=null) System.out.println("\nServer msg: "+message); break;
            }
            System.out.print("> ");
        } catch (Exception e) { log.error("Error processing server JSON: {}", e.getMessage(), e); }
    }

    private void sendCharacterCount(String receivedJsonString, String transactionId, InetAddress serverAddress, int serverPort) {
        try {
            Map<Character, Integer> freqMap = CaesarCipher.countLetterFrequencies(receivedJsonString); // Use unified
            JsonObject frequenciesJson = new JsonObject();
            for (Map.Entry<Character, Integer> entry : freqMap.entrySet()) {
                frequenciesJson.addProperty(String.valueOf(entry.getKey()), entry.getValue());
            }
            JsonObject data = new JsonObject();
            data.addProperty("transaction_id", transactionId);
            data.add(Constants.KEY_LETTER_FREQUENCIES, frequenciesJson);
            JsonObject request = JsonHelper.createRequest(Constants.ACTION_CHARACTER_COUNT, data); // Use unified
            JsonHelper.sendPacket(socket, serverAddress, serverPort, request, sessionKey, log); // Use sessionKey
            log.info("Sent CHARACTER_COUNT for transaction: {}", transactionId);
        } catch (Exception e) {
            log.error("Error sending CHARACTER_COUNT for transaction {}: {}", transactionId, e.getMessage(), e);
        }
    }

    private void sendAck(String transactionId, String status, String message, InetAddress serverAddress, int serverPort) {
        try {
            JsonObject data = new JsonObject();
            data.addProperty("transaction_id", transactionId);
            JsonObject request = JsonHelper.createReply(Constants.ACTION_ACK, status, message, data); // Use unified
            JsonHelper.sendPacket(socket, serverAddress, serverPort, request, sessionKey, log); // Use sessionKey
            log.info("Sent ACK for transaction: {} with status: {}", transactionId, status);
        } catch (Exception e) {
             log.error("Error sending ACK for transaction {}: {}", transactionId, e.getMessage(), e);
        }
    }

    private void login(String chatId, String password) {
        if (sessionKey != null) { System.out.println("Already logged in!"); return; }
        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, chatId);
        data.addProperty(Constants.KEY_PASSWORD, password);
        JsonObject request = JsonHelper.createRequest(Constants.ACTION_LOGIN, data);
        // Send directly, don't wait for ACK here. Listener handles response.
        boolean sent = JsonHelper.sendPacket(socket, serverAddress, serverPort, request, Constants.FIXED_LOGIN_KEY_STRING, log);

        if (sent) {
            log.info("Sent login request for user: {}", chatId);
            System.out.println("Login request sent. Waiting for server response...");
            // No waiting here, response handled by listener
        } else {
            log.error("Failed to send login request for user: {}", chatId);
            System.out.println("Error sending login request.");
            System.out.print("> "); // Prompt user again if send failed
        }
    }

    private void createRoom(String[] participants) {
        if (sessionKey == null) { System.out.println("Login first!"); return; }
        if (participants == null || participants.length == 0) { System.out.println("Usage: /create <p1> [p2...]"); return; }
        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, currentChatId);
        JsonArray pa = new JsonArray(); for (String p : participants) pa.add(p.trim()); data.add(Constants.KEY_PARTICIPANTS, pa);
        JsonObject request = JsonHelper.createRequest(Constants.ACTION_CREATE_ROOM, data);
        sendClientRequestWithAck(request, Constants.ACTION_CREATE_ROOM, sessionKey);
    }

    private void sendMessage(String roomId, String content) {
        if (sessionKey == null) { System.out.println("Login first!"); return; }
        if (roomId == null || roomId.trim().isEmpty() || content == null || content.isEmpty()) { System.out.println("Usage: /send <room> <msg>"); return; }
        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, currentChatId);
        data.addProperty(Constants.KEY_ROOM_ID, roomId.trim());
        data.addProperty(Constants.KEY_CONTENT, content);
        JsonObject request = JsonHelper.createRequest(Constants.ACTION_SEND_MESSAGE, data);
        sendClientRequestWithAck(request, Constants.ACTION_SEND_MESSAGE, sessionKey);
    }

    private void getRooms() {
        if (sessionKey == null) { System.out.println("Login first!"); return; }
        JsonObject data = new JsonObject(); data.addProperty(Constants.KEY_CHAT_ID, currentChatId);
        JsonObject request = JsonHelper.createRequest(Constants.ACTION_GET_ROOMS, data);
        sendClientRequestWithAck(request, Constants.ACTION_GET_ROOMS, sessionKey);
    }

    private void getMessages(String roomId, String timeOption) {
        if (sessionKey == null) { System.out.println("Login first!"); return; }
        if (roomId == null || roomId.trim().isEmpty()) { System.out.println("Usage: /msg <room> [time]"); return; }
        JsonObject data = new JsonObject(); data.addProperty(Constants.KEY_CHAT_ID, currentChatId); data.addProperty(Constants.KEY_ROOM_ID, roomId.trim());
        if (timeOption != null && !timeOption.isEmpty() && !timeOption.equalsIgnoreCase(TIME_OPTION_ALL)) {
            String ft = parseTimeOption(timeOption); if (ft != null) data.addProperty("from_time", ft); else return;
        }
        JsonObject request = JsonHelper.createRequest(Constants.ACTION_GET_MESSAGES, data);
        sendClientRequestWithAck(request, Constants.ACTION_GET_MESSAGES, sessionKey);
    }

    private void sendClientRequestWithAck(JsonObject request, String action, String encryptionKey) {
        String tempId = UUID.randomUUID().toString(); // Use UUID for better uniqueness
        String jsonToSend = gson.toJson(request);
        ClientPendingRequest pendingReq = new ClientPendingRequest(action, jsonToSend);
        pendingClientRequestsByTempId.put(tempId, pendingReq); // Store by temp ID initially

        try {
            JsonHelper.sendPacket(socket, serverAddress, serverPort, request, encryptionKey, log);
            log.info("Sent action: {} (TempID: {}) - waiting for server CHARACTER_COUNT...", action, tempId);

            boolean completed = pendingReq.latch.await(15, TimeUnit.SECONDS); // Wait for ACK signal

            // If completed, the request should have been moved to pendingClientRequestsByServerId
            // and removed from there by handleServerAck. If not completed, it might still be in temp map.
            pendingClientRequestsByTempId.remove(tempId); // Ensure cleanup from temp map if timeout/error occurred before CHARACTER_COUNT
            if (pendingReq.serverTransactionId != null) {
                 pendingClientRequestsByServerId.remove(pendingReq.serverTransactionId); // Ensure cleanup from main map
            }


            if (!completed) {
                log.warn("Timeout waiting for server ACK for action: {} (TempID: {})", action, tempId);
                System.out.println("\nRequest timed out. Server did not respond.");
            } else {
                JsonObject ackResponse = pendingReq.ackData;
                if (ackResponse != null && ackResponse.has(Constants.KEY_STATUS)) {
                    String status = ackResponse.get(Constants.KEY_STATUS).getAsString();
                    if (!Constants.STATUS_SUCCESS.equals(status)) {
                        String serverMessage = ackResponse.has(Constants.KEY_MESSAGE) ? ackResponse.get(Constants.KEY_MESSAGE).getAsString() : "No details";
                        log.warn("Action {} (TempID: {}) failed on server. Status: {}, Message: {}", action, tempId, status, serverMessage);
                        System.out.println("\nServer couldn't process request: " + serverMessage + " (Status: " + status + ")");
                    } else {
                        log.info("Action {} (TempID: {}) acknowledged successfully by server.", action, tempId);
                        if (action.equals(Constants.ACTION_SEND_MESSAGE)) System.out.println("\nMessage sent successfully!");
                    }
                } else {
                     log.error("ACK received for action {} (TempID: {}) but status missing/invalid.", action, tempId);
                     System.out.println("\nReceived invalid ACK from server.");
                }
            }
        // Removed unreachable IOException catch block, as sendPacket handles it internally.
        } catch (InterruptedException e) {
             log.warn("Interrupted waiting for ACK for {} (TempID: {})", action, tempId);
             System.out.println("\nRequest interrupted.");
             pendingClientRequestsByTempId.remove(tempId); // Cleanup
             Thread.currentThread().interrupt();
        } catch (Exception e) {
             log.error("Unexpected error sending {} (TempID: {}): {}", action, tempId, e.getMessage(), e);
             System.out.println("Error: " + e.getMessage());
             pendingClientRequestsByTempId.remove(tempId); // Cleanup
        } finally {
             System.out.print("> ");
        }
    }

    private String parseTimeOption(String timeOption) {
        // (Logic remains the same)
        if (timeOption == null) return null; timeOption = timeOption.trim().toLowerCase(); if (timeOption.equals(TIME_OPTION_ALL)) return null;
        Pattern p = Pattern.compile("^(\\d+)(hours?|days?|weeks?)$"); Matcher m = p.matcher(timeOption);
        Instant now = Instant.now(), from = null;
        if (m.matches()) { try { int a = Integer.parseInt(m.group(1)); String u = m.group(2); if (u.startsWith("h")) from=now.minus(a,ChronoUnit.HOURS); else if (u.startsWith("d")) from=now.minus(a,ChronoUnit.DAYS); else if (u.startsWith("w")) from=now.minus(a*7,ChronoUnit.DAYS); } catch (NumberFormatException e){System.out.println("Invalid #: "+timeOption); return null;} }
        else { try { from = Instant.parse(timeOption); } catch (Exception e1) { try { from = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(timeOption).toInstant(); } catch (Exception e2) { System.out.println("Invalid time format."); return null; } } }
        return from != null ? from.toString() : null;
    }

    private void showHelp() {
        // (Logic remains the same)
        System.out.println("\nAvailable commands:"); System.out.println(CMD_LOGIN_DESC); System.out.println(CMD_CREATE_ROOM_DESC); System.out.println(CMD_SEND_DESC); System.out.println(CMD_LIST_ROOMS_DESC); System.out.println(CMD_LIST_MESSAGES_DESC); System.out.println("  Time options: '12"+TIME_OPTION_HOURS+"', '7"+TIME_OPTION_DAYS+"', '3"+TIME_OPTION_WEEKS+"', '"+TIME_OPTION_ALL+"', or ISO/yyyy-MM-dd HH:mm:ss"); System.out.println(CMD_HELP_DESC); System.out.println(CMD_EXIT_DESC); System.out.print("> ");
    }

    public void start() {
        // (Logic remains the same)
        listenerThread = new Thread(new MessageListener(), "ClientListener"); listenerThread.setDaemon(true); listenerThread.start(); showHelp();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in))) { String l; while (running && (l=r.readLine())!=null) { String cl=l.trim(); if(cl.isEmpty()){System.out.print("> ");continue;} String[] p=cl.split("\\s+",2); String cmd=p[0].toLowerCase(); String args=p.length>1?p[1]:""; switch(cmd){ case CMD_LOGIN: String[] la=args.split("\\s+",2); if(la.length!=2)System.out.println("Usage: /login <id> <pw>"); else login(la[0],la[1]); break; case CMD_CREATE_ROOM: if(args.isEmpty())System.out.println("Usage: /create <p1> [p2...]"); else createRoom(args.split("\\s+")); break; case CMD_SEND: String[] sa=args.split("\\s+",2); if(sa.length!=2)System.out.println("Usage: /send <room> <msg>"); else sendMessage(sa[0],sa[1]); break; case CMD_LIST_ROOMS: getRooms(); break; case CMD_LIST_MESSAGES: String[] ma=args.split("\\s+",2); if(ma.length<1||ma[0].isEmpty())System.out.println("Usage: /msg <room> [time]"); else getMessages(ma[0],ma.length>1?ma[1]:TIME_OPTION_ALL); break; case CMD_HELP: showHelp(); break; case CMD_EXIT: System.out.println("Exiting..."); running=false; break; default: System.out.println("Invalid command. /help"); System.out.print("> "); break; } } }
        catch (IOException e) { log.error("Input error: {}", e.getMessage()); } finally { cleanup(); }
    }

    private void cleanup() {
        // (Logic remains the same)
        running = false; if (socket != null && !socket.isClosed()) socket.close(); log.info("Client closed."); System.out.println("\nClient connection closed.");
    }

    private Map<Character, Integer> parseFrequencyJson(JsonObject freqJson) {
        Map<Character, Integer> map = new ConcurrentHashMap<>(); if (freqJson != null) { for (Map.Entry<String, JsonElement> entry : freqJson.entrySet()) { if (entry.getKey().length() == 1) { try { map.put(entry.getKey().charAt(0), entry.getValue().getAsInt()); } catch (Exception e) { log.warn("Invalid freq value {}: {}", entry.getKey(), entry.getValue()); } } } } return map;
    }

    private boolean areFrequenciesEqual(Map<Character, Integer> map1, Map<Character, Integer> map2) { return map1 != null && map1.equals(map2); }

    // public static void main(String[] args) {
    //     // (Logic remains the same)
    //     String h=DEFAULT_SERVER_HOST; int p=Constants.DEFAULT_SERVER_PORT; if(args.length>=1)h=args[0]; if(args.length>=2)try{p=Integer.parseInt(args[1]);}catch(NumberFormatException e){System.err.println("Invalid port: "+args[1]);} try{new UdpChatClient(h,p).start();} catch(SocketException e){System.err.println("Socket error: "+e.getMessage());log.error("SocketException",e);} catch(Exception e){System.err.println("Error: "+e.getMessage());log.error("Error",e);}
    // }
}
