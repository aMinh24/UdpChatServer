package UdpChatServer.handler.file;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.model.FileMetaData;

public class ListRequestHandler extends SendFileHandler {
    public ListRequestHandler(UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket) {
        super(userDAO, roomDAO, fileDAO, socket);
    } 

    public void handleListRequest(String clientName, InetAddress clientAddress, int clientPort) {
        System.out.println("Received LIST request from client: " + clientName);
        List<FileMetaData> files = filesForClients.getOrDefault(clientName, Collections.emptyList());

        StringBuilder fileListStr = new StringBuilder();
        if (files.isEmpty()) {
            fileListStr.append("No files available for you.");
        } else {
            for (int i = 0; i < files.size(); i++) {
                fileListStr.append(files.get(i).getFilename());
                fileListStr.append(" (").append(files.get(i).getFileSize()).append(" bytes)");
                if (i < files.size() - 1) {
                    fileListStr.append(", ");
                }
            }
        }

        String responsePayload = "LIST_RESP" + PACKET_DELIMITER + fileListStr.toString();
        sendPacket(responsePayload, clientAddress, clientPort);
        System.out.println("Sent file list to " + clientName + ": " + (files.isEmpty() ? "None" : fileListStr.toString()));
    } 
}
