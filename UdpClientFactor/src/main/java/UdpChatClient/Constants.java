package UdpChatClient;

public final class Constants {

    // Private constructor to prevent instantiation
    private Constants() {}

    // --- Network ---
    public static final int DEFAULT_SERVER_PORT = 9876;
    public static final int MAX_UDP_PACKET_SIZE = 65507;
    

    // --- Security ---
    /**
     * Fixed key string used ONLY for decrypting the initial login request.
     * Its length (9) determines the Caesar shift (shift = 9).
     * DO NOT use this for encrypting replies or any other communication.
     */
    public static final String FIXED_LOGIN_KEY_STRING = "LoginKey9"; // Length 9

    // --- JSON Keys ---
    // Common
    public static final String KEY_ACTION = "action";
    public static final String KEY_STATUS = "status";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_DATA = "data";
    public static final String KEY_CHAT_ID = "chatid";

    // Login Action
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_SESSION_KEY = "session_key";

    // Room Creation
    public static final String KEY_PARTICIPANTS = "participants"; // List of chatids to add to room
    public static final String KEY_ROOM_ID = "room_id";
    public static final String KEY_ROOM_NAME = "room_name"; 

    // Send Message Action
    public static final String KEY_CONTENT = "content";
    public static final String KEY_SENDER_CHAT_ID = "sender_chatid";
    public static final String KEY_TIMESTAMP = "timestamp";
    public static final String KEY_LETTER_COUNT = "letter_count"; // Kept for potential compatibility, but frequencies are used now
    public static final String KEY_LETTER_FREQUENCIES = "letter_frequencies"; // Key for frequency map in CHARACTER_COUNT and CONFIRM_COUNT
    public static final String KEY_CONFIRM = "confirm"; // Boolean key in CONFIRM_COUNT
    public static final String KEY_ORIGINAL_ACTION = "original_action"; // Key to store the action being confirmed/acked
    public static final String KEY_FROM_TIME = "from_time"; // Key for filtering messages by time
    // Note: KEY_CONFIRMATION is removed as the new flow uses KEY_CONFIRM within CONFIRM_COUNT action

    // --- Action Values ---
    public static final String ACTION_REGISTER = "register"; // Dăng ký
    public static final String ACTION_REGISTER_SUCCESS = "register_success"; // Phản hồi đăng ký thành công
    public static final String ACTION_GET_USERS = "get_users"; // Lấy danh sách người dùng
    public static final String ACTION_USERS_LIST = "users_list";
    public static final String ACTION_LOGIN = "login";
    public static final String ACTION_CREATE_ROOM = "create_room";
    public static final String ACTION_GET_ROOMS = "get_rooms"; // Thêm action xem danh sách room
    public static final String ACTION_GET_MESSAGES = "get_messages"; // Thêm action xem tin nhắn
    public static final String ACTION_SEND_MESSAGE = "send_message"; // Initial request from client
    public static final String ACTION_RECEIVE_MESSAGE = "receive_message"; // Server forwarding message to other clients
    public static final String ACTION_ERROR = "error";
    public static final String ACTION_LOGIN_SUCCESS = "login_success"; // Server response to login
    public static final String ACTION_ROOM_CREATED = "room_created"; // Server response to create_room
    public static final String ACTION_ROOMS_LIST = "rooms_list"; // Server response to get_rooms
    public static final String ACTION_MESSAGES_LIST = "messages_list"; // Server response to get_messages
    public static final String ACTION_MESSAGE_SENT = "message_sent"; // Final confirmation to sender after successful delivery/save

    // New actions for the 3-way handshake
    public static final String ACTION_CHARACTER_COUNT = "character_count"; // Server -> Client (after initial Client req) OR Client -> Server (after initial Server req)
    public static final String ACTION_CONFIRM_COUNT = "confirm_count";     // Client -> Server (response to CHARACTER_COUNT) OR Server -> Client (response to CHARACTER_COUNT)
    public static final String ACTION_ACK = "ack";                         // Server -> Client (final step for Client->Server flow) OR Client -> Server (final step for Server->Client flow)
    // Note: ACTION_CONFIRM_MESSAGE is removed as the new flow uses ACTION_CONFIRM_COUNT
    // Note: ACTION_MESSAGE_CONFIRMED_BY_SERVER is removed/replaced by the new flow steps

    public static final String ACTION_ADD_USER_TO_ROOM = "add_user_to_room"; // Add user to a room
    public static final String ACTION_USER_ADDED = "user_added"; // Server response for add_user_to_room
    public static final String ACTION_REMOVE_USER_FROM_ROOM = "remove_user_from_room";
    public static final String ACTION_USER_REMOVED = "user_removed";
    public static final String ACTION_DELETE_ROOM = "delete_room";
    public static final String ACTION_ROOM_DELETED = "room_deleted";
    public static final String ACTION_RENAME_ROOM = "rename_room";
    public static final String ACTION_ROOM_RENAMED = "room_renamed";

    // --- Status Values ---
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILURE = "failure";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_CANCELLED = "cancelled"; // Added status for ACK when confirm is false

    // --- Error Messages ---
    public static final String ERROR_MSG_INVALID_JSON = "Invalid JSON format or decryption failed."; // Updated message
    public static final String ERROR_MSG_UNKNOWN_ACTION = "Unknown action specified.";
    public static final String ERROR_MSG_MISSING_FIELD = "Missing required field: ";
    public static final String ERROR_MSG_AUTHENTICATION_FAILED = "Authentication failed. Invalid chatid or password.";
    public static final String ERROR_MSG_INTERNAL_SERVER_ERROR = "Internal server error.";
    public static final String ERROR_MSG_NOT_LOGGED_IN = "User not logged in or session expired.";
    public static final String ERROR_MSG_ROOM_NOT_FOUND = "Room not found.";
    public static final String ERROR_MSG_INVALID_TIME = "Invalid time format.";
    public static final String ERROR_MSG_NOT_IN_ROOM = "You are not a participant in this room.";
    public static final String ERROR_MSG_INVALID_CONFIRMATION = "Message confirmation failed (letter frequency mismatch)."; // Updated message
    public static final String ERROR_MSG_INVALID_PARTICIPANTS = "Invalid participants list. Need at least 2 participants.";
    public static final String ERROR_MSG_USER_NOT_FOUND = "One or more users not found.";
    public static final String ERROR_MSG_DECRYPTION_FAILED = "Failed to decrypt message with provided session key."; // Added message
    public static final String ERROR_MSG_PENDING_ACTION_NOT_FOUND = "No pending action found for this confirmation/ack.";
    public static final String ERROR_MSG_INVALID_STATE = "Invalid state for current action.";

    // --- Client Command Definitions ---
    public static final String CMD_REGISTER = "/register";
    public static final String CMD_GET_USERS = "/users";
    public static final String CMD_REGISTER_DESC = "/register <chatid> <password> - Register for new account";
    public static final String CMD_GET_USERS_DESC = "/users - Show all users";
    public static final String CMD_LOGIN = "/login";
    public static final String CMD_CREATE_ROOM = "/create";
    public static final String CMD_SEND = "/send";
    public static final String CMD_HELP = "/help";
    public static final String CMD_EXIT = "/exit";
    public static final String CMD_LIST_ROOMS = "/rooms";
    public static final String CMD_LIST_MESSAGES = "/messages";
    public static final String CMD_LOGIN_DESC = "/login <chatid> <password> - Đăng nhập vào hệ thống";
    public static final String CMD_CREATE_ROOM_DESC = "/create <room_name> <user2> [user3 ...] - Tạo phòng chat với các người dùng được chỉ định";
    public static final String CMD_SEND_DESC = "/send <room_id> <message> - Gửi tin nhắn đến phòng chat";
    public static final String CMD_HELP_DESC = "/help - Hiển thị hướng dẫn này";
    public static final String CMD_EXIT_DESC = "/exit - Thoát chương trình";
    public static final String CMD_LIST_ROOMS_DESC = "/rooms - Hiển thị danh sách phòng chat của bạn";
    public static final String CMD_LIST_MESSAGES_DESC = "/messages <room_id> [time_option] - Hiển thị tin nhắn trong phòng chat";
    public static final String CMD_ADD_USER = "/adduser";
    public static final String CMD_ADD_USER_DESC = "/adduser <room_id> <username> - Add a user to an existing room";
    public static final String CMD_REMOVE_USER = "/removeuser";
    public static final String CMD_REMOVE_USER_DESC = "/removeuser <room_id> <username> - Remove a user from an existing room";
    public static final String CMD_DELETE_ROOM = "/deleteroom";
    public static final String CMD_DELETE_ROOM_DESC = "/deleteroom <room_id> - Delete an existing room";
    public static final String CMD_RENAME_ROOM = "/renameroom";
    public static final String CMD_RENAME_ROOM_DESC = "/renameroom <room_id> <new_room_name> - Rename an existing room";
    public static final String TIME_OPTION_HOURS = "hours";
    public static final String TIME_OPTION_DAYS = "days";
    public static final String TIME_OPTION_WEEKS = "weeks";
    public static final String TIME_OPTION_ALL = "all";

    // --- Other ---
    public static final long SESSION_CLEANUP_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes
    public static final long SESSION_MAX_INACTIVE_INTERVAL_MS = 30 * 60 * 1000; // 30 minutes
    public static final long PENDING_MESSAGE_TIMEOUT_MS = 60 * 1000; // 1 minute timeout for pending confirmations/acks
}
