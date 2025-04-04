package UdpChatServer.handler;

import java.net.InetAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import UdpChatServer.db.RoomDAO;
import UdpChatServer.manager.ClientSessionManager;
import UdpChatServer.manager.RoomManager;
import UdpChatServer.model.Constants;
import UdpChatServer.model.PendingMessageInfo;
import UdpChatServer.net.UdpSender;
import UdpChatServer.util.JsonHelper;

/**
 * Handles room management operations: adding/removing users, deleting rooms, and renaming rooms.
 * All operations require the requesting user to be the room owner.
 */
public class RoomManagementHandler {
    private static final Logger log = LoggerFactory.getLogger(RoomManagementHandler.class);
    
    private final ClientSessionManager sessionManager;
    private final RoomManager roomManager;
    private final RoomDAO roomDAO;
    private final UdpSender udpSender;
    
    public RoomManagementHandler(ClientSessionManager sessionManager, RoomManager roomManager, 
                                 RoomDAO roomDAO, UdpSender udpSender) {
        this.sessionManager = sessionManager;
        this.roomManager = roomManager;
        this.roomDAO = roomDAO;
        this.udpSender = udpSender;
    }
    
    /**
     * Processes a confirmed request to add a user to a room
     * 
     * @param pendingInfo Information about the confirmed transaction
     * @return true if user was added successfully, false otherwise
     */
    public boolean processConfirmedAddUserToRoom(PendingMessageInfo pendingInfo) {
        if (pendingInfo == null || pendingInfo.getDirection() != PendingMessageInfo.Direction.CLIENT_TO_SERVER ||
            !Constants.ACTION_ADD_USER_TO_ROOM.equals(pendingInfo.getOriginalAction())) {
            log.error("Invalid pending info for add user to room: {}", pendingInfo);
            return false;
        }
        
        InetAddress clientAddress = pendingInfo.getPartnerAddress();
        int clientPort = pendingInfo.getPartnerPort();
        String transactionKey = pendingInfo.getTransactionKey();
        
        JsonObject originalRequest = pendingInfo.getOriginalMessageJson();
        JsonObject requestData = originalRequest.getAsJsonObject(Constants.KEY_DATA);
        
        String requestingUser = requestData.get(Constants.KEY_CHAT_ID).getAsString();
        String roomId = requestData.get(Constants.KEY_ROOM_ID).getAsString();
        String userToAdd = requestData.get("user_to_add").getAsString();
        
        log.info("Processing confirmed add_user_to_room: User '{}' wants to add '{}' to room '{}'", 
                requestingUser, userToAdd, roomId);
        
        // Validate session
        if (!sessionManager.validateSession(requestingUser, clientAddress, clientPort)) {
            log.warn("Invalid session for add_user_to_room from {}", requestingUser);
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                    "Invalid session.", transactionKey);
            return false;
        }
        
        // Add user to room in database
        boolean success = roomDAO.addUserToRoomByOwner(roomId, userToAdd, requestingUser);
        
        if (success) {
            // Also update in-memory representation
            roomManager.addUserToRoom(roomId, userToAdd);
            
            // Send success response
            JsonObject responseData = new JsonObject();
            responseData.addProperty(Constants.KEY_ROOM_ID, roomId);
            responseData.addProperty("user_added", userToAdd);
            
            JsonObject response = JsonHelper.createReply(
                Constants.ACTION_USER_ADDED,
                Constants.STATUS_SUCCESS,
                "User added to room successfully.",
                responseData
            );
            
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), true,
                    response.toString(), transactionKey);
            
            return true;
        } else {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                    "Failed to add user to room. You may not be the room owner.", transactionKey);
            return false;
        }
    }
    
    /**
     * Processes a confirmed request to remove a user from a room
     * 
     * @param pendingInfo Information about the confirmed transaction
     * @return true if user was removed successfully, false otherwise
     */
    public boolean processConfirmedRemoveUserFromRoom(PendingMessageInfo pendingInfo) {
        if (pendingInfo == null || pendingInfo.getDirection() != PendingMessageInfo.Direction.CLIENT_TO_SERVER ||
            !Constants.ACTION_REMOVE_USER_FROM_ROOM.equals(pendingInfo.getOriginalAction())) {
            log.error("Invalid pending info for remove user from room: {}", pendingInfo);
            return false;
        }
        
        InetAddress clientAddress = pendingInfo.getPartnerAddress();
        int clientPort = pendingInfo.getPartnerPort();
        String transactionKey = pendingInfo.getTransactionKey();
        
        JsonObject originalRequest = pendingInfo.getOriginalMessageJson();
        JsonObject requestData = originalRequest.getAsJsonObject(Constants.KEY_DATA);
        
        String requestingUser = requestData.get(Constants.KEY_CHAT_ID).getAsString();
        String roomId = requestData.get(Constants.KEY_ROOM_ID).getAsString();
        String userToRemove = requestData.get("user_to_remove").getAsString();
        
        log.info("Processing confirmed remove_user_from_room: User '{}' wants to remove '{}' from room '{}'", 
                requestingUser, userToRemove, roomId);
        
        // Validate session
        if (!sessionManager.validateSession(requestingUser, clientAddress, clientPort)) {
            log.warn("Invalid session for remove_user_from_room from {}", requestingUser);
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                    "Invalid session.", transactionKey);
            return false;
        }
        
        // Remove user from room in database
        boolean success = roomDAO.removeUserFromRoomByOwner(roomId, userToRemove, requestingUser);
        
        if (success) {
            // Also update in-memory representation
            roomManager.removeUserFromRoom(roomId, userToRemove);
            
            // Send success response
            JsonObject responseData = new JsonObject();
            responseData.addProperty(Constants.KEY_ROOM_ID, roomId);
            responseData.addProperty("user_removed", userToRemove);
            
            JsonObject response = JsonHelper.createReply(
                Constants.ACTION_USER_REMOVED,
                Constants.STATUS_SUCCESS,
                "User removed from room successfully.",
                responseData
            );
            
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), true,
                    response.toString(), transactionKey);
            
            return true;
        } else {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                    "Failed to remove user from room. You may not be the room owner or trying to remove the owner.", 
                    transactionKey);
            return false;
        }
    }
    
    /**
     * Processes a confirmed request to delete a room
     * 
     * @param pendingInfo Information about the confirmed transaction
     * @return true if room was deleted successfully, false otherwise
     */
    public boolean processConfirmedDeleteRoom(PendingMessageInfo pendingInfo) {
        if (pendingInfo == null || pendingInfo.getDirection() != PendingMessageInfo.Direction.CLIENT_TO_SERVER ||
            !Constants.ACTION_DELETE_ROOM.equals(pendingInfo.getOriginalAction())) {
            log.error("Invalid pending info for delete room: {}", pendingInfo);
            return false;
        }
        
        InetAddress clientAddress = pendingInfo.getPartnerAddress();
        int clientPort = pendingInfo.getPartnerPort();
        String transactionKey = pendingInfo.getTransactionKey();
        
        JsonObject originalRequest = pendingInfo.getOriginalMessageJson();
        JsonObject requestData = originalRequest.getAsJsonObject(Constants.KEY_DATA);
        
        String requestingUser = requestData.get(Constants.KEY_CHAT_ID).getAsString();
        String roomId = requestData.get(Constants.KEY_ROOM_ID).getAsString();
        
        log.info("Processing confirmed delete_room: User '{}' wants to delete room '{}'", 
                requestingUser, roomId);
        
        // Validate session
        if (!sessionManager.validateSession(requestingUser, clientAddress, clientPort)) {
            log.warn("Invalid session for delete_room from {}", requestingUser);
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                    "Invalid session.", transactionKey);
            return false;
        }
        
        // Delete room from database
        boolean success = roomDAO.deleteRoom(roomId, requestingUser);
        
        if (success) {
            // Also remove from in-memory representation - this should cascade to removing all users from room
            roomManager.removeRoom(roomId);
            
            // Send success response
            JsonObject responseData = new JsonObject();
            responseData.addProperty(Constants.KEY_ROOM_ID, roomId);
            
            JsonObject response = JsonHelper.createReply(
                Constants.ACTION_ROOM_DELETED,
                Constants.STATUS_SUCCESS,
                "Room deleted successfully.",
                responseData
            );
            
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), true,
                    response.toString(), transactionKey);
            
            return true;
        } else {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                    "Failed to delete room. You may not be the room owner.", transactionKey);
            return false;
        }
    }
    
    /**
     * Processes a confirmed request to rename a room
     * 
     * @param pendingInfo Information about the confirmed transaction
     * @return true if room was renamed successfully, false otherwise
     */
    public boolean processConfirmedRenameRoom(PendingMessageInfo pendingInfo) {
        if (pendingInfo == null || pendingInfo.getDirection() != PendingMessageInfo.Direction.CLIENT_TO_SERVER ||
            !Constants.ACTION_RENAME_ROOM.equals(pendingInfo.getOriginalAction())) {
            log.error("Invalid pending info for rename room: {}", pendingInfo);
            return false;
        }
        
        InetAddress clientAddress = pendingInfo.getPartnerAddress();
        int clientPort = pendingInfo.getPartnerPort();
        String transactionKey = pendingInfo.getTransactionKey();
        
        JsonObject originalRequest = pendingInfo.getOriginalMessageJson();
        JsonObject requestData = originalRequest.getAsJsonObject(Constants.KEY_DATA);
        
        String requestingUser = requestData.get(Constants.KEY_CHAT_ID).getAsString();
        String roomId = requestData.get(Constants.KEY_ROOM_ID).getAsString();
        String newName = requestData.get(Constants.KEY_ROOM_NAME).getAsString();
        
        log.info("Processing confirmed rename_room: User '{}' wants to rename room '{}' to '{}'", 
                requestingUser, roomId, newName);
        
        // Validate session
        if (!sessionManager.validateSession(requestingUser, clientAddress, clientPort)) {
            log.warn("Invalid session for rename_room from {}", requestingUser);
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                    "Invalid session.", transactionKey);
            return false;
        }
        
        // Rename room in database
        boolean success = roomDAO.renameRoom(roomId, newName, requestingUser);
        
        if (success) {
            // Send success response
            JsonObject responseData = new JsonObject();
            responseData.addProperty(Constants.KEY_ROOM_ID, roomId);
            responseData.addProperty(Constants.KEY_ROOM_NAME, newName);
            
            JsonObject response = JsonHelper.createReply(
                Constants.ACTION_ROOM_RENAMED,
                Constants.STATUS_SUCCESS,
                "Room renamed successfully.",
                responseData
            );
            
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), true,
                    response.toString(), transactionKey);
            
            return true;
        } else {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), false,
                    "Failed to rename room. You may not be the room owner.", transactionKey);
            return false;
        }
    }
}
