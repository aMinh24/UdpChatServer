package UdpChatServer.handler;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.manager.ClientSessionManager;
import UdpChatServer.model.Constants;
import UdpChatServer.model.PendingMessageInfo;
import UdpChatServer.net.UdpSender;

import java.net.InetAddress;

public class RenameRoomHandler {
    private static final Logger log = LoggerFactory.getLogger(RenameRoomHandler.class);
    private final ClientSessionManager sessionManager;
    private final RoomDAO roomDAO;
    private final UdpSender udpSender;

    public RenameRoomHandler(ClientSessionManager sessionManager, RoomDAO roomDAO, UdpSender udpSender) {
        this.sessionManager = sessionManager;
        this.roomDAO = roomDAO;
        this.udpSender = udpSender;
    }

    public boolean processConfirmedRenameRoom(PendingMessageInfo pendingInfo) {
        InetAddress clientAddress = pendingInfo.getPartnerAddress();
        int clientPort = pendingInfo.getPartnerPort();
        JsonObject data = pendingInfo.getOriginalMessageJson().getAsJsonObject(Constants.KEY_DATA);
        String transactionKey = pendingInfo.getTransactionKey();

        String chatId = data.get(Constants.KEY_CHAT_ID).getAsString();
        String roomId = data.get(Constants.KEY_ROOM_ID).getAsString(); // Sử dụng KEY_ROOM_ID thay vì "room_id"
        String newName = data.get(Constants.KEY_NEW_NAME).getAsString(); // Sử dụng KEY_NEW_NAME thay vì "new_name"

        if (!sessionManager.validateSession(chatId, clientAddress, clientPort)) {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), Constants.STATUS_FAILURE_BOOL, "Invalid session.", transactionKey);
            return false;
        }

        String ownerChatId = roomDAO.getRoomOwner(roomId);
        if (ownerChatId == null || !ownerChatId.equals(chatId)) {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), Constants.STATUS_FAILURE_BOOL, "Only the room owner can rename the room.", transactionKey);
            return false;
        }

        boolean success = roomDAO.renameRoom(roomId, newName);
        if (success) {
            JsonObject responseData = new JsonObject();
            responseData.addProperty(Constants.KEY_ROOM_ID, roomId); // Sử dụng KEY_ROOM_ID thay vì "room_id"
            responseData.addProperty(Constants.KEY_NEW_NAME, newName); // Sử dụng KEY_NEW_NAME thay vì "new_name"
            udpSender.initiateServerToClientFlow(Constants.ACTION_RENAME_ROOM_SUCCESS, responseData, clientAddress, clientPort, transactionKey);
            log.info("Room {} renamed to {} by {}.", roomId, newName, chatId);
            return true;
        } else {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), Constants.STATUS_FAILURE_BOOL, "Failed to rename room.", transactionKey);
            return false;
        }
    }
}