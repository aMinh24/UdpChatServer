import time
import signal
import sys
from client_logic import AIClient
import config # Ensure config is loaded
from logger import log, setup_logger # Import logger

# Global variable to hold the client instance for signal handling
ai_client_instance = None

def signal_handler(sig, frame):
    """Handles termination signals (like Ctrl+C)."""
    log.info("\nSignal received, shutting down AI client...") # Use logger
    if ai_client_instance:
        ai_client_instance.stop()
    sys.exit(0)

def main():
    global ai_client_instance

    # --- Setup Logger FIRST ---
    setup_logger()

    log.info("--- Gemini AI Chat Client ---") # Use logger

    # Validate essential config
    if not config.AI_CHAT_ID or not config.AI_PASSWORD:
        log.error("AI_CHAT_ID and AI_PASSWORD must be set in config.py") # Use logger
        sys.exit(1)
    if not config.GEMINI_API_KEY or config.GEMINI_API_KEY == "YOUR_GEMINI_API_KEY":
         log.warning("GEMINI_API_KEY is not set or is the placeholder in config.py.") # Use logger
         log.warning("AI responses will likely fail.") # Use logger
         # Decide if you want to exit or continue without AI functionality
         # sys.exit(1)


    # Set up signal handling for graceful shutdown
    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    # Create and start the client
    ai_client_instance = AIClient()
    try:
        ai_client_instance.start()

        # Keep the main thread alive while the client runs in background threads
        while ai_client_instance.running:
            time.sleep(1) # Keep main thread alive, client logic runs in threads

    except KeyboardInterrupt:
        log.info("\nKeyboardInterrupt received in main loop.") # Use logger
        # Signal handler should have been called, but call stop just in case
        if ai_client_instance:
            ai_client_instance.stop()
    except Exception as e:
        log.exception(f"An unexpected error occurred in the main loop: {e}") # Use logger with exception info
        if ai_client_instance:
            ai_client_instance.stop()
    finally:
        log.info("Exiting main application.") # Use logger


if __name__ == "__main__":
    main()
