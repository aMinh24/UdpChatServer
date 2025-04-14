import json
import constants
import crypto
import utils
import config # Import config to access credentials for login frequency check
from logger import log # Import logger
# Type hinting imports (avoid runtime circular dependency)
from typing import TYPE_CHECKING
if TYPE_CHECKING:
    from client_logic import AIClientState, AIClient
    from network import UDPSocketManager
    from gemini import GeminiAPI


class PacketHandler:
    def __init__(self, client_state: 'AIClientState', udp_manager: 'UDPSocketManager', gemini_api: 'GeminiAPI', ai_client: 'AIClient'):
        self.client_state = client_state
        self.udp_manager = udp_manager
        self.gemini_api = gemini_api
        self.ai_client = ai_client # Reference to the main client instance
        # Store the raw decrypted string for frequency calculation in S2C handshake
        self.last_decrypted_packet_str = None

    def handle_packet(self, raw_data: bytes, address: tuple):
        """Processes a received UDP packet."""
        log.debug(f"handle_packet received raw data: {raw_data[:150]}...") # Use logger
        decrypted_text = None
        packet_dict = None
        decryption_method = "None" # Track how packet was obtained
        action = None # Initialize action
        self.last_decrypted_packet_str = None # Reset last decrypted string

        # --- 1. Try UTF-8 Decode first (for potentially unencrypted packets like handshake steps) ---
        try:
            potential_json = raw_data.decode('utf-8')
            packet_dict = utils.safe_json_loads(potential_json) # safe_json_loads uses logger
            if packet_dict:
                action = packet_dict.get(constants.KEY_ACTION)
                # Assume if it decodes as JSON via UTF-8, it's meant to be unencrypted
                # (e.g., CHARACTER_COUNT or CONFIRM_COUNT from server)
                decryption_method = "UTF-8 Decode"
                self.last_decrypted_packet_str = potential_json # Store for potential S2C frequency calc
                log.debug(f"Successfully decoded unencrypted packet: {packet_dict}") # Use logger
            # else: safe_json_loads logs error

        except UnicodeDecodeError:
            # Not valid UTF-8, must be encrypted
            log.debug("Data is not valid UTF-8, requires decryption.") # Use logger
            packet_dict = None # Ensure packet_dict is None

        # --- 2. Attempt Decryption if needed ---
        if packet_dict is None:
            # Check login status *once* before trying keys
            with self.client_state.lock:
                is_logged_in = self.client_state.logged_in
                current_session_key = self.client_state.session_key
            log.debug(f"Entering decryption block (logged_in={is_logged_in})") # Use logger

            # Try session key ONLY if logged in
            if is_logged_in and current_session_key:
                log.debug("Attempting session key decryption...") # Use logger
                try:
                    # Log raw bytes before decoding
                    log.debug(f"Raw data before session decode: {raw_data[:100]}...")
                    raw_text_for_decrypt = raw_data.decode('utf-8')
                    # Log decoded text before decryption
                    log.debug(f"Data after session decode (before decrypt): {raw_text_for_decrypt[:100]}...")
                    decrypted_text = crypto.decrypt_session(raw_text_for_decrypt, current_session_key)
                    packet_dict = utils.safe_json_loads(decrypted_text) # safe_json_loads uses logger
                    if packet_dict:
                        action = packet_dict.get(constants.KEY_ACTION) # Update action
                        decryption_method = "Session Key"
                        self.last_decrypted_packet_str = decrypted_text # Store decrypted string
                        log.debug(f"Session key decryption successful: {packet_dict}") # Use logger
                    else:
                        log.warning(f"Session key decryption resulted in invalid JSON: {decrypted_text[:100]}...") # Use logger
                        packet_dict = None # Ensure reset
                except UnicodeDecodeError as ude:
                     log.error(f"UnicodeDecodeError during session key stage: {ude}. Raw data: {raw_data[:100]}...") # Log error and raw data
                     packet_dict = None
                except Exception as e:
                    log.exception(f"Session key decryption failed: {e}") # Use logger
                    packet_dict = None # Reset if decryption failed

            # If session decryption failed OR client is not logged in, try login key
            if packet_dict is None:
                log.debug(f"Attempting login key decryption (Previous attempt failed or not logged in)...") # Use logger
                try:
                    # Log raw bytes before decoding
                    log.debug(f"Raw data before login decode: {raw_data[:100]}...")
                    raw_text_for_decrypt = raw_data.decode('utf-8')
                    # Log decoded text before decryption
                    log.debug(f"Data after login decode (before decrypt): {raw_text_for_decrypt[:100]}...")
                    decrypted_text = crypto.decrypt_login_response(raw_text_for_decrypt)
                    packet_dict = utils.safe_json_loads(decrypted_text) # safe_json_loads uses logger
                    if packet_dict:
                        action = packet_dict.get(constants.KEY_ACTION) # Update action
                        decryption_method = "Login Key"
                        self.last_decrypted_packet_str = decrypted_text # Store decrypted string
                        log.debug(f"Login key decryption successful: {packet_dict}") # Use logger
                    else:
                        log.warning(f"Login key decryption resulted in invalid JSON: {decrypted_text[:100]}...") # Use logger
                        packet_dict = None # Ensure reset
                except UnicodeDecodeError as ude:
                     log.error(f"UnicodeDecodeError during login key stage: {ude}. Raw data: {raw_data[:100]}...") # Log error and raw data
                     packet_dict = None
                except Exception as e:
                     log.exception(f"Login key decryption failed: {e}") # Use logger
                     packet_dict = None

        # --- 3. Validate Final Packet ---
        if packet_dict is None:
            log.error(f"Could not parse packet after all decryption attempts. Original data: {raw_data[:100]}...") # Use logger
            return

        # Re-fetch action if it was determined during decryption
        action = packet_dict.get(constants.KEY_ACTION)
        data = packet_dict.get(constants.KEY_DATA, {})
        status = packet_dict.get(constants.KEY_STATUS)
        message = packet_dict.get(constants.KEY_MESSAGE)
        transaction_id = data.get(constants.KEY_TRANSACTION_ID)

        log.debug(f"Final Packet Dict: {packet_dict}") # Use logger
        log.info(f"Routing Action: {action}, Status: {status}, TX_ID: {transaction_id}, Decryption: {decryption_method}") # Use logger

        if not action:
            log.error(f"Received packet with missing action from {address}: {packet_dict}") # Use logger
            return

        # --- 4. Route to Specific Handler ---
        handler_method_name = f"handle_{action}"
        handler_method = getattr(self, handler_method_name, self.handle_unknown)
        log.debug(f"Calling handler: {handler_method_name}") # Use logger
        # Pass the original decrypted string if available for S2C frequency calculation
        handler_method(packet_dict, data, status, message, transaction_id, address, self.last_decrypted_packet_str)

    # --- Specific Action Handlers ---
    # Note: Added 'original_packet_str' argument to handlers
    def handle_receive_message(self, packet: dict, data: dict, status: str, message: str, tx_id: str, address: tuple, original_packet_str: str | None):
        """Handles the initial RECEIVE_MESSAGE from server (Start S2C Handshake)."""
        log.debug(f"Entered handle_receive_message (S2C Start - TX: {tx_id})")

        if not tx_id:
            log.error("Received RECEIVE_MESSAGE without transaction_id in data.")
            return

        # Acquire lock before checking login status
        with self.client_state.lock:
            is_logged_in = self.client_state.logged_in
            current_session_key = self.client_state.session_key

        if not is_logged_in or not current_session_key:
            log.warning("Ignoring receive_message, not logged in or no session key.")
            return

        if not original_packet_str:
             log.error(f"Cannot calculate frequency for S2C RECEIVE_MESSAGE (TX: {tx_id}), original decrypted string not available.")
             return

        # Store the original packet for processing later after confirmation
        self.client_state.add_pending_receive(tx_id, packet)
        log.debug(f"Stored pending receive for TX: {tx_id}")

        # Calculate frequencies of the *entire received JSON string*
        client_frequencies = utils.calculate_frequencies(original_packet_str)
        log.debug(f"Calculated frequencies for received packet (TX: {tx_id}): {client_frequencies}")

        # Send CHARACTER_COUNT response (encrypted with session key)
        char_count_packet_str = utils.create_packet(
            action=constants.ACTION_CHARACTER_COUNT,
            transaction_id=tx_id, # Echo the server's transaction ID
            data={constants.KEY_LETTER_FREQUENCIES: client_frequencies}
        )
        try:
            # Encrypt using the current session key
            encrypted_char_count_packet = crypto.encrypt_session(char_count_packet_str, current_session_key).encode('utf-8')
            log.debug(f"Sending encrypted CHARACTER_COUNT for S2C TX: {tx_id}")
            self.udp_manager.send(encrypted_char_count_packet)
        except Exception as e:
            log.exception(f"Error sending encrypted CHARACTER_COUNT for S2C TX {tx_id}: {e}")
            # Clean up pending state if sending fails
            self.client_state.remove_pending_receive(tx_id)

        # DO NOT process the message here yet. Wait for CONFIRM_COUNT from server.

    def handle_character_count(self, packet: dict, data: dict, status: str, message: str, tx_id: str, address: tuple, original_packet_str: str | None):
        """Handles the server's request for character count confirmation (C2S Flow)."""
        log.debug(f"Entered handle_character_count (C2S - Server TX: {tx_id})") # Use logger
        if not tx_id:
            log.error("Received CHARACTER_COUNT without transaction_id in data.") # Use logger
            return

        server_frequencies = data.get(constants.KEY_LETTER_FREQUENCIES)
        original_action = data.get(constants.KEY_ORIGINAL_ACTION)

        valid_original_actions = {constants.ACTION_SEND_MESSAGE, constants.ACTION_LOGIN}
        if not server_frequencies or original_action not in valid_original_actions:
            log.error(f"Invalid CHARACTER_COUNT packet data or unsupported original_action. Data: {data}") # Use logger
            return

        confirm = False # Default to false
        client_frequencies = {}
        original_packet_str_for_calc = "" # For logging/calculation
        # Store the transaction ID received from the server (e.g., C2S_...)
        # This ID MUST be used when sending CONFIRM_COUNT back.
        server_tx_id = tx_id
        # Store the client's original transaction ID if found (e.g., f21a...)
        # This ID is used to manage the pending state.
        original_client_tx_id = None

        try:
            if original_action == constants.ACTION_LOGIN:
                # Login flow seems to work correctly with the received tx_id
                original_client_tx_id = server_tx_id # Assume server echoes correctly for login
                login_data = {
                    constants.KEY_CHAT_ID: config.AI_CHAT_ID,
                    constants.KEY_PASSWORD: config.AI_PASSWORD
                }
                original_packet_str_for_calc = utils.create_packet(constants.ACTION_LOGIN, data=login_data)
                client_frequencies = utils.calculate_frequencies(original_packet_str_for_calc)
                confirm = (client_frequencies == server_frequencies)

            elif original_action == constants.ACTION_SEND_MESSAGE:
                # Server sends its own tx_id (server_tx_id), not the client's original one.
                # Find the latest pending send_message based on action, not the received tx_id.
                log.debug(f"Handling CHARACTER_COUNT for SEND_MESSAGE (Server TX: {server_tx_id}). Searching for latest pending send...")
                original_client_tx_id, pending_data = self.client_state.get_latest_pending_send_by_action(constants.ACTION_SEND_MESSAGE)

                if not pending_data or not original_client_tx_id:
                    # Log using the server's tx_id as that's what we received
                    log.error(f"Received CHARACTER_COUNT (Server TX: {server_tx_id}) but no matching pending send_message found.")
                    confirm = False
                else:
                    log.debug(f"Found corresponding pending send (Client TX: {original_client_tx_id}) for Server TX: {server_tx_id}")
                    original_packet_dict = pending_data["packet"]
                    original_packet_str_for_calc = json.dumps(original_packet_dict)
                    client_frequencies = utils.calculate_frequencies(original_packet_str_for_calc)
                    confirm = (client_frequencies == server_frequencies)

        except Exception as e:
            # Log using the server's tx_id as context
            log.exception(f"Error calculating client frequencies for Server TX {server_tx_id} (Action: {original_action}): {e}") # Use logger
            confirm = False

        if not confirm:
            # Log using the server's tx_id as context
            log.warning(f"Frequency mismatch for Server TX: {server_tx_id} (Action: {original_action}, Client TX: {original_client_tx_id or 'N/A'})") # Use logger
            if original_packet_str_for_calc:
                 log.debug(f"  Client calculated on: {original_packet_str_for_calc[:100]}...") # Use logger
            log.debug(f"  Client Freq: {client_frequencies}") # Use logger
            log.debug(f"  Server Freq: {server_frequencies}") # Use logger

        # Send CONFIRM_COUNT using the server's transaction ID
        confirm_packet_str = utils.create_packet(
            action=constants.ACTION_CONFIRM_COUNT,
            transaction_id=server_tx_id, # Use the ID received from the server
            data={constants.KEY_CONFIRM: confirm}
        )
        try:
            encrypted_confirm_packet = None
            if original_action == constants.ACTION_LOGIN:
                log.debug(f"Encrypting CONFIRM_COUNT with login key for Server TX: {server_tx_id}") # Use logger
                encrypted_confirm_packet = crypto.encrypt_login(confirm_packet_str).encode('utf-8')
            elif original_action == constants.ACTION_SEND_MESSAGE:
                 with self.client_state.lock:
                     current_session_key = self.client_state.session_key
                 if current_session_key:
                     log.debug(f"Encrypting CONFIRM_COUNT with session key for Server TX: {server_tx_id}") # Use logger
                     encrypted_confirm_packet = crypto.encrypt_session(confirm_packet_str, current_session_key).encode('utf-8')
                 else:
                     log.error(f"Cannot encrypt CONFIRM_COUNT for SEND_MESSAGE (Server TX: {server_tx_id}), session key not available.")

            if encrypted_confirm_packet:
                self.udp_manager.send(encrypted_confirm_packet)
                log.debug(f"Sent encrypted CONFIRM_COUNT (Confirm: {confirm}) for Server TX: {server_tx_id}") # Use logger
            else:
                 log.error(f"Failed to encrypt CONFIRM_COUNT for Server TX: {server_tx_id}. Packet not sent.")

        except Exception as e:
             log.exception(f"Error sending CONFIRM_COUNT for Server TX {server_tx_id}: {e}") # Use logger

        # If confirmation failed for send_message, remove the original pending state
        # using the client's transaction ID.
        if not confirm and original_action == constants.ACTION_SEND_MESSAGE and original_client_tx_id:
            removed = self.client_state.remove_pending_send(original_client_tx_id)
            if removed:
                log.warning(f"Cancelled pending send (Client TX: {original_client_tx_id}) due to frequency mismatch or error.") # Use logger
            else:
                log.warning(f"Attempted to cancel pending send (Client TX: {original_client_tx_id}) but it was already removed.")

    def handle_confirm_count(self, packet: dict, data: dict, status: str, message: str, tx_id: str, address: tuple, original_packet_str: str | None):
        """Handles the server's confirmation during an S2C handshake."""
        log.debug(f"Entered handle_confirm_count (S2C - Server TX: {tx_id})")
        if not tx_id:
            log.error("Received CONFIRM_COUNT without transaction_id in data.")
            return

        server_confirm = data.get(constants.KEY_CONFIRM, False)
        ack_status = constants.STATUS_ERROR # Default ACK status
        ack_message = "Processing failed due to server confirmation."

        # Retrieve the original pending packet
        pending_packet_data = self.client_state.get_pending_receive(tx_id)

        if not pending_packet_data:
            log.warning(f"Received CONFIRM_COUNT (Server TX: {tx_id}) but no matching pending receive found.")
            ack_message = "No corresponding pending action found."
            # Still send ACK to potentially clear server state
        elif server_confirm:
            log.info(f"Server confirmed frequency match for S2C action (Server TX: {tx_id}). Processing original packet.")
            ack_status = constants.STATUS_SUCCESS
            ack_message = constants.MSG_ACTION_SUCCESS

            # --- Process the original action based on its type ---
            original_packet = pending_packet_data # Already a dict
            original_action = original_packet.get(constants.KEY_ACTION)
            original_data = original_packet.get(constants.KEY_DATA, {})
            original_status = original_packet.get(constants.KEY_STATUS)
            original_msg = original_packet.get(constants.KEY_MESSAGE)
            original_tx_id = original_data.get(constants.KEY_TRANSACTION_ID) # Should match tx_id

            log.debug(f"Processing original action '{original_action}' from pending state (TX: {original_tx_id})")

            if original_action == constants.ACTION_RECEIVE_MESSAGE:
                sender_chatid = original_data.get(constants.KEY_SENDER_CHAT_ID)
                room_id = original_data.get(constants.KEY_ROOM_ID)
                content = original_data.get(constants.KEY_CONTENT)
                timestamp = original_data.get(constants.KEY_TIMESTAMP)

                if not all([sender_chatid, room_id, content, timestamp]):
                    log.error(f"Incomplete message data in pending packet: {original_data}")
                    ack_status = constants.STATUS_ERROR
                    ack_message = "Incomplete message data found after confirmation."
                elif sender_chatid == self.client_state.chatid:
                    log.debug(f"Ignoring own message in room {room_id} after confirmation.")
                    # ack_status remains success as the handshake was fine
                else:
                    log.info(f"Processing confirmed message in room {room_id} from {sender_chatid} at {timestamp}: {content[:50]}...")
                    ai_response_content = self.gemini_api.generate_response(content)
                    if ai_response_content:
                        self.ai_client.send_message(room_id, ai_response_content)
                    else:
                        log.warning("Failed to generate AI response for confirmed message.")
                        # Consider if this should change ack_status/message
            # Add elif blocks here for other S2C actions like ROOMS_LIST, MESSAGES_LIST etc.
            # elif original_action == constants.ACTION_ROOMS_LIST:
            #     # Process room list data
            #     pass
            else:
                log.warning(f"No specific processing logic for confirmed action '{original_action}' (TX: {tx_id}).")
                # ack_status remains success as handshake was okay

        else: # server_confirm is False
            log.warning(f"Server indicated frequency mismatch for S2C action (Server TX: {tx_id}). Cancelling.")
            ack_status = constants.STATUS_CANCELLED
            ack_message = constants.MSG_ACTION_CANCELLED

        # Clean up the pending state regardless of confirmation result
        removed = self.client_state.remove_pending_receive(tx_id)
        if removed:
             log.debug(f"Removed pending receive state for TX: {tx_id}")
        else:
             # This case was already logged if pending_packet_data was None
             pass

        # Send ACK back to the server (encrypted with session key)
        ack_packet_str = utils.create_packet(
            action=constants.ACTION_ACK,
            status=ack_status,
            message=ack_message,
            transaction_id=tx_id # Echo the server's transaction ID
        )
        try:
            with self.client_state.lock:
                current_session_key = self.client_state.session_key
            if current_session_key:
                log.debug(f"Encrypting and sending ACK ({ack_status}) for S2C TX: {tx_id}")
                encrypted_ack_packet = crypto.encrypt_session(ack_packet_str, current_session_key).encode('utf-8')
                self.udp_manager.send(encrypted_ack_packet)
            else:
                log.error(f"Cannot send ACK for S2C TX {tx_id}, session key not available.")
        except Exception as e:
            log.exception(f"Error sending ACK for S2C TX {tx_id}: {e}")

    def handle_ack(self, packet: dict, data: dict, status: str, message: str, tx_id: str, address: tuple, original_packet_str: str | None):
        """Handles the final acknowledgement from the server (C2S Flow End)."""
        # tx_id here is the one received in the ACK packet (e.g., C2S_...)
        log.debug(f"Entered handle_ack (C2S End - Server TX: {tx_id}, Status: {status})") # Use logger
        if not tx_id:
            log.error("Received ACK without transaction_id.") # Use logger
            return

        # We need the original action to know how to handle this ACK
        original_action_in_data = data.get(constants.KEY_ORIGINAL_ACTION)
        log.debug(f"ACK details - Server TX: {tx_id}, Status: {status}, OrigActionInData: {original_action_in_data}")

        # --- Handle Login Success via ACK ---
        if original_action_in_data == constants.ACTION_LOGIN and status == constants.STATUS_SUCCESS:
            log.info(f"Processing successful ACK for LOGIN (Server TX: {tx_id}).") # Use logger
            session_key = data.get(constants.KEY_SESSION_KEY)
            received_chatid = data.get(constants.KEY_CHAT_ID)

            if session_key and received_chatid == self.client_state.chatid:
                with self.client_state.lock:
                    already_logged_in = self.client_state.logged_in
                if not already_logged_in:
                    self.client_state.set_session(session_key)
                else:
                    log.debug(f"Ignoring login success ACK (Server TX: {tx_id}) as client is already logged in.") # Use logger
            else:
                log.error(f"Login ACK success packet missing session key or mismatched chatid. Data: {data}") # Use logger
                self.ai_client.stop() # Stop if login ACK is malformed
            # Login doesn't use pending_sends in the same way, so no removal needed here.
            return # Handled login success

        # --- Handle ACK for send_message ---
        # Attempt to remove the corresponding pending send using the workaround
        if original_action_in_data == constants.ACTION_SEND_MESSAGE:
            log.debug(f"Received ACK for SEND_MESSAGE (Server TX: {tx_id}, Status: {status}). Attempting to find and remove corresponding pending send...")
            # Find the latest pending send_message (assuming it's the one this ACK relates to)
            client_tx_id_to_remove, pending_data = self.client_state.get_latest_pending_send_by_action(constants.ACTION_SEND_MESSAGE)

            if client_tx_id_to_remove:
                # Log based on status
                if status == constants.STATUS_SUCCESS:
                    log.info(f"Server acknowledged successful processing of '{original_action_in_data}' (Server TX: {tx_id}, Client TX: {client_tx_id_to_remove}). Message likely sent/saved.") # Use logger
                elif status == constants.STATUS_CANCELLED:
                     log.warning(f"Server cancelled processing of '{original_action_in_data}' (Server TX: {tx_id}, Client TX: {client_tx_id_to_remove}): {message}") # Use logger
                else: # Failure or other error
                    log.error(f"Server failed to process '{original_action_in_data}' (Server TX: {tx_id}, Client TX: {client_tx_id_to_remove}): {message}") # Use logger

                # Remove the pending state using the client's original transaction ID
                removed = self.client_state.remove_pending_send(client_tx_id_to_remove)
                if removed:
                    log.debug(f"Removed pending send state for Client TX: {client_tx_id_to_remove}")
                else:
                    # This shouldn't happen if we just found it, but log just in case
                    log.warning(f"Tried to remove pending send (Client TX: {client_tx_id_to_remove}) but it was already gone.")
            else:
                log.warning(f"Received ACK for SEND_MESSAGE (Server TX: {tx_id}, Status: {status}) but couldn't find a matching pending send to remove.")
            return # Handled send_message ACK

        # --- Handle other ACKs (e.g., errors during login, or unexpected ACKs) ---
        if original_action_in_data == constants.ACTION_LOGIN and status != constants.STATUS_SUCCESS:
             log.error(f"Login handshake failed at ACK stage (Server TX: {tx_id}, Status: {status}): {message}") # Use logger
             self.ai_client.stop()
             return # Handled login failure ACK

        # Fallback for ACKs not matching known flows
        log.info(f"Received unhandled ACK (Server TX: {tx_id}, Status: {status}, OrigAction: {original_action_in_data or 'N/A'}). Might be for an unmanaged flow or already removed.") # Use logger

    def handle_error(self, packet: dict, data: dict, status: str, message: str, tx_id: str, address: tuple, original_packet_str: str | None):
        log.debug(f"Entered handle_error (TX: {tx_id})") # Use logger
        original_action = data.get(constants.KEY_ORIGINAL_ACTION, "N/A")
        log.error(f"Received ERROR from server regarding action '{original_action}' (TX: {tx_id or 'N/A'}): {message}") # Use logger

        if original_action == constants.ACTION_LOGIN:
            log.error("Login failed according to error message. Stopping client.") # Use logger
            self.ai_client.stop()
        elif tx_id:
            # Attempt to remove pending states associated with this transaction ID
            # Note: tx_id in an ERROR packet might be the server's or client's ID depending on context.
            removed_send = self.client_state.remove_pending_send(tx_id)
            removed_receive = self.client_state.remove_pending_receive(tx_id)
            if removed_send or removed_receive:
                log.warning(f"Removed pending transaction {tx_id} due to server error.") # Use logger
            else:
                # Also try finding by action if it was a C2S send_message error reported late
                if original_action == constants.ACTION_SEND_MESSAGE:
                     client_tx_id_to_remove, _ = self.client_state.get_latest_pending_send_by_action(constants.ACTION_SEND_MESSAGE)
                     if client_tx_id_to_remove:
                         removed = self.client_state.remove_pending_send(client_tx_id_to_remove)
                         if removed:
                              log.warning(f"Removed latest pending send ({client_tx_id_to_remove}) for action {original_action} due to server error.")


    def handle_unknown(self, packet: dict, data: dict, status: str, message: str, tx_id: str, address: tuple, original_packet_str: str | None):
        action = packet.get(constants.KEY_ACTION, "UNKNOWN")
        log.warning(f"Received unhandled action '{action}' from {address}. Packet: {packet}") # Use logger

