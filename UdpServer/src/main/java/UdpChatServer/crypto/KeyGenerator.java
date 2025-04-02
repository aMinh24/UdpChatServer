package UdpChatServer.crypto;

import java.security.SecureRandom;

/**
 * Generates random keys suitable for use with the CaesarCipher.
 * The length of the key determines the shift value in CaesarCipher.
 */
public class KeyGenerator {

    // Characters allowed in the generated key
    private static final String ALLOWED_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom random = new SecureRandom();
    private static final int DEFAULT_KEY_LENGTH = 16; // Default length for generated keys

    /**
     * Generates a random key string of a specified length.
     *
     * @param length The desired length of the key. Must be positive.
     * @return A randomly generated key string.
     * @throws IllegalArgumentException if length is not positive.
     */
    public static String generateKey(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Key length must be positive.");
        }

        StringBuilder key = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int randomIndex = random.nextInt(ALLOWED_CHARACTERS.length());
            key.append(ALLOWED_CHARACTERS.charAt(randomIndex));
        }
        return key.toString();
    }

    /**
     * Generates a random key string using the default length.
     *
     * @return A randomly generated key string of default length.
     */
    public static String generateKey() {
        return generateKey(DEFAULT_KEY_LENGTH);
    }

    // Private constructor to prevent instantiation of this utility class
    private KeyGenerator() {}
}
