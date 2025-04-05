package UdpChatServer.handler.file;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Pattern;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;

public class FileDataHandler extends SendFileHandler {
    public FileDataHandler(UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO, DatagramSocket socket) {
        super(userDAO, roomDAO, fileDAO, socket);
    }

    public void handleSendData(String metadataPayload, byte[] rawPacketData, int packetLength,
            InetAddress clientAddress, int clientPort) {
        try {
            String[] parts = metadataPayload.split(Pattern.quote(PACKET_DELIMITER));
            if (parts.length < 5) { // Check minimum parts before data
                System.err.println("Invalid SEND_DATA format (metadata): " + metadataPayload);
                return;
            }
            String sender = parts[0];
            String receiver = parts[1];
            String filename = parts[2];
            int sequenceNumber = Integer.parseInt(parts[3]);

            String fileIdentifier = sender + "_" + receiver + "_" + filename;

            // Calculate where data starts
            String metadataHeader = "SEND_DATA" + PACKET_DELIMITER + metadataPayload.substring(0, metadataPayload.lastIndexOf(PACKET_DELIMITER) + PACKET_DELIMITER.length());
            int metadataLength = metadataHeader.getBytes().length;

            if (packetLength <= metadataLength) {
                System.err.println("SEND_DATA packet has no data payload for seq " + sequenceNumber);
                return;
            }

            byte[] dataChunk = Arrays.copyOfRange(rawPacketData, metadataLength, packetLength);

            ConcurrentSkipListMap<Integer, byte[]> chunks = incomingFileChunks.get(fileIdentifier);
            if (chunks != null) {
                chunks.put(sequenceNumber, dataChunk);
                // System.out.println("DEBUG: Received chunk " + sequenceNumber + " for " + fileIdentifier); // Verbose debug
            } else {
                System.err.println("Received data chunk for unknown/uninitialized file transfer: " + fileIdentifier);
            }
        } catch (NumberFormatException e) {
            System.err.println("Error parsing SEND_DATA sequence number: " + metadataPayload + " - " + e.getMessage());
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println("Error processing SEND_DATA packet structure: " + metadataPayload + " - " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error in handleSendData: " + e.getMessage());
        }
    }
}
