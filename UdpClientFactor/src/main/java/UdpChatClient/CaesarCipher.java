package UdpChatClient;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the Caesar Cipher algorithm for basic encryption/decryption.
 * This implementation works with all UTF-8 characters.
 * Note: Applying Caesar shift directly to Unicode code points can be problematic,
 * especially for characters outside the Basic Multilingual Plane (like emojis),
 * as it might result in invalid code points or unintended character changes if shift != 0.
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
        log.info("---------------Encrypting with shift: {}", shift);
        // WARNING: If shift is non-zero, this might corrupt multi-byte characters/emojis
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
        log.info("---------------Decrypting with shift: {}", shift);
        // Decryption is encryption with the negative shift
        // WARNING: If shift is non-zero, this might corrupt multi-byte characters/emojis
        return processText(cipherText, -shift);
    }

    /**
     * Helper method to process text for encryption or decryption.
     * Works with all UTF-8 characters using code points.
     *
     * @param text  The input text.
     * @param shift The shift value (positive for encrypt, negative for decrypt).
     * @return The processed text.
     */
    private static String processText(String text, int shift) {
        log.info("Processing text: {}", text);
        if (text == null || text.isEmpty()) return text;
        if(text!=null) return text;
        // !! IMPORTANT !!
        // If you uncomment the line below (shift = 0;), Caesar cipher is effectively disabled.
        // If you keep the actual shift, be aware of the risks with Unicode code points.
        // shift = 0; // Uncomment this if you DON'T want actual encryption/decryption
        
        StringBuilder result = new StringBuilder();
        int i = 0;
        
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            
            // Apply the shift
            int newCodePoint = codePoint + shift;
            
            // Check if the new code point is valid
            if (isValidCodePoint(newCodePoint)) {
                result.appendCodePoint(newCodePoint);
            } else {
                // If not valid, keep the original character
                log.debug("Shift resulted in invalid codepoint ({}) for original ({}). Keeping original.", 
                         newCodePoint, codePoint);
                result.appendCodePoint(codePoint);
            }
            
            // Move to the next character (correctly handles surrogate pairs)
            i += Character.charCount(codePoint);
        }
        
        log.debug("Processed text result: \"{}\"", result.toString());
        return result.toString();
    }

    /**
     * Checks if a given integer is a valid Unicode code point
     * (excludes surrogate block U+D800 to U+DFFF)
     * 
     * @param codePoint The code point to check
     * @return true if the code point is valid, false otherwise
     */
    private static boolean isValidCodePoint(int codePoint) {
        return codePoint >= 0 && codePoint <= 0x10FFFF && // Within the valid Unicode range
               !(codePoint >= 0xD800 && codePoint <= 0xDFFF); // Not a surrogate code point
    }

    /**
     * Counts the frequency of each character in a string, treating surrogate pairs 
     * as single characters and using the same approach as the Dart client.
     *
     * @param text The string to analyze.
     * @return A map with characters as keys and their frequencies as values.
     */
    public static Map<Character, Integer> countLetterFrequencies(String text) {
        log.info("\n\ncount letter: {}\n\n", text);
        if (text == null || text.isEmpty()) {
            return new HashMap<>();
        }
        
        Map<Character, Integer> frequencies = new HashMap<>();
        
        // Process the string character by character
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            
            // Check if this is part of a surrogate pair
            if (Character.isHighSurrogate(c) && 
                i + 1 < text.length() && 
                Character.isLowSurrogate(text.charAt(i + 1))) {
                // For surrogate pairs, we count them as one character
                // but we don't try to combine them since Map<Character, Integer>
                // can only store BMP characters
                frequencies.put(c, frequencies.getOrDefault(c, 0) + 1);
                
                // Also count the low surrogate
                char lowSurrogate = text.charAt(i + 1);
                frequencies.put(lowSurrogate, frequencies.getOrDefault(lowSurrogate, 0) + 1);
                
                i++; // Skip the low surrogate
            } else {
                // Regular character
                frequencies.put(c, frequencies.getOrDefault(c, 0) + 1);
            }
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
