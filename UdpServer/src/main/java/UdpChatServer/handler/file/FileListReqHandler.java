package UdpChatServer.handler.file;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;

import com.google.gson.JsonObject;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.MessageDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.manager.ClientSessionManager;
import UdpChatServer.model.Constants;
import UdpChatServer.model.FileState;

public class FileListReqHandler extends FileTransferHandler {
    public FileListReqHandler(MessageDAO messageDAO, UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket, ClientSessionManager sessionManager) {
        super(sessionManager, messageDAO, userDAO, roomDAO, fileDAO, socket);
    } 

    public void handle(JsonObject jsonPacket, InetAddress clientAddress, int clientPort) {
        JsonObject dataJson = jsonPacket.getAsJsonObject(Constants.KEY_DATA);
        String roomId = dataJson.get("room_id").getAsString();

        System.out.println("Received LIST request from client: " + roomId);
        List<FileState> files = fileDAO.getFilesByRoom(roomId);

        StringBuilder fileListStr = new StringBuilder();
        if (files.isEmpty()) {
            JsonObject responseJsonPacket = createJsonPacket(Constants.ACTION_FILE_LIST_REQ, Constants.STATUS_ERROR, "No files available.", null);
            sendPacket(responseJsonPacket, clientAddress, clientPort);
            return;
        } else {
            for (int i = 0; i < files.size(); i++) {
                fileListStr.append(files.get(i).getFilePath() + ", ");
                // fileListStr.append(" (").append(files.get(i).getFileSize()).append(" bytes)");
                // if (i < files.size() - 1) {
                //     fileListStr.append(", ");
                // }
            }
        }

        JsonObject dataResponeJson = new JsonObject();
        dataResponeJson.addProperty("file_list", fileListStr.toString());
        JsonObject responseJsonPacket = createJsonPacket(Constants.ACTION_FILE_LIST_REQ, Constants.STATUS_SUCCESS, roomId, dataResponeJson);
        sendPacket(responseJsonPacket, clientAddress, clientPort);
        System.out.println("Sent file list to " + roomId + ": " + (files.isEmpty() ? "None" : fileListStr.toString()));
    } 
}
