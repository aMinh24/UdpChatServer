import logging
import sys
from logging.handlers import RotatingFileHandler

LOG_FILENAME = 'client.log'
LOG_LEVEL = logging.DEBUG # Set desired logging level (DEBUG, INFO, WARNING, ERROR, CRITICAL)

# Create a logger instance (acts as Singleton due to module-level scope)
log = logging.getLogger('AIClientLogger')
log.setLevel(LOG_LEVEL)
log.propagate = False # Prevent root logger from handling messages again

# --- Configuration Function ---
_is_configured = False

def setup_logger():
    """Configures the logger. Call this once at the start of the application."""
    global _is_configured
    if _is_configured:
        return

    # Create formatter
    formatter = logging.Formatter(
        '%(asctime)s - %(name)s - %(levelname)s - %(threadName)s - %(message)s'
    )

    # Create file handler (rotate logs)
    try:
        # Rotate log file when it reaches 5MB, keep 3 backup logs
        file_handler = RotatingFileHandler(
            LOG_FILENAME, maxBytes=5*1024*1024, backupCount=3, encoding='utf-8'
        )
        file_handler.setLevel(LOG_LEVEL)
        file_handler.setFormatter(formatter)
        log.addHandler(file_handler)
    except Exception as e:
        # Fallback to console if file logging fails
        print(f"Error setting up file logger: {e}. Logging to console.", file=sys.stderr)
        console_handler = logging.StreamHandler(sys.stderr)
        console_handler.setLevel(LOG_LEVEL)
        console_handler.setFormatter(formatter)
        log.addHandler(console_handler)

    # Optional: Add a console handler as well for immediate feedback during development
    # console_handler = logging.StreamHandler(sys.stdout)
    # console_handler.setLevel(logging.INFO) # Log INFO and above to console
    # console_handler.setFormatter(formatter)
    # log.addHandler(console_handler)

    _is_configured = True
    log.info("Logger setup complete.")

# Example usage (within other modules):
# from logger import log
# log.debug("This is a debug message")
# log.info("This is an info message")
# log.warning("This is a warning")
# log.error("This is an error")
# try:
#     1 / 0
# except ZeroDivisionError:
#     log.exception("Caught an exception")
