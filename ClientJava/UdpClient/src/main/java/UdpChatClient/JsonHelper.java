package UdpChatClient;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets; // Use SLF4J Logger

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Utility class for handling JSON parsing, creation, encryption/decryption, and UDP packet sending.
 */
public final class JsonHelper {

    private static final Gson gson = new Gson(); // Reusable Gson instance

    // Private constructor to prevent instantiation
    private JsonHelper() {}

    /**
     * Creates a standard JSON request object.
     * Similar to createReply but without status.
     */
    public static JsonObject createRequest(String action, JsonObject data) {
        JsonObject request = new JsonObject();
        request.addProperty(Constants.KEY_ACTION, action);
        if (data != null) {
            request.add(Constants.KEY_DATA, data);
        }
        // Requests typically don't include status or message, but could if needed
        return request;
    }

    /**
     * Wrapper class to hold the result of decryption and parsing.
     */
    public static class DecryptedResult {
        public final JsonObject jsonObject;
        public final String decryptedJsonString;

        public DecryptedResult(JsonObject jsonObject, String decryptedJsonString) {
            this.jsonObject = jsonObject;
            this.decryptedJsonString = decryptedJsonString;
        }
    }

    /**
     * Decrypts the data from a DatagramPacket using the provided key string,
     * then parses it into a JsonObject.
     *
     * @param packet    The received DatagramPacket.
     * @param keyString The key string for Caesar decryption.
     * @param log       The logger instance from the calling class.
     * @return DecryptedResult containing the JsonObject and the decrypted JSON string,
     *         or null if decryption or parsing fails.
     */
    public static DecryptedResult decryptAndParse(DatagramPacket packet, String keyString, Logger log) {
        if (packet == null || packet.getData() == null || packet.getLength() == 0) {
            log.warn("Received empty or null packet for decryption.");
            return null;
        }
        if (keyString == null || keyString.isEmpty()) {
            log.error("Attempted decryption with null or empty key from {}:{}", packet.getAddress().getHostAddress(), packet.getPort());
            return null;
        }

        try {
            // Assume the entire data payload is the encrypted string
            String encryptedString = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
            // log.trace("Received raw encrypted string: {}", encryptedString); // Can be noisy

            // Decrypt using Caesar cipher
            String decryptedJsonString = CaesarCipher.decrypt(encryptedString, keyString);
            if (decryptedJsonString == null) {
                 log.error("Decryption returned null for packet from {}:{} with key length {}", packet.getAddress().getHostAddress(), packet.getPort(), keyString.length());
                 return null; // Decryption failed critically
            }
            // log.trace("Decrypted JSON string: {}", decryptedJsonString); // Can be noisy

            // Parse the decrypted string
            JsonObject jsonObject = JsonParser.parseString(decryptedJsonString).getAsJsonObject();
            return new DecryptedResult(jsonObject, decryptedJsonString);

        } catch (JsonSyntaxException e) {
            // Log the decrypted string *only* if logging level allows, as it might contain sensitive info if decryption failed partially
            log.error("Invalid JSON syntax after decryption with key length {} from {}:{}. Decrypted content (potential garbage): '{}'. Error: {}",
                      keyString.length(), packet.getAddress().getHostAddress(), packet.getPort(),
                      log.isTraceEnabled() ? CaesarCipher.decrypt(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8), keyString) : "[hidden]",
                      e.getMessage());
            return null;
        } catch (IllegalStateException e) {
             log.error("Parsed JSON after decryption with key length {} is not an object from {}:{}: {}", keyString.length(), packet.getAddress().getHostAddress(), packet.getPort(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Error decrypting/parsing JSON with key length {} from {}:{}: {}", keyString.length(), packet.getAddress().getHostAddress(), packet.getPort(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Creates a standard JSON reply object.
     * (No changes needed here)
     */
    public static JsonObject createReply(String action, String status, String message, JsonObject data) {
        JsonObject reply = new JsonObject();
        reply.addProperty(Constants.KEY_ACTION, action);
        reply.addProperty(Constants.KEY_STATUS, status);
        if (message != null) {
            reply.addProperty(Constants.KEY_MESSAGE, message);
        }
        if (data != null) {
            reply.add(Constants.KEY_DATA, data);
        }
        return reply;
    }

    /**
     * Creates a standard JSON error reply object.
     * (No changes needed here)
     */
    public static JsonObject createErrorReply(String originalAction, String errorMessage) {
        JsonObject reply = new JsonObject();
        reply.addProperty(Constants.KEY_ACTION, Constants.ACTION_ERROR); // General error action
        reply.addProperty("original_action", originalAction); // Include the action that failed
        reply.addProperty(Constants.KEY_STATUS, Constants.STATUS_ERROR);
        reply.addProperty(Constants.KEY_MESSAGE, errorMessage);
        return reply;
    }

    /**
     * Encrypts a JsonObject using the provided key and sends it as a UDP DatagramPacket.
     *
     * @param socket    The DatagramSocket to send from.
     * @param address   The destination IP address.
     * @param port      The destination port.
     * @param json      The JsonObject to send.
     * @param keyString The key string for Caesar encryption.
     * @param log       The logger instance from the calling class.
     * @return true if sending was attempted, false if an error occurred before sending.
     */
    public static boolean sendPacket(DatagramSocket socket, InetAddress address, int port, JsonObject json, String keyString, Logger log) {
        if (socket == null || address == null || json == null) {
            log.error("Attempted to send packet with null socket, address, or JSON data.");
            return false;
        }
         if (keyString == null || keyString.isEmpty()) {
            log.error("Attempted encryption with null or empty key for packet to {}:{}", address.getHostAddress(), port);
            return false;
        }

        try {
            String jsonString = gson.toJson(json);
            // log.trace("Plain JSON string to send: {}", jsonString); // Can be noisy

            // Encrypt the JSON string
            String encryptedString = CaesarCipher.encrypt(jsonString, keyString);
            // log.trace("Encrypted JSON string to send: {}", encryptedString); // Can be noisy
            System.out.print("-------------------Encrypted JSON string to send: " + encryptedString); // For debugging
            byte[] sendData = encryptedString.getBytes(StandardCharsets.UTF_8);

            if (sendData.length > Constants.MAX_UDP_PACKET_SIZE) {
                 log.error("Attempted to send UDP packet larger than max size ({} bytes) after encryption to {}:{}", sendData.length, address.getHostAddress(), port);
                 return false;
            }

            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);
            socket.send(sendPacket);
            // log.trace("Sent encrypted UDP packet (key length {}) to {}:{}: {}", keyString.length(), address.getHostAddress(), port, encryptedString); // Can be noisy
            log.debug("Sent encrypted packet (action: {}) with key length {} to {}:{}",
                      json.has(Constants.KEY_ACTION) ? json.get(Constants.KEY_ACTION).getAsString() : "unknown",
                      keyString.length(), address.getHostAddress(), port);
            return true;
        } catch (IOException e) {
            log.error("IOException sending encrypted UDP packet to {}:{}: {}", address.getHostAddress(), port, e.getMessage(), e);
            return false;
        } catch (Exception e) {
             log.error("Unexpected error sending encrypted UDP packet to {}:{}: {}", address.getHostAddress(), port, e.getMessage(), e);
             return false;
        }
    }
}
