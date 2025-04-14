import constants
import os # Make sure os is imported

# --- Server Configuration ---
SERVER_ADDRESS = "127.0.0.1" # Replace with actual server IP if needed
SERVER_PORT = constants.DEFAULT_SERVER_PORT

# --- AI Client Credentials ---
AI_CHAT_ID = "gemini_bot" # Choose a unique chat ID for the AI
AI_PASSWORD = "ai_password" # Choose a secure password

# --- Gemini API Configuration ---
# IMPORTANT: Keep your API key secure. Consider environment variables or a secrets manager.
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY") # Load key from environment
# Define only the base URL here
GEMINI_API_URL_BASE = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
GEMINI_SAFETY_SETTINGS = [
    {
        "category": "HARM_CATEGORY_HARASSMENT",
        "threshold": "BLOCK_MEDIUM_AND_ABOVE"
    },
    {
        "category": "HARM_CATEGORY_HATE_SPEECH",
        "threshold": "BLOCK_MEDIUM_AND_ABOVE"
    },
    {
        "category": "HARM_CATEGORY_SEXUALLY_EXPLICIT",
        "threshold": "BLOCK_MEDIUM_AND_ABOVE"
    },
    {
        "category": "HARM_CATEGORY_DANGEROUS_CONTENT",
        "threshold": "BLOCK_MEDIUM_AND_ABOVE"
    },
]
GEMINI_GENERATION_CONFIG = {
    "temperature": 0.9,
    "topK": 1,
    "topP": 1,
    "maxOutputTokens": 2048,
    "stopSequences": []
}

# --- Network ---
RECEIVE_BUFFER_SIZE = constants.MAX_UDP_PACKET_SIZE
SOCKET_TIMEOUT = 1.0 # Seconds for socket receive timeout

# --- Handshake ---
PENDING_TIMEOUT_CHECK_INTERVAL = 5 # Seconds
