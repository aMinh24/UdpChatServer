# --- Network ---
DEFAULT_SERVER_PORT = 9876
MAX_UDP_PACKET_SIZE = 65507

# --- Security ---
"""
Fixed key string used ONLY for encrypting the initial login request (Client -> Server)
and decrypting the login success/failure response (Server -> Client).
Its length (9) determines the Caesar shift (shift = 9).
DO NOT use this for encrypting/decrypting any other communication after login.
Session-based communication uses the session_key.
"""
FIXED_LOGIN_KEY_STRING = "LoginKey9" # Length 9

# --- JSON Keys ---
# Common
KEY_ACTION = "action"
KEY_STATUS = "status"
KEY_MESSAGE = "message"
KEY_DATA = "data"
KEY_CHAT_ID = "chatid"
KEY_TRANSACTION_ID = "transaction_id"

# Login Action
KEY_PASSWORD = "password"
KEY_SESSION_KEY = "session_key"

# Room Creation
KEY_PARTICIPANTS = "participants" # List of chatids to add to room
KEY_ROOM_ID = "room_id"
KEY_ROOM_NAME = "room_name" # Add room name key

# Send Message Action
KEY_CONTENT = "content"
KEY_SENDER_CHAT_ID = "sender_chatid"
KEY_TIMESTAMP = "timestamp"
KEY_LETTER_COUNT = "letter_count" # Kept for potential compatibility, but frequencies are used now
KEY_LETTER_FREQUENCIES = "letter_frequencies" # Key for frequency map in CHARACTER_COUNT and CONFIRM_COUNT
KEY_CONFIRM = "confirm" # Boolean key in CONFIRM_COUNT
KEY_ORIGINAL_ACTION = "original_action" # Key to store the action being confirmed/acked
# Note: KEY_CONFIRMATION is removed as the new flow uses KEY_CONFIRM within CONFIRM_COUNT action

# --- Action Values ---
ACTION_LOGIN = "login"
ACTION_REGISTER = "register" # Hành động đăng ký
ACTION_REGISTER_SUCCESS = "register_success" # Phản hồi đăng ký thành công
ACTION_GET_USERS = "get_users" # Hành động lấy danh sách người dùng
ACTION_USERS_LIST = "users_list" # Phản hồi danh sách người dùng
ACTION_CREATE_ROOM = "create_room"
ACTION_GET_ROOMS = "get_rooms" # Thêm action xem danh sách room
ACTION_GET_MESSAGES = "get_messages" # Thêm action xem tin nhắn
ACTION_SEND_MESSAGE = "send_message" # Initial request from client
ACTION_RECEIVE_MESSAGE = "receive_message" # Server forwarding message to other clients
ACTION_ERROR = "error"
ACTION_LOGIN_SUCCESS = "login_success" # Server response to login
ACTION_ROOM_CREATED = "room_created" # Server response to create_room
ACTION_ROOMS_LIST = "rooms_list" # Server response to get_rooms
ACTION_MESSAGES_LIST = "messages_list" # Server response to get_messages
ACTION_MESSAGE_SENT = "message_sent" # Final confirmation to sender after successful delivery/save

# New actions for the 3-way handshake
ACTION_CHARACTER_COUNT = "character_count" # Server -> Client (after initial Client req) OR Client -> Server (after initial Server req)
ACTION_CONFIRM_COUNT = "confirm_count"     # Client -> Server (response to CHARACTER_COUNT) OR Server -> Client (response to CHARACTER_COUNT)
ACTION_ACK = "ack"                         # Server -> Client (final step for Client->Server flow) OR Client -> Server (final step for Server->Client flow)
# Note: ACTION_CONFIRM_MESSAGE is removed as the new flow uses ACTION_CONFIRM_COUNT
# Note: ACTION_MESSAGE_CONFIRMED_BY_SERVER is removed/replaced by the new flow steps

# Room management actions
ACTION_ADD_USER_TO_ROOM = "add_user_to_room"
ACTION_REMOVE_USER_FROM_ROOM = "remove_user_from_room"
ACTION_DELETE_ROOM = "delete_room"
ACTION_RENAME_ROOM = "rename_room"
ACTION_GET_USER_ROOMS = "get_user_rooms" # New action to request room list

# Room user list action
ACTION_GET_ROOM_USERS = "get_room_users"
ACTION_ROOM_USERS_LIST = "room_users_list"

# Room management replies
ACTION_USER_ADDED = "user_added"
ACTION_USER_REMOVED = "user_removed"
ACTION_ROOM_DELETED = "room_deleted"
ACTION_ROOM_RENAMED = "room_renamed"
ACTION_USER_ROOM_LIST = "user_room_list" # New response for room list
ACTION_RECIEVE_ROOM = "recieve_room"
ACTION_RECIEVE_LISTUSER = "recieve_listuser" # New action to receive room info

# --- Status Values ---
STATUS_SUCCESS = "success"
STATUS_FAILURE = "failure"
STATUS_ERROR = "error"
STATUS_CANCELLED = "cancelled" # Added status for ACK when confirm is false

# --- Error Messages ---
ERROR_MSG_INVALID_JSON = "Invalid JSON format or decryption failed." # Updated message
ERROR_MSG_UNKNOWN_ACTION = "Unknown action specified."
ERROR_MSG_MISSING_FIELD = "Missing required field: "
ERROR_MSG_AUTHENTICATION_FAILED = "Authentication failed. Invalid chatid or password."
ERROR_MSG_INTERNAL_SERVER_ERROR = "Internal server error."
ERROR_MSG_NOT_LOGGED_IN = "User not logged in or session expired."
ERROR_MSG_ROOM_NOT_FOUND = "Room not found."
ERROR_MSG_INVALID_TIME = "Invalid time format."
ERROR_MSG_NOT_IN_ROOM = "You are not a participant in this room."
ERROR_MSG_INVALID_CONFIRMATION = "Message confirmation failed (letter frequency mismatch)." # Updated message
ERROR_MSG_INVALID_PARTICIPANTS = "Invalid participants list. Need at least 2 participants."
ERROR_MSG_USER_NOT_FOUND = "One or more users not found."
ERROR_MSG_DECRYPTION_FAILED = "Failed to decrypt message with provided session key." # Added message
ERROR_MSG_PENDING_ACTION_NOT_FOUND = "No pending action found for this confirmation/ack."
ERROR_MSG_INVALID_STATE = "Invalid state for current action."

MSG_ACTION_SUCCESS = "Action processed successfully."
MSG_ACTION_FAILED = "Action failed to process."
MSG_ACTION_CANCELLED = "Action cancelled by the user."
MSG_ACTION_PENDING = "Action is pending confirmation."

# --- File Transfer Constants ---
FILE_TRANSFER_SERVER_PORT = 9877
ACTION_FILE_SEND_INIT = "file_send_init"
ACTION_FILE_SEND_DATA = "file_send_data"
ACTION_FILE_SEND_FIN = "file_send_fin"
ACTION_FILE_LIST_REQ = "file_list_req"
ACTION_FILE_DOWN_REQ = "file_down_req"
ACTION_FILE_DOWN_META = "file_down_meta"
ACTION_FILE_DOWN_DATA = "file_down_data"
ACTION_FILE_DOWN_FIN = "file_down_fin"

# --- File Transfer Storage ---
STORAGE_DIR = "server_storage"
BUFFER_SIZE = 1024 * 64
DATA_CHUNK_SIZE = 1024 * 32

# --- Other ---
SESSION_CLEANUP_INTERVAL_MS = 5 * 60 * 1000 # 5 minutes
SESSION_MAX_INACTIVE_INTERVAL_MS = 30 * 60 * 1000 # 30 minutes
PENDING_MESSAGE_TIMEOUT_MS = 60 * 1000 # 1 minute timeout for pending confirmations/acks
