import json
import uuid
import time
import constants
from collections import Counter
from logger import log # Import logger

def safe_json_loads(text: str):
    """Safely parses JSON string, returning None on failure."""
    if not text:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError as e:
        log.error(f"Failed to decode JSON: {e} - Data: {text[:100]}...") # Use logger
        return None

def create_packet(action: str, data: dict = None, status: str = None, message: str = None, transaction_id: str = None, original_action: str = None, **kwargs) -> str:
    """Creates a JSON packet string."""
    packet = {constants.KEY_ACTION: action}
    if status:
        packet[constants.KEY_STATUS] = status
    if message:
        packet[constants.KEY_MESSAGE] = message

    packet_data = data if data is not None else {}
    packet_data.update(kwargs) # Add any extra kwargs to the data field

    # Add transaction_id and original_action to the data field if they exist
    if transaction_id:
        packet_data[constants.KEY_TRANSACTION_ID] = transaction_id
    if original_action:
        packet_data[constants.KEY_ORIGINAL_ACTION] = original_action

    if packet_data: # Only add data field if it's not empty
        packet[constants.KEY_DATA] = packet_data

    return json.dumps(packet)

def generate_transaction_id() -> str:
    """Generates a unique transaction ID."""
    return str(uuid.uuid4())

def get_timestamp() -> str:
    """Returns the current time in ISO 8601 format (UTC)."""
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

def calculate_frequencies(text: str) -> dict:
    """
    Calculates the frequency of each character in the text.
    Counts all characters, including spaces, punctuation, etc.
    """
    if not text:
        return {}
    # Count every character in the string
    return dict(Counter(char for char in text))

