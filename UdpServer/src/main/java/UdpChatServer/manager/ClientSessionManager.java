package UdpChatServer.manager;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import UdpChatServer.model.Constants;
import UdpChatServer.model.PendingMessageInfo;
import UdpChatServer.model.SessionInfo;

/**
 * Manages active client sessions and pending multi-step message transactions in a thread-safe manner.
 */
public class ClientSessionManager {
    private static final Logger log = LoggerFactory.getLogger(ClientSessionManager.class);

    // Primary map: chatid -> SessionInfo
    private final Map<String, SessionInfo> sessionsByChatId = new ConcurrentHashMap<>();
    // Secondary map: "ip:port" -> chatid for quick lookups
    private final Map<String, String> sessionsByAddress = new ConcurrentHashMap<>();

    // Store pending transactions: transactionId -> PendingMessageInfo
    private final Map<String, PendingMessageInfo> pendingTransactions = new ConcurrentHashMap<>();
    private final AtomicLong transactionCounter = new AtomicLong(0); // Simple counter for transaction IDs

    private String getAddressKey(InetAddress ipAddress, int port) {
        return ipAddress.getHostAddress() + ":" + port;
    }

    /**
     * Generates a unique transaction ID.
     * Can be customized (e.g., using UUID).
     */
    public String generateTransactionId(String prefix) {
        // Simple counter-based ID, potentially prefixed
        return prefix + "_" + transactionCounter.incrementAndGet();
        // Alternative: UUID-based
        // return prefix + "_" + UUID.randomUUID().toString();
    }

    /**
     * Adds a new client session or updates an existing one.
     */
    public void addSession(String chatid, InetAddress ipAddress, int port, String key) {
        if (chatid == null || ipAddress == null || key == null) {
            log.error("Attempted to add session with null values for chatid: {}", chatid);
            return;
        }

        // Create new session info
        SessionInfo sessionInfo = new SessionInfo(ipAddress, port, key, chatid);

        // Remove old address mapping if exists
        SessionInfo oldSession = sessionsByChatId.get(chatid);
        if (oldSession != null) {
            sessionsByAddress.remove(getAddressKey(oldSession.getIpAddress(), oldSession.getPort()));
            log.info("Removed old address mapping for updated session: {}", chatid);
        }

        // Add new mappings
        sessionsByChatId.put(chatid, sessionInfo);
        sessionsByAddress.put(getAddressKey(ipAddress, port), chatid);

        log.info("Session added/updated for user: {} -> {}", chatid, sessionInfo);
    }

    /**
     * Gets the session key for a specific client by their chatid.
     */
    public String getClientKey(String chatid) {
        SessionInfo session = getSessionInfo(chatid);
        return (session != null) ? session.getKey() : null;
    }

    /**
     * Gets the session key for a client based on their address and port.
     */
    public String getClientKeyByAddress(InetAddress ipAddress, int port) {
        String chatid = sessionsByAddress.get(getAddressKey(ipAddress, port));
        if (chatid != null) {
            SessionInfo session = sessionsByChatId.get(chatid);
            if (session != null) {
                session.updateLastActivityTime(); // Update activity on key lookup
                return session.getKey();
            }
        }
        return null;
    }

    /**
     * Gets session information for a given client by chatid.
     * Updates the last activity time for the session.
     */
    public SessionInfo getSessionInfo(String chatid) {
        SessionInfo sessionInfo = sessionsByChatId.get(chatid);
        if (sessionInfo != null) {
            sessionInfo.updateLastActivityTime();
        }
        return sessionInfo;
    }

    /**
     * Gets session information for a client based on their address and port.
     */
    public SessionInfo getSessionInfoByAddress(InetAddress ipAddress, int port) {
        String chatid = sessionsByAddress.get(getAddressKey(ipAddress, port));
        return chatid != null ? getSessionInfo(chatid) : null; // getSessionInfo updates activity time
    }

    /**
     * Checks if a client is currently online (has an active session).
     */
    public boolean isOnline(String chatid) {
        return sessionsByChatId.containsKey(chatid);
    }

    /**
     * Gets the network address (IP and Port) for a specific client.
     */
    public SessionInfo getClientAddressInfo(String chatid) {
        return getSessionInfo(chatid);
    }

    /**
     * Removes a client session (e.g., on logout or timeout).
     * Also removes any pending transactions associated with this session.
     */
    public void removeSession(String chatid) {
        SessionInfo removedSession = sessionsByChatId.remove(chatid);
        if (removedSession != null) {
            sessionsByAddress.remove(getAddressKey(removedSession.getIpAddress(), removedSession.getPort()));
            // Remove pending transactions initiated *by* or *to* this client
            pendingTransactions.entrySet().removeIf(entry -> {
                PendingMessageInfo info = entry.getValue();
                boolean related = (info.getDirection() == PendingMessageInfo.Direction.CLIENT_TO_SERVER && info.getOriginalMessageJson().get(Constants.KEY_CHAT_ID).getAsString().equals(chatid)) ||
                                  (info.getDirection() == PendingMessageInfo.Direction.SERVER_TO_CLIENT && info.getPartnerAddress().equals(removedSession.getIpAddress()) && info.getPartnerPort() == removedSession.getPort());
                if (related) {
                    log.info("Removing pending transaction {} due to session removal for {}", entry.getKey(), chatid);
                }
                return related;
            });
            log.info("Session removed for user: {}", chatid);
        }
    }

    /**
     * Removes a client session based on their address and port.
     */
    public void removeSessionByAddress(InetAddress ipAddress, int port) {
        String chatid = sessionsByAddress.get(getAddressKey(ipAddress, port)); // Get chatid before removing address mapping
        if (chatid != null) {
            removeSession(chatid); // Call the main removal logic
        } else {
             log.info("Attempted to remove session by address {}:{}, but no mapping found.", ipAddress.getHostAddress(), port);
        }
    }

    /**
     * Validates that a session exists and matches the provided chatid and address.
     */
    public boolean validateSession(String chatid, InetAddress ipAddress, int port) {
        SessionInfo session = sessionsByChatId.get(chatid);
        if (session == null) return false;
        // Also check if the address/port matches the stored session for this chatid
        return session.getIpAddress().equals(ipAddress) && session.getPort() == port;
    }

    /**
     * Stores a pending transaction.
     */
    public void storePendingTransaction(PendingMessageInfo pendingInfo) {
        if (pendingInfo == null || pendingInfo.getTransactionId() == null) {
            log.error("Attempted to store null or invalid pending transaction info.");
            return;
        }
        pendingTransactions.put(pendingInfo.getTransactionId(), pendingInfo);
        log.info("Pending transaction stored: {}", pendingInfo);
    }

    /**
     * Retrieves and removes a pending transaction by its ID.
     */
    public PendingMessageInfo retrieveAndRemovePendingTransaction(String transactionId) {
        if (transactionId == null) return null;
        PendingMessageInfo info = pendingTransactions.remove(transactionId);
        if (info != null) {
            log.info("Retrieved and removed pending transaction: {}", transactionId);
        } else {
             log.warn("Attempted to retrieve non-existent pending transaction: {}", transactionId);
        }
        return info;
    }

     /**
     * Retrieves a pending transaction by its ID without removing it.
     */
    public PendingMessageInfo getPendingTransaction(String transactionId) {
        if (transactionId == null) return null;
        return pendingTransactions.get(transactionId);
    }


    /**
     * Gets the IDs of all currently pending transactions.
     */
    public String[] getPendingTransactionIds() {
        return pendingTransactions.keySet().toArray(new String[0]);
    }

    /**
     * Periodic cleanup of inactive sessions and timed-out pending transactions.
     */
    public void cleanupInactiveSessionsAndTransactions(long maxSessionInactiveIntervalMillis, long maxTransactionTimeoutMillis) {
        long now = System.currentTimeMillis();

        // Cleanup inactive sessions
        sessionsByChatId.entrySet().removeIf(entry -> {
            SessionInfo session = entry.getValue();
            boolean inactive = (now - session.getLastActivityTime()) > maxSessionInactiveIntervalMillis;
            if (inactive) {
                // Remove associated address mapping immediately
                sessionsByAddress.remove(getAddressKey(session.getIpAddress(), session.getPort()));
                log.info("Removing inactive session for user: {} (Last activity: {} ms ago)",
                         entry.getKey(), now - session.getLastActivityTime());
                // Associated pending transactions will be cleaned up in the next step or when session is explicitly removed
            }
            return inactive;
        });

        // Cleanup timed-out pending transactions
        pendingTransactions.entrySet().removeIf(entry -> {
            PendingMessageInfo info = entry.getValue();
            boolean timedOut = (now - info.getLastUpdateTime()) > maxTransactionTimeoutMillis;
            if (timedOut) {
                log.warn("Removing timed-out pending transaction: {} (State: {}, Last update: {} ms ago)",
                         entry.getKey(), info.getCurrentState(), now - info.getLastUpdateTime());
                // Optionally notify the initiator if possible/needed (complex)
            }
            return timedOut;
        });
    }
}
