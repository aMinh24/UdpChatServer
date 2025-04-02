package UdpChatClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.text.SimpleDateFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class CommandProcessor {

    private final ClientState clientState;
    private final HandshakeManager handshakeManager;

    // --- Client Specific Constants ---
    // Copied from UdpChatClient for command definitions
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

    public CommandProcessor(ClientState clientState, HandshakeManager handshakeManager) {
        this.clientState = clientState;
        this.handshakeManager = handshakeManager;
    }

    public void processCommand(String line) {
        String trimmedLine = line.trim();
        if (trimmedLine.isEmpty()) {
            System.out.print("> ");
            return;
        }

        String[] parts = trimmedLine.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        switch (command) {
            case CMD_LOGIN:
                String[] loginArgs = args.split("\\s+", 2);
                if (loginArgs.length != 2) {
                    System.out.println("Usage: /login <chatid> <password>");
                    System.out.print("> ");
                } else {
                    login(loginArgs[0], loginArgs[1]);
                }
                break;
            case CMD_CREATE_ROOM:
                if (args.isEmpty()) {
                    System.out.println("Usage: /create <participant1> [participant2...]");
                    System.out.print("> ");
                } else {
                    createRoom(args.split("\\s+"));
                }
                break;
            case CMD_SEND:
                String[] sendArgs = args.split("\\s+", 2);
                if (sendArgs.length != 2) {
                    System.out.println("Usage: /send <room_id> <message>");
                    System.out.print("> ");
                } else {
                    sendMessage(sendArgs[0], sendArgs[1]);
                }
                break;
            case CMD_LIST_ROOMS:
                getRooms();
                break;
            case CMD_LIST_MESSAGES:
                String[] msgArgs = args.split("\\s+", 2);
                if (msgArgs.length < 1 || msgArgs[0].isEmpty()) {
                    System.out.println("Usage: /messages <room_id> [time_option]");
                    System.out.print("> ");
                } else {
                    // Default to "all" if time option is missing
                    getMessages(msgArgs[0], msgArgs.length > 1 ? msgArgs[1] : TIME_OPTION_ALL);
                }
                break;
            case CMD_HELP:
                showHelp();
                break;
            case CMD_EXIT:
                System.out.println("Exiting...");
                clientState.setRunning(false); // Signal the main loop to stop
                // No need to print "> " here as the loop will terminate
                break;
            default:
                System.out.println("Invalid command. Type /help for available commands.");
                System.out.print("> ");
                break;
        }
    }

    private void login(String chatId, String password) {
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
    }

    private void createRoom(String[] participants) {
        if (clientState.getSessionKey() == null) {
            System.out.println("You must be logged in to create a room. Use /login <id> <pw>");
            System.out.print("> ");
            return;
        }
        if (participants == null || participants.length == 0) {
             System.out.println("Usage: /create <participant1> [participant2...]"); // Should be caught by caller, but double-check
             System.out.print("> ");
             return;
        }
        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, clientState.getCurrentChatId());
        JsonArray participantsArray = new JsonArray();
        for (String p : participants) {
            participantsArray.add(p.trim());
        }
        data.add(Constants.KEY_PARTICIPANTS, participantsArray);
        JsonObject request = JsonHelper.createRequest(Constants.ACTION_CREATE_ROOM, data);
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_CREATE_ROOM, clientState.getSessionKey());
    }

    private void sendMessage(String roomId, String content) {
        if (clientState.getSessionKey() == null) {
            System.out.println("You must be logged in to send messages. Use /login <id> <pw>");
            System.out.print("> ");
            return;
        }
        if (roomId == null || roomId.trim().isEmpty() || content == null || content.isEmpty()) {
            System.out.println("Usage: /send <room_id> <message>"); // Should be caught by caller, but double-check
            System.out.print("> ");
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, clientState.getCurrentChatId());
        data.addProperty(Constants.KEY_ROOM_ID, roomId.trim());
        data.addProperty(Constants.KEY_CONTENT, content);
        JsonObject request = JsonHelper.createRequest(Constants.ACTION_SEND_MESSAGE, data);
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_SEND_MESSAGE, clientState.getSessionKey());
    }

    private void getRooms() {
        if (clientState.getSessionKey() == null) {
            System.out.println("You must be logged in to list rooms. Use /login <id> <pw>");
            System.out.print("> ");
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, clientState.getCurrentChatId());
        JsonObject request = JsonHelper.createRequest(Constants.ACTION_GET_ROOMS, data);
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_GET_ROOMS, clientState.getSessionKey());
    }

    private void getMessages(String roomId, String timeOption) {
        if (clientState.getSessionKey() == null) {
            System.out.println("You must be logged in to list messages. Use /login <id> <pw>");
            System.out.print("> ");
            return;
        }
         if (roomId == null || roomId.trim().isEmpty()) {
             System.out.println("Usage: /messages <room_id> [time_option]"); // Should be caught by caller
             System.out.print("> ");
             return;
         }
        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, clientState.getCurrentChatId());
        data.addProperty(Constants.KEY_ROOM_ID, roomId.trim());

        // Only add from_time if a specific time option (not "all") is provided and valid
        if (timeOption != null && !timeOption.isEmpty() && !timeOption.equalsIgnoreCase(TIME_OPTION_ALL)) {
            String fromTimeIso = parseTimeOption(timeOption);
            if (fromTimeIso != null) {
                data.addProperty(Constants.KEY_FROM_TIME, fromTimeIso);
            } else {
                // parseTimeOption prints error, just return
                System.out.print("> ");
                return;
            }
        }
        // If timeOption is "all" or null/empty, don't add from_time

        JsonObject request = JsonHelper.createRequest(Constants.ACTION_GET_MESSAGES, data);
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_GET_MESSAGES, clientState.getSessionKey());
    }

    private String parseTimeOption(String timeOption) {
        if (timeOption == null) return null;
        timeOption = timeOption.trim().toLowerCase();
        if (timeOption.equals(TIME_OPTION_ALL)) return null; // "all" means no time filter

        // Regex for "12hours", "7days", "3weeks" (plural optional)
        Pattern durationPattern = Pattern.compile("^(\\d+)\\s*(hours?|days?|weeks?)$");
        Matcher durationMatcher = durationPattern.matcher(timeOption);

        Instant now = Instant.now();
        Instant fromInstant = null;

        if (durationMatcher.matches()) {
            try {
                int amount = Integer.parseInt(durationMatcher.group(1));
                String unit = durationMatcher.group(2);
                if (unit.startsWith("h")) {
                    fromInstant = now.minus(amount, ChronoUnit.HOURS);
                } else if (unit.startsWith("d")) {
                    fromInstant = now.minus(amount, ChronoUnit.DAYS);
                } else if (unit.startsWith("w")) {
                    fromInstant = now.minus(amount * 7L, ChronoUnit.DAYS); // Use long for multiplication
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid number in time option: " + timeOption);
                return null;
            } catch (Exception e) { // Catch potential arithmetic overflow etc.
                 System.out.println("Error calculating time from option: " + timeOption);
                 return null;
            }
        } else {
            // Try parsing as ISO 8601 or yyyy-MM-dd HH:mm:ss
            try {
                // Attempt ISO 8601 first (more standard)
                fromInstant = Instant.parse(timeOption);
            } catch (Exception e1) {
                try {
                    // Attempt specific format
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    // sdf.setTimeZone(TimeZone.getDefault()); // Optional: Be explicit about timezone if needed
                    fromInstant = sdf.parse(timeOption).toInstant();
                } catch (Exception e2) {
                    System.out.println("Invalid time format. Use e.g., '12hours', '7days', '3weeks', 'all', ISO format, or 'yyyy-MM-dd HH:mm:ss'.");
                    return null;
                }
            }
        }

        return fromInstant != null ? fromInstant.toString() : null;
    }

    public void showHelp() {
        System.out.println("\nAvailable commands:");
        System.out.println("  " + CMD_LOGIN_DESC);
        System.out.println("  " + CMD_CREATE_ROOM_DESC);
        System.out.println("  " + CMD_SEND_DESC);
        System.out.println("  " + CMD_LIST_ROOMS_DESC);
        System.out.println("  " + CMD_LIST_MESSAGES_DESC);
        System.out.println("    Time options: e.g., '12"+TIME_OPTION_HOURS+"', '7"+TIME_OPTION_DAYS+"', '3"+TIME_OPTION_WEEKS+"', '"+TIME_OPTION_ALL+"', ISO format, or 'yyyy-MM-dd HH:mm:ss'");
        System.out.println("  " + CMD_HELP_DESC);
        System.out.println("  " + CMD_EXIT_DESC);
        System.out.print("> ");
    }
}
