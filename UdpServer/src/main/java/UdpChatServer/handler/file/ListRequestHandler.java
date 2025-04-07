// package UdpChatServer.handler.file;

// import java.net.DatagramSocket;
// import java.net.InetAddress;
// import java.util.Collections;
// import java.util.List;

// import com.google.gson.JsonObject;

// import UdpChatServer.db.FileDAO;
// import UdpChatServer.db.RoomDAO;
// import UdpChatServer.db.UserDAO;
// import UdpChatServer.manager.ClientSessionManager;
// import UdpChatServer.model.Constants;
// import UdpChatServer.model.FileMetaData;

// public class ListRequestHandler extends FileTransferHandler {
//     public ListRequestHandler(ClientSessionManager sessionManager, UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket) {
//         super(sessionManager, userDAO, roomDAO, fileDAO, socket);
//     } 

//     public void handleListRequest(JsonObject jsonPacket, InetAddress clientAddress, int clientPort) {
//         JsonObject dataJson = jsonPacket.getAsJsonObject(Constants.KEY_DATA);
//         String clientName = dataJson.get("client_name").getAsString();

//         System.out.println("Received LIST request from client: " + clientName);
//         List<FileMetaData> files = filesForClients.getOrDefault(clientName, Collections.emptyList());

//         StringBuilder fileListStr = new StringBuilder();
//         if (files.isEmpty()) {
//             fileListStr.append("No files available for you.");
//         } else {
//             for (int i = 0; i < files.size(); i++) {
//                 fileListStr.append(files.get(i).getFilename());
//                 fileListStr.append(" (").append(files.get(i).getFileSize()).append(" bytes)");
//                 if (i < files.size() - 1) {
//                     fileListStr.append(", ");
//                 }
//             }
//         }

//         JsonObject responseJson = jsonPacket;
//         responseJson.addProperty(Constants.KEY_MESSAGE, fileListStr.toString());
//         sendPacket(responseJson, clientAddress, clientPort);
//         System.out.println("Sent file list to " + clientName + ": " + (files.isEmpty() ? "None" : fileListStr.toString()));
//     } 
// }
