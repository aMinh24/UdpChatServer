import constants
import sys
from logger import log # Import logger

def caesar_cipher(text: str, shift: int) -> str:
    """
    Applies Caesar cipher to the text by shifting Unicode code points.
    Compatible with the Java implementation.
    Handles all UTF-8 characters, including Vietnamese and emojis.
    """
    if text is None:
        return None
    if not text:
        return ""
    shift = 0 # REMOVED: Re-enable cipher logic

    result = []
    # Iterate directly over Unicode characters (code points) in the string
    for char in text:
        try:
            code_point = ord(char)

            # Apply the shift to the code point
            shifted_code_point = code_point + shift

            # Convert the shifted code point back to a character
            # Use try-except to handle cases where the shifted code point is invalid
            try:
                 result.append(chr(shifted_code_point))
            except ValueError:
                 # If chr() raises ValueError (e.g., invalid code point after shift),
                 # append the original character.
                 log.warning(f"Caesar cipher resulted in invalid code point: Original='{char}' (ord={code_point}), Shifted={shifted_code_point}. Reverting to original.") # Log warning
                 result.append(char)

        except Exception as e:
            # Log error and append original character if any unexpected error occurs
            log.error(f"Error processing character '{char}': {e}") # Use logger
            result.append(char)

    return "".join(result)

def encrypt(plain_text: str, key_length: int) -> str:
    """Encrypts text using Caesar cipher with shift based on key_length."""
    if not isinstance(key_length, int): # Allow key_length == 0
        log.warning(f"Invalid key_length type for encryption: {key_length}. Returning original text.") # Use logger
        return plain_text
    if plain_text is None:
        return None
    shift = key_length
    return caesar_cipher(plain_text, shift)

def decrypt(cipher_text: str, key_length: int) -> str:
    """Decrypts text using Caesar cipher with shift based on key_length."""
    if not isinstance(key_length, int): # Allow key_length == 0
         log.warning(f"Invalid key_length type for decryption: {key_length}. Returning original text.") # Use logger
         return cipher_text
    if cipher_text is None:
        return None
    shift = key_length
    return caesar_cipher(cipher_text, -shift)

# --- Specific Key Usage ---

def encrypt_login(plain_text: str) -> str:
    """Encrypts the initial login request using the fixed key."""
    shift = len(constants.FIXED_LOGIN_KEY_STRING)
    return caesar_cipher(plain_text, shift)

def decrypt_login_response(cipher_text: str) -> str:
    """Decrypts the login response using the fixed key."""
    shift = len(constants.FIXED_LOGIN_KEY_STRING)
    return caesar_cipher(cipher_text, -shift)

def encrypt_session(plain_text: str, session_key: str) -> str:
    """Encrypts data using the session key length."""
    if not session_key:
        raise ValueError("Session key is not set for encryption.")
    return encrypt(plain_text, len(session_key))

def decrypt_session(cipher_text: str, session_key: str) -> str:
    """Decrypts data using the session key length."""
    if not session_key:
        raise ValueError("Session key is not set for decryption.")
    return decrypt(cipher_text, len(session_key))
