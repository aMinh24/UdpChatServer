package UdpChatClient.command;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

import UdpChatClient.ClientState;
import UdpChatClient.Constants;
import UdpChatClient.HandshakeManager;
import UdpChatClient.JsonHelper;

public class ListMessagesHandler implements CommandHandler {

    @Override
    public void handle(String args, ClientState clientState, HandshakeManager handshakeManager) {
        String[] msgArgs = args.split("\\s+", 2);
        if (msgArgs.length < 1 || msgArgs[0].trim().isEmpty()) {
            System.out.println("Usage: " + Constants.CMD_LIST_MESSAGES + " <room_id> [time_option]");
            System.out.print("> ");
            return;
        }

        String roomId = msgArgs[0].trim();
        // Default to "all" if time option is missing or empty
        String timeOption = (msgArgs.length > 1 && !msgArgs[1].trim().isEmpty()) ? msgArgs[1].trim() : Constants.TIME_OPTION_ALL;

        if (clientState.getSessionKey() == null) {
            System.out.println("You must be logged in to list messages. Use /login <id> <pw>");
            System.out.print("> ");
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty(Constants.KEY_CHAT_ID, clientState.getCurrentChatId());
        data.addProperty(Constants.KEY_ROOM_ID, roomId);

        // Only add from_time if a specific time option (not "all") is provided and valid
        if (!timeOption.equalsIgnoreCase(Constants.TIME_OPTION_ALL)) {
            String fromTimeIso = parseTimeOption(timeOption);
            if (fromTimeIso != null) {
                data.addProperty(Constants.KEY_FROM_TIME, fromTimeIso);
            } else {
                // parseTimeOption prints error, just return
                System.out.print("> ");
                return;
            }
        }
        // If timeOption is "all", don't add from_time

        JsonObject request = JsonHelper.createRequest(Constants.ACTION_GET_MESSAGES, data);
        handshakeManager.sendClientRequestWithAck(request, Constants.ACTION_GET_MESSAGES, clientState.getSessionKey());
        // No need to print "> " here
    }

    /**
     * Parses the time option string into an ISO 8601 formatted string.
     * Handles formats like "12hours", "7days", "3weeks", "all", ISO 8601, or "yyyy-MM-dd HH:mm:ss".
     * Returns null if the format is invalid or if the option is "all".
     * Prints error messages for invalid formats.
     *
     * @param timeOption The time option string provided by the user.
     * @return ISO 8601 string representing the calculated time, or null.
     */
    private String parseTimeOption(String timeOption) {
        if (timeOption == null) return null;
        timeOption = timeOption.trim().toLowerCase();
        // Use constant for comparison
        if (timeOption.equals(Constants.TIME_OPTION_ALL)) return null; // "all" means no time filter

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
                    System.out.println("Invalid time format. Use e.g., '12"+Constants.TIME_OPTION_HOURS+"', '7"+Constants.TIME_OPTION_DAYS+"', '3"+Constants.TIME_OPTION_WEEKS+"', '"+Constants.TIME_OPTION_ALL+"', ISO format, or 'yyyy-MM-dd HH:mm:ss'.");
                    return null;
                }
            }
        }

        return fromInstant != null ? fromInstant.toString() : null;
    }


    @Override
    public String getDescription() {
        return Constants.CMD_LIST_MESSAGES_DESC;
    }
}
