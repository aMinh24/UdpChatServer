package UdpChatServer.crypto;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the Caesar Cipher algorithm for basic encryption/decryption.
 * This implementation works with all UTF-8 characters.
 */
public class CaesarCipher {

    private static final Logger log = LoggerFactory.getLogger(CaesarCipher.class);

    /**
     * Encrypts plain text using the Caesar cipher with a given key (shift value).
     * Works with all UTF-8 characters.
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
     * Works with all UTF-8 characters.
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
     * Works with all UTF-8 characters by using Unicode code points directly.
     *
     * @param text  The input text.
     * @param shift The shift value (positive for encrypt, negative for decrypt).
     * @return The processed text.
     */
    private static String processText(String text, int shift) {
        log.info("Processing text: {}", text);
        if(text == null || text.isEmpty()) return text;
        shift = 0;
        StringBuilder result = new StringBuilder();
        int i = 0;
        
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            
            // Áp dụng phép dịch
            int newCodePoint = codePoint + shift;
            
            // Kiểm tra tính hợp lệ
            if (Character.isValidCodePoint(newCodePoint)) {
                result.appendCodePoint(newCodePoint);
            } else {
                // Nếu không hợp lệ, giữ nguyên ký tự
                result.appendCodePoint(codePoint);
            }
            
            // Tiến tới ký tự tiếp theo (xử lý đúng cho surrogate pairs)
            i += Character.charCount(codePoint);
        }
        
        return result.toString();
    }
    /**
     * Counts the frequency of each character in a string.
     * Used for the confirmation step after decryption.
     *
     * @param text The string to analyze.
     * @return A map with characters as keys and their frequencies as values.
     */
    public static Map<Character, Integer> countLetterFrequencies(String text) {
        log.info("Counting letter frequencies in text: {}", text);
        if (text == null || text.isEmpty()) {
            return new HashMap<>();
        }
        
        Map<Character, Integer> frequencies = new HashMap<>();
        
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            frequencies.put(character, frequencies.getOrDefault(character, 0) + 1);
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
        if (text == null || text.isEmpty()) {
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
