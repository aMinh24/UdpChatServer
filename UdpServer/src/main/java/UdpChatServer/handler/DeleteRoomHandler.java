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

public class DeleteRoomHandler {
    private static final Logger log = LoggerFactory.getLogger(DeleteRoomHandler.class);
    private final ClientSessionManager sessionManager;
    private final RoomDAO roomDAO;
    private final UdpSender udpSender;

    public DeleteRoomHandler(ClientSessionManager sessionManager, RoomDAO roomDAO, UdpSender udpSender) {
        this.sessionManager = sessionManager;
        this.roomDAO = roomDAO;
        this.udpSender = udpSender;
    }

    public boolean processConfirmedDeleteRoom(PendingMessageInfo pendingInfo) {
        InetAddress clientAddress = pendingInfo.getPartnerAddress();
        int clientPort = pendingInfo.getPartnerPort();
        JsonObject data = pendingInfo.getOriginalMessageJson().getAsJsonObject(Constants.KEY_DATA);
        String transactionKey = pendingInfo.getTransactionKey();

        String chatId = data.get(Constants.KEY_CHAT_ID).getAsString();
        String roomId = data.get(Constants.KEY_ROOM_ID).getAsString(); // Sử dụng KEY_ROOM_ID

        if (!sessionManager.validateSession(chatId, clientAddress, clientPort)) {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), Constants.STATUS_FAILURE_BOOL, "Invalid session.", transactionKey);
            return false;
        }

        String ownerChatId = roomDAO.getRoomOwner(roomId);
        if (ownerChatId == null || !ownerChatId.equals(chatId)) {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), Constants.STATUS_FAILURE_BOOL, "Only the room owner can delete the room.", transactionKey);
            return false;
        }

        boolean success = roomDAO.deleteRoom(roomId);
        if (success) {
            JsonObject responseData = new JsonObject();
            responseData.addProperty(Constants.KEY_ROOM_ID, roomId); // Sử dụng KEY_ROOM_ID
            udpSender.initiateServerToClientFlow(Constants.ACTION_DELETE_ROOM_SUCCESS, responseData, clientAddress, clientPort, transactionKey);
            log.info("Room {} was deleted by {}.", roomId, chatId);
            return true;
        } else {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), Constants.STATUS_FAILURE_BOOL, "Failed to delete room.", transactionKey);
            return false;
        }
    }
}