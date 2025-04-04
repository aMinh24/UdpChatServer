package UdpChatServer.manager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages chat rooms and their participants in memory in a thread-safe manner.
 * Note: This manager primarily handles the in-memory state of active rooms and users.
 * Persistence (creating/fetching rooms from DB) should be handled by RoomDAO.
 */
public class RoomManager {

    // Map: roomId -> Set<chatid> of participants currently in the room (in memory)
    // Use ConcurrentHashMap for the outer map and thread-safe sets for the inner values.
    private final Map<String, Set<String>> rooms = new ConcurrentHashMap<>();

    // Optional: Reference to RoomDAO if needed for persistence checks/operations directly here
    // private final RoomDAO roomDAO;
    // public RoomManager(RoomDAO roomDAO) { this.roomDAO = roomDAO; }

    /**
     * Generates a deterministic room ID based on a set of participant chat IDs.
     * Sorts the chat IDs to ensure the order doesn't affect the result.
     * Example: Users "alice", "bob" -> room ID "alice_bob"
     * Example: Users "bob", "alice" -> room ID "alice_bob"
     * For group chats with many users, a hash might be more suitable.
     *
     * @param chatids The set of participant chat IDs.
     * @return A unique, deterministic room ID string.
     */
    public static String generateRoomId(Set<String> chatids) {
        if (chatids == null || chatids.isEmpty()) {
            throw new IllegalArgumentException("Chat IDs cannot be null or empty to generate a room ID.");
        }
        // Use TreeSet to ensure sorted order for deterministic ID generation
        Set<String> sortedChatIds = new TreeSet<>(chatids);
        return sortedChatIds.stream().collect(Collectors.joining("_")); // Simple concatenation with '_'
        // Alternative: Hashing for potentially shorter/more uniform IDs
        // return HashingFunction.hash(sortedChatIds.stream().collect(Collectors.joining(",")));
    }


    /**
     * Creates a new room in memory with the given participants.
     * If the room already exists, it ensures all specified participants are in it.
     * Note: Actual room creation in the DB should be handled by RoomDAO.
     *
     * @param roomId The unique ID of the room.
     * @param initialParticipants The initial set of participants.
     * @return true if a new room was created in memory, false if it already existed.
     */
    public boolean createOrJoinRoom(String roomId, Set<String> initialParticipants) {
         // Create a thread-safe set for participants
        Set<String> participants = rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());
        boolean created = participants.isEmpty(); // Check if it was newly created by computeIfAbsent
        participants.addAll(initialParticipants); // Add all initial participants
        System.out.println("Room '" + roomId + "' accessed/created in memory. Participants: " + participants);
        return created;
    }

     /**
     * Adds a user to an existing room in memory.
     *
     * @param roomId The ID of the room.
     * @param chatid The ID of the user to add.
     * @return true if the user was added, false if the room doesn't exist or the user was already present.
     */
    public boolean addUserToRoom(String roomId, String chatid) {
        Set<String> participants = rooms.get(roomId);
        if (participants != null) {
            boolean added = participants.add(chatid);
            if(added) System.out.println("User '" + chatid + "' added to room '" + roomId + "' in memory.");
            return added;
        }
        System.err.println("Attempted to add user '" + chatid + "' to non-existent room '" + roomId + "' in memory.");
        return false; // Room doesn't exist in memory
    }

    /**
     * Removes a user from a room in memory.
     * If the room becomes empty after removal, the room itself might be removed from memory (optional).
     *
     * @param roomId The ID of the room.
     * @param chatid The ID of the user to remove.
     * @return true if the user was removed, false if the room or user wasn't found.
     */
    public boolean removeUserFromRoom(String roomId, String chatid) {
        Set<String> participants = rooms.get(roomId);
        if (participants != null) {
            boolean removed = participants.remove(chatid);
            if (removed) {
                System.out.println("User '" + chatid + "' removed from room '" + roomId + "' in memory.");
                // Optional: Remove the room from memory if it becomes empty
                // if (participants.isEmpty()) {
                //     rooms.remove(roomId);
                //     System.out.println("Room '" + roomId + "' removed from memory as it became empty.");
                // }
            }
            return removed;
        }
         System.err.println("Attempted to remove user '" + chatid + "' from non-existent room '" + roomId + "' in memory.");
        return false; // Room or user not found
    }

    /**
     * Gets the set of participants currently in a specific room (in memory).
     *
     * @param roomId The ID of the room.
     * @return An unmodifiable set of participant chat IDs, or an empty set if the room doesn't exist in memory.
     */
    public Set<String> getUsersInRoom(String roomId) {
        Set<String> participants = rooms.get(roomId);
        // Return an unmodifiable view or a copy to prevent external modification
        return (participants != null) ? Collections.unmodifiableSet(new HashSet<>(participants)) : Collections.emptySet();
    }

    /**
     * Checks if a specific user is currently listed as being in a specific room (in memory).
     *
     * @param roomId The ID of the room.
     * @param chatid The ID of the user.
     * @return true if the user is in the room's participant list in memory, false otherwise.
     */
    public boolean isUserInRoom(String roomId, String chatid) {
        Set<String> participants = rooms.get(roomId);
        return participants != null && participants.contains(chatid);
    }

     /**
     * Gets all room IDs currently managed in memory.
     *
     * @return A set of all active room IDs.
     */
    public Set<String> getAllRoomIds() {
        return Collections.unmodifiableSet(new HashSet<>(rooms.keySet()));
    }

    /**
     * Removes a room from memory completely.
     *
     * @param roomId The ID of the room to remove.
     * @return true if the room existed and was removed, false otherwise.
     */
    public boolean removeRoom(String roomId) {
        Set<String> removed = rooms.remove(roomId);
        if (removed != null) {
            System.out.println("Room '" + roomId + "' completely removed from memory.");
            return true;
        }
        System.err.println("Attempted to remove non-existent room '" + roomId + "' from memory.");
        return false;
    }
}
