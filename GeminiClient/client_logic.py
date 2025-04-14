import time
import threading
import constants
import config
import crypto
import utils
import json
from network import UDPSocketManager
from gemini import GeminiAPI
from logger import log

class AIClientState:
    """Holds the state of the AI client."""
    def __init__(self, chatid: str):
        self.chatid = chatid
        self.session_key = None
        self.logged_in = False
        self.server_address = (config.SERVER_ADDRESS, config.SERVER_PORT)
        # Store pending client-initiated actions requiring C2S handshake confirmation
        # {transaction_id: {"packet": original_packet_dict, "timestamp": time.time()}}
        self.pending_sends = {}
        # Store pending server-initiated actions requiring S2C handshake confirmation
        # {transaction_id: {"packet": original_received_packet_dict, "timestamp": time.time()}}
        self.pending_receives = {}
        self.lock = threading.Lock() # To protect access to shared state

    def set_session(self, session_key: str):
        with self.lock:
            self.session_key = session_key
            self.logged_in = True
            log.debug(f"State updated - logged_in set to True (Session Key Length: {len(session_key)})")
            log.info(f"Login successful. Session key received (length {len(session_key)}).")

    def clear_session(self):
        with self.lock:
            log.debug(f"Clearing session - logged_in set to False")
            self.session_key = None
            self.logged_in = False
            self.pending_sends.clear()
            self.pending_receives.clear()
            log.info("Session cleared (logged out or disconnected).")

    def add_pending_send(self, transaction_id: str, packet_dict: dict):
        with self.lock:
            self.pending_sends[transaction_id] = {
                "packet": packet_dict,
                "timestamp": time.time()
            }

    def get_pending_send(self, transaction_id: str) -> dict | None:
        with self.lock:
            return self.pending_sends.get(transaction_id)

    def remove_pending_send(self, transaction_id: str) -> bool:
        with self.lock:
            removed = self.pending_sends.pop(transaction_id, None)
            # Log if removal happened or not
            log.debug(f"Attempted to remove pending send {transaction_id}. Found: {removed is not None}")
            return removed is not None

    def get_latest_pending_send_by_action(self, action: str) -> tuple[str, dict] | tuple[None, None]:
        """Finds the most recent pending send matching the action."""
        with self.lock:
            matching_sends = [
                (tx_id, data) for tx_id, data in self.pending_sends.items()
                if data.get("packet", {}).get(constants.KEY_ACTION) == action
            ]
            if not matching_sends:
                return None, None
            # Sort by timestamp descending to get the latest
            matching_sends.sort(key=lambda item: item[1]['timestamp'], reverse=True)
            latest_tx_id, latest_data = matching_sends[0]
            return latest_tx_id, latest_data

    def add_pending_receive(self, transaction_id: str, packet_dict: dict):
        with self.lock:
            self.pending_receives[transaction_id] = {
                "packet": packet_dict,
                "timestamp": time.time()
            }

    def get_pending_receive(self, transaction_id: str) -> dict | None:
        with self.lock:
            pending_data = self.pending_receives.get(transaction_id)
            return pending_data["packet"] if pending_data else None

    def remove_pending_receive(self, transaction_id: str) -> bool:
        with self.lock:
            removed = self.pending_receives.pop(transaction_id, None)
            return removed is not None

    def cleanup_pending_states(self):
        """Removes timed-out pending sends and receives."""
        now = time.time()
        timeout = constants.PENDING_MESSAGE_TIMEOUT_MS / 1000.0
        with self.lock:
            expired_send_ids = [
                tx_id for tx_id, data in self.pending_sends.items()
                if now - data["timestamp"] > timeout
            ]
            for tx_id in expired_send_ids:
                log.warning(f"Timeout for pending send: {tx_id}. Removing.")
                del self.pending_sends[tx_id]

            expired_receive_ids = [
                tx_id for tx_id, data in self.pending_receives.items()
                if now - data["timestamp"] > timeout
            ]
            for tx_id in expired_receive_ids:
                log.warning(f"Timeout for pending receive: {tx_id}. Removing.")
                del self.pending_receives[tx_id]


class AIClient:
    def __init__(self):
        self.client_state = AIClientState(config.AI_CHAT_ID)
        self.udp_manager = UDPSocketManager(self.handle_raw_packet)
        # Pass the base URL from config
        self.gemini_api = GeminiAPI(config.GEMINI_API_URL_BASE)
        self.packet_handler = PacketHandler(self.client_state, self.udp_manager, self.gemini_api, self)
        self.running = False
        self.pending_check_thread = None

    def start(self):
        """Starts the UDP manager and login process."""
        if self.running:
            return
        self.running = True
        self.udp_manager.start()
        self._start_pending_check()
        self.login()

    def stop(self):
        """Stops the client."""
        log.info("AI Client stopping...")
        self.running = False
        if self.pending_check_thread and self.pending_check_thread.is_alive():
            self.pending_check_thread.join(timeout=1.0)
        self.udp_manager.stop()
        self.client_state.clear_session()
        log.info("AI Client stopped.")

    def _start_pending_check(self):
        """Starts the thread to periodically clean up timed-out pending states."""
        self.pending_check_thread = threading.Thread(target=self._pending_check_loop, daemon=True)
        self.pending_check_thread.start()

    def _pending_check_loop(self):
        """Periodically checks for and removes timed-out pending messages."""
        while self.running:
            try:
                self.client_state.cleanup_pending_states()
            except Exception as e:
                log.exception(f"Error during pending state cleanup: {e}")
            time.sleep(config.PENDING_TIMEOUT_CHECK_INTERVAL)

    def handle_raw_packet(self, data: bytes, address: tuple):
        """Callback for UDPSocketManager, passes data to PacketHandler."""
        log.debug(f"handle_raw_packet called with {len(data)} bytes from {address}")
        self.packet_handler.handle_packet(data, address)

    def login(self):
        """Initiates the login process."""
        with self.client_state.lock:
            is_logged_in = self.client_state.logged_in

        if is_logged_in:
            log.info("Already logged in.")
            return

        log.info(f"Attempting login as {config.AI_CHAT_ID}...")
        login_data = {
            constants.KEY_CHAT_ID: config.AI_CHAT_ID,
            constants.KEY_PASSWORD: config.AI_PASSWORD
        }
        packet_str = utils.create_packet(constants.ACTION_LOGIN, data=login_data)

        try:
            encrypted_packet = crypto.encrypt_login(packet_str).encode('utf-8')
            self.udp_manager.send(encrypted_packet)
            log.info("Login request sent.")
        except Exception as e:
            log.exception(f"Error sending login request: {e}")

    def send_message(self, room_id: str, content: str):
        """Initiates sending a message with the C2S 3-way handshake."""
        with self.client_state.lock:
            is_logged_in = self.client_state.logged_in
            current_session_key = self.client_state.session_key

        if not is_logged_in or not current_session_key:
            log.error("Cannot send message, not logged in.")
            return

        log.info(f"Sending message to room {room_id}: {content[:30]}...")

        transaction_id = utils.generate_transaction_id()
        message_data = {
            constants.KEY_CHAT_ID: self.client_state.chatid,
            constants.KEY_ROOM_ID: room_id,
            constants.KEY_CONTENT: content,
            constants.KEY_TIMESTAMP: utils.get_timestamp(),
            constants.KEY_TRANSACTION_ID: transaction_id
        }
        packet_dict = {
            constants.KEY_ACTION: constants.ACTION_SEND_MESSAGE,
            constants.KEY_DATA: message_data
        }
        packet_str = json.dumps(packet_dict)

        try:
            encrypted_packet = crypto.encrypt_session(packet_str, current_session_key).encode('utf-8')
            # Add to pending sends *before* sending
            self.client_state.add_pending_send(transaction_id, packet_dict)
            self.udp_manager.send(encrypted_packet)
            log.info(f"Sent SEND_MESSAGE (Client TX: {transaction_id}). Waiting for CHARACTER_COUNT...") # Log client TX ID
        except ValueError as e:
            log.error(f"Encryption error: {e}")
            # Ensure removal if adding succeeded but sending failed later (though unlikely here)
            self.client_state.remove_pending_send(transaction_id)
        except Exception as e:
            log.exception(f"Error sending message packet: {e}")
            # Ensure removal if adding succeeded but sending failed later
            self.client_state.remove_pending_send(transaction_id)


from packet_handler import PacketHandler
