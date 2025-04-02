package UdpChatClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketException;
import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UdpChatClient {
    private static final Logger log = LoggerFactory.getLogger(UdpChatClient.class);
    public static final String DEFAULT_SERVER_HOST = "localhost"; // Keep default host here

    private final ClientState clientState;
    private final MessageProcessor messageProcessor;
    private final HandshakeManager handshakeManager;
    private final CommandProcessor commandProcessor;
    private final MessageListener messageListener;
    private Thread listenerThread;

    public UdpChatClient(String serverHost, int serverPort) throws SocketException, UnknownHostException {
        log.info("Initializing UDP Chat Client for server {}:{}", serverHost, serverPort);
        this.clientState = new ClientState(serverHost, serverPort);
        // Order matters: MessageProcessor needs ClientState
        this.messageProcessor = new MessageProcessor(clientState);
        // HandshakeManager needs ClientState and MessageProcessor
        this.handshakeManager = new HandshakeManager(clientState, messageProcessor);
        // CommandProcessor needs ClientState and HandshakeManager
        this.commandProcessor = new CommandProcessor(clientState, handshakeManager);
        // MessageListener needs ClientState and HandshakeManager
        this.messageListener = new MessageListener(clientState, handshakeManager);
        log.info("Client components initialized.");
    }

    public void start() {
        // Start the listener thread
        listenerThread = new Thread(messageListener, "ClientListenerThread");
        listenerThread.setDaemon(true); // Allow JVM to exit if only daemon threads are running
        listenerThread.start();
        log.info("Message listener thread started.");

        // Show initial help message
        commandProcessor.showHelp();

        // Main input loop
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            // Loop while the client state is running and we can read input
            while (clientState.isRunning() && (line = reader.readLine()) != null) {
                commandProcessor.processCommand(line);
            }
        } catch (IOException e) {
            log.error("Error reading user input: {}", e.getMessage(), e);
            // Consider stopping the client if input fails critically
            clientState.setRunning(false);
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        log.info("Starting client cleanup...");
        // Ensure running state is false to signal listener thread
        clientState.setRunning(false);

        // Close the socket (this will interrupt the listener's blocking receive call)
        clientState.closeSocket();

        // Optionally wait for the listener thread to finish
        if (listenerThread != null && listenerThread.isAlive()) {
            try {
                log.debug("Waiting for listener thread to join...");
                listenerThread.join(1000); // Wait max 1 second
                if (listenerThread.isAlive()) {
                    log.warn("Listener thread did not exit gracefully, interrupting.");
                    listenerThread.interrupt(); // Force interruption if needed
                } else {
                     log.debug("Listener thread joined successfully.");
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for listener thread to join.");
                Thread.currentThread().interrupt();
            }
        }

        // Shutdown handshake manager (clears pending requests)
        handshakeManager.shutdown();

        log.info("Client cleanup finished.");
        System.out.println("\nClient connection closed.");
    }

    public static void main(String[] args) {
        String host = DEFAULT_SERVER_HOST;
        int port = Constants.DEFAULT_SERVER_PORT;

        if (args.length >= 1) {
            host = args[0];
        }
        if (args.length >= 2) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number provided: " + args[1] + ". Using default port " + port + ".");
                log.warn("Invalid port argument '{}', using default {}", args[1], port);
            }
        }

        try {
            UdpChatClient client = new UdpChatClient(host, port);
            client.start();
        } catch (SocketException e) {
            System.err.println("Network error: Could not create socket. " + e.getMessage());
            log.error("SocketException during client initialization:", e);
        } catch (UnknownHostException e) {
            System.err.println("Network error: Could not resolve server host '" + host + "'. " + e.getMessage());
            log.error("UnknownHostException during client initialization:", e);
        } catch (Exception e) {
            System.err.println("An unexpected error occurred: " + e.getMessage());
            log.error("Unexpected error during client startup:", e);
        }
    }
}
