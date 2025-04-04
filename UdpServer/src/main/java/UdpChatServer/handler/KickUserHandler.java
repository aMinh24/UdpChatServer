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

public class KickUserHandler {
    private static final Logger log = LoggerFactory.getLogger(KickUserHandler.class);
    private final ClientSessionManager sessionManager;
    private final RoomDAO roomDAO;
    private final UdpSender udpSender;

    public KickUserHandler(ClientSessionManager sessionManager, RoomDAO roomDAO, UdpSender udpSender) {
        this.sessionManager = sessionManager;
        this.roomDAO = roomDAO;
        this.udpSender = udpSender;
    }

    public boolean processConfirmedKickUser(PendingMessageInfo pendingInfo) {
        InetAddress clientAddress = pendingInfo.getPartnerAddress();
        int clientPort = pendingInfo.getPartnerPort();
        JsonObject data = pendingInfo.getOriginalMessageJson().getAsJsonObject(Constants.KEY_DATA);
        String transactionKey = pendingInfo.getTransactionKey();

        String chatId = data.get(Constants.KEY_CHAT_ID).getAsString();
        String roomId = data.get(Constants.KEY_ROOM_ID).getAsString(); // Sử dụng KEY_ROOM_ID
        String targetChatId = data.get(Constants.KEY_TARGET_CHATID).getAsString(); // Sử dụng KEY_TARGET_CHATID

        if (!sessionManager.validateSession(chatId, clientAddress, clientPort)) {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), Constants.STATUS_FAILURE_BOOL, "Invalid session.", transactionKey);
            return false;
        }

        String ownerChatId = roomDAO.getRoomOwner(roomId);
        if (ownerChatId == null || !ownerChatId.equals(chatId)) {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), Constants.STATUS_FAILURE_BOOL, "Only the room owner can kick users.", transactionKey);
            return false;
        }

        if (!roomDAO.isUserInRoom(roomId, targetChatId)) {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), Constants.STATUS_FAILURE_BOOL, "User is not in the room.", transactionKey);
            return false;
        }

        boolean success = roomDAO.removeParticipant(roomId, targetChatId);
        if (success) {
            JsonObject responseData = new JsonObject();
            responseData.addProperty(Constants.KEY_ROOM_ID, roomId); // Sử dụng KEY_ROOM_ID
            responseData.addProperty(Constants.KEY_TARGET_CHATID, targetChatId); // Sử dụng KEY_TARGET_CHATID
            udpSender.initiateServerToClientFlow(Constants.ACTION_KICK_USER_SUCCESS, responseData, clientAddress, clientPort, transactionKey);
            log.info("User {} was kicked from room {} by {}.", targetChatId, roomId, chatId);
            return true;
        } else {
            udpSender.sendAck(clientAddress, clientPort, pendingInfo.getTransactionId(), Constants.STATUS_FAILURE_BOOL, "Failed to kick user.", transactionKey);
            return false;
        }
    }
}