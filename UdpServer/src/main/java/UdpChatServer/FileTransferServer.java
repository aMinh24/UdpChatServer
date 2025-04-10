package UdpChatServer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import UdpChatServer.db.FileDAO;
import UdpChatServer.db.MessageDAO;
import UdpChatServer.db.RoomDAO;
import UdpChatServer.db.UserDAO;
import UdpChatServer.handler.file.FileSendDataHandler;
import UdpChatServer.handler.file.FileSendFinHandler;
import UdpChatServer.handler.file.FileSendInitHandler;
import UdpChatServer.model.Constants;

public class FileTransferServer {

    private static final Logger log = LoggerFactory.getLogger(FileTransferServer.class);

    private final DatagramSocket socket;
    private final ExecutorService executor;
    private volatile boolean running = true;
    private final MessageDAO messageDAO;
    private final UserDAO userDAO;
    private final RoomDAO roomDAO;
    private final FileDAO fileDAO;
    private FileSendDataHandler fileSendDataHandler;
    //private FileDownloadHandler fileDownloadHandler;
    private FileSendFinHandler fileSendFinHandler;
    private FileSendInitHandler fileSendInitHandler;
    //private ListRequestHandler listRequestHandler;

    public FileTransferServer(Properties config, MessageDAO messageDAO, UserDAO userDAO, RoomDAO roomDAO, FileDAO fileDAO) throws SocketException {
        int port = Integer.parseInt(config.getProperty("file.server.port", String.valueOf(Constants.FILE_TRANSFER_SERVER_PORT)));
        String storageDir = config.getProperty("file.storage.dir", "server_storage");

        this.messageDAO = messageDAO;
        this.userDAO = userDAO;
        this.roomDAO = roomDAO;
        this.fileDAO = fileDAO;

        socket = new DatagramSocket(port);
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
            byte[] receivedData = packet.getData();
            int receivedLength = packet.getLength();
            String jsonString = new String(receivedData, 0, receivedLength, StandardCharsets.UTF_8);
            JsonObject jsonPacket = JsonParser.parseString(jsonString).getAsJsonObject();
            String action = jsonPacket.get(Constants.KEY_ACTION).getAsString();

            InetAddress clientAddress = packet.getAddress();
            int clientPort = packet.getPort();

            switch (action) {
                case Constants.ACTION_FILE_SEND_INIT:
                    fileSendInitHandler = new FileSendInitHandler(messageDAO, userDAO, roomDAO, fileDAO, socket);
                    fileSendInitHandler.handle(jsonPacket, clientAddress, clientPort);
                    break;
                case Constants.ACTION_FILE_SEND_DATA:
                    fileSendDataHandler = new FileSendDataHandler(messageDAO, userDAO, roomDAO, fileDAO, socket);
                    fileSendDataHandler.handle(jsonPacket, clientAddress, clientPort);
                    break;
                case Constants.ACTION_FILE_SEND_FIN:
                    fileSendFinHandler = new FileSendFinHandler(messageDAO, userDAO, roomDAO, fileDAO, socket);
                    fileSendFinHandler.handle(jsonPacket, clientAddress, clientPort);
                    break;
                case Constants.ACTION_FILE_LIST_REQ:
                    break;
                case Constants.ACTION_FILE_DOWN_REQ:
                    break;
                case Constants.ACTION_FILE_DOWN_META:
                    break;
                case Constants.ACTION_FILE_DOWN_DATA:
                    break;
                case Constants.ACTION_FILE_DOWN_FIN:
                    break;
                // case Constants.ACTION_FILE_INIT:
                //     fileInitHandler = new FileInitHandler(sessionManager, userDAO, roomDAO, fileDAO, socket);
                //     fileInitHandler.handleSendInit(jsonPacket, clientAddress, clientPort);
                //     break;
                // case Constants.ACTION_FILE_DATA:
                //     fileDataHandler = new FileDataHandler(sessionManager, userDAO, roomDAO, fileDAO, socket);
                //     fileDataHandler.handleSendData(jsonPacket, clientAddress, clientPort);
                //     break;
                // case Constants.ACTION_FILE_FIN:
                //     fileFinHandler = new FileFinHandler(sessionManager, userDAO, roomDAO, fileDAO, socket);
                //     fileFinHandler.handleSendFin(jsonPacket, clientAddress, clientPort);
                //     break;
                // case Constants.ACTION_LIST_REQ:
                //     listRequestHandler = new ListRequestHandler(sessionManager, userDAO, roomDAO, fileDAO, socket);
                //     listRequestHandler.handleListRequest(jsonPacket, clientAddress, clientPort);
                //     break;
                // case Constants.ACTION_DOWN_REQ:
                //     fileDownloadHandler = new FileDownloadHandler(sessionManager, userDAO, roomDAO, fileDAO, socket);
                //     fileDownloadHandler.handleDownloadRequest(jsonPacket, clientAddress, clientPort);
                //     break;
                default:
                    log.warn("Unknown command received: {}", jsonString);
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
        System.out.println("File Transfer Server started with default config:------------ " + Constants.FILE_TRANSFER_SERVER_PORT + " " + Constants.STORAGE_DIR);

        try {
            FileTransferServer server = new FileTransferServer(config, new MessageDAO(), new UserDAO(), new RoomDAO(), new FileDAO());

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
