package UdpChatServer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import UdpChatServer.handler.SendFileHandler;
import UdpChatServer.model.Constants;

public class FileTransferServer {
    private static final Logger log = LoggerFactory.getLogger(FileTransferServer.class);
    
    private final DatagramSocket socket;
    private final SendFileHandler handler;
    private final ExecutorService executor;
    private volatile boolean running = true;

    public FileTransferServer(Properties config) throws SocketException {
        int port = Integer.parseInt(config.getProperty("file.server.port", String.valueOf(Constants.FILE_TRANSFER_SERVER_PORT)));
        String storageDir = config.getProperty("file.storage.dir", "server_storage");
        
        socket = new DatagramSocket(port);
        handler = new SendFileHandler(socket);
        executor = Executors.newCachedThreadPool();
        
        log.info("UDP File Transfer Server started on port {}", port);
        
        try {
            Files.createDirectories(Paths.get(storageDir));
            log.info("Server storage directory: {}", Paths.get(storageDir).toAbsolutePath());
        } catch (IOException e) {
            log.error("Error creating server storage directory: {}", e.getMessage());
            throw new RuntimeException("Cannot create storage directory", e);
        }
    }

    public void listen() {
        byte[] receiveBuffer = new byte[Constants.MAX_UDP_PACKET_SIZE];
        log.info("File Transfer Server listening for incoming packets...");

        while (running) {
            try {
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                socket.receive(receivePacket);
                executor.submit(() -> handlePacket(receivePacket));
            } catch (IOException e) {
                if (running) {
                    log.error("IOException during receive: {}", e.getMessage());
                }
            }
        }
    }

    public void stop() {
        running = false;
        log.info("Stopping File Transfer Server...");
        
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        
        log.info("File Transfer Server stopped.");
    }

    private void handlePacket(DatagramPacket packet) {
        try {
            String receivedData = new String(packet.getData(), 0, packet.getLength()).trim();
            String[] parts = receivedData.split(Pattern.quote(Constants.PACKET_DELIMITER), 2);
            
            if (parts.length < 1) {
                log.warn("Received invalid packet format from {}:{}", 
                    packet.getAddress(), packet.getPort());
                return;
            }

            String command = parts[0];
            String payload = (parts.length > 1) ? parts[1] : "";
            InetAddress clientAddress = packet.getAddress();
            int clientPort = packet.getPort();

            switch (command) {
                case Constants.CMD_SEND_INIT:
                    handler.handleSendInit(payload, clientAddress, clientPort);
                    break;
                case Constants.CMD_SEND_DATA:
                    handler.handleSendData(payload, packet.getData(), packet.getLength(), 
                        clientAddress, clientPort);
                    break;
                case Constants.CMD_SEND_FIN:
                    handler.handleSendFin(payload, clientAddress, clientPort);
                    break;
                case Constants.CMD_LIST_REQ:
                    handler.handleListRequest(payload, clientAddress, clientPort);
                    break;
                case Constants.CMD_DOWNLOAD_REQ:
                    handler.handleDownloadRequest(payload, clientAddress, clientPort);
                    break;
                default:
                    log.warn("Unknown command received: {}", command);
            }
        } catch (Exception e) {
            log.error("Error handling packet from {}:{} - {}", 
                packet.getAddress(), packet.getPort(), e.getMessage());
        }
    }

    public static void main(String[] args) {
        Properties config = new Properties();
        // Add default config values
        config.setProperty("file.server.port", String.valueOf(Constants.FILE_TRANSFER_SERVER_PORT));
        config.setProperty("file.storage.dir", "server_storage");
        
        try {
            FileTransferServer server = new FileTransferServer(config);
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutdown hook triggered for File Transfer Server...");
                server.stop();
            }));
            
            server.listen();
        } catch (SocketException e) {
            log.error("Failed to start server: Could not bind to port {}", 
                config.getProperty("file.server.port"));
            System.exit(1);
        }
    }
}
