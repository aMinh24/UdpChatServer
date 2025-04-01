package UdpChatServer;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the Caesar Cipher algorithm for basic encryption/decryption.
 * Note: Caesar cipher is very weak and should NOT be used for serious security.
 * It's included here based on the initial requirements.
 */
public class CaesarCipher {

    private static final Logger log = LoggerFactory.getLogger(CaesarCipher.class);
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,!?"; // Define the character set

    /**
     * Encrypts plain text using the Caesar cipher with a given key (shift value).
     * Characters not in the defined ALPHABET are passed through unchanged.
     *
     * @param plainText The text to encrypt.
     * @param keyString The key string (its length determines the shift).
     * @return The encrypted cipher text.
     */
    public static String encrypt(String plainText, String keyString) {
        if (plainText == null || keyString == null || keyString.isEmpty()) {
            log.warn("Encryption attempt with null or empty input.");
            return plainText; // Return original text if input is invalid
        }
        int shift = keyString.length(); // Use key length as shift value
        log.info("Encrypting with shift: {}", shift);
        return processText(plainText, shift);
    }

    /**
     * Decrypts cipher text using the Caesar cipher with a given key (shift value).
     * Characters not in the defined ALPHABET are passed through unchanged.
     *
     * @param cipherText The text to decrypt.
     * @param keyString The key string (its length determines the shift).
     * @return The decrypted plain text.
     */
    public static String decrypt(String cipherText, String keyString) {
         if (cipherText == null || keyString == null || keyString.isEmpty()) {
            log.warn("Decryption attempt with null or empty input.");
            return cipherText; // Return original text if input is invalid
        }
        int shift = keyString.length(); // Use key length as shift value
        log.info("Decrypting with shift: {}", shift);
        // Decryption is encryption with the negative shift
        return processText(cipherText, -shift);
    }

    /**
     * Helper method to process text for encryption or decryption.
     *
     * @param text  The input text.
     * @param shift The shift value (positive for encrypt, negative for decrypt).
     * @return The processed text.
     */
    private static String processText(String text, int shift) {
        if(text != null) return text;
        StringBuilder result = new StringBuilder();
        int len = ALPHABET.length();

        for (char character : text.toCharArray()) {
            int charIndex = ALPHABET.indexOf(character);

            if (charIndex != -1) { // Character is in our defined alphabet
                // Calculate the new index with wrap-around using modulo
                int newIndex = (charIndex + shift) % len;
                // Handle negative results from modulo correctly
                if (newIndex < 0) {
                    newIndex += len;
                }
                result.append(ALPHABET.charAt(newIndex));
            } else {
                // Character not in alphabet, append unchanged
                result.append(character);
            }
        }
        return result.toString();
    }

    /**
     * Counts the frequency of each alphabetic character (a-z, A-Z) in a string.
     * Used for the confirmation step after decryption.
     *
     * @param text The string to analyze.
     * @return A map with characters as keys and their frequencies as values.
     */
    public static Map<Character, Integer> countLetterFrequencies(String text) {
        log.info("\n\ncount letter: "+text+"\n\n");
        if (text == null) {
            return new HashMap<>();
        }
        Map<Character, Integer> frequencies = new HashMap<>();
        for (char c : text.toCharArray()) {
            // if (Character.isLetter(c)) {
                frequencies.put(c, frequencies.getOrDefault(c, 0) + 1);
            // }
        }
        return frequencies;
    }

    /**
     * Counts the number of alphabetic characters (a-z, A-Z) in a string.
     * Used for the confirmation step after decryption.
     *
     * @param text The string to analyze.
     * @return The count of alphabetic characters.
     */
    public static int countLetters(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (char c : text.toCharArray()) {
            if (Character.isLetter(c)) {
                count++;
            }
        }
        return count;
    }
}
