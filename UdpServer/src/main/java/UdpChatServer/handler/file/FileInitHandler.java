package UdpChatServer.handler.file;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Pattern;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;

public class FileInitHandler extends SendFileHandler {
    public FileInitHandler(UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket) {
        super(userDAO, roomDAO, fileDAO, socket);
    } 

    public void handleSendInit(String payload, InetAddress clientAddress, int clientPort) {
        try {
            String[] parts = payload.split(Pattern.quote(PACKET_DELIMITER));
            if (parts.length != 5) {
                System.err.println("Invalid SEND_INIT format: " + payload);
                return;
            }
            String sender = parts[0];
            String receiver = parts[1];
            String filename = parts[2];
            long fileSize = Long.parseLong(parts[3]);
            int totalPackets = Integer.parseInt(parts[4]);

            String fileIdentifier = sender + "_" + receiver + "_" + filename;

            // Check if the file is already being received
            if (fileSize <= 0 || totalPackets <= 0) {
                sendPacket("SEND_INIT_RESP" + PACKET_DELIMITER + "ERROR" + PACKET_DELIMITER + "Invalid file size or packet count", clientAddress, clientPort);
                return;
            }

            incomingFileChunks.put(fileIdentifier, new ConcurrentSkipListMap<>()); // Prepare to receive chunks

            System.out.println("Receiving file '" + filename + "' from " + sender + " for " + receiver + " (" + fileSize + " bytes, " + totalPackets + " packets)");
            // Send an ACK back to the sender
            sendPacket("SEND_INIT_RESP" + PACKET_DELIMITER + "OK" + PACKET_DELIMITER + "Ready to receive file", clientAddress, clientPort);
        } catch (NumberFormatException e) {
            System.err.println("Error parsing SEND_INIT numbers: " + payload + " - " + e.getMessage());
            sendPacket("SEND_INIT_RESP" + PACKET_DELIMITER + "OK" + PACKET_DELIMITER + "ERROR", clientAddress, clientPort);
        } catch (Exception e) {
            System.err.println("Error in handleSendInit: " + e.getMessage());
            sendPacket("SEND_INIT_RESP" + PACKET_DELIMITER + "OK" + PACKET_DELIMITER + "ERROR", clientAddress, clientPort);
        }
    }
}
