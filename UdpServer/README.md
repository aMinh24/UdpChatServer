# UDP Chat Server Packet Flow

This document outlines the basic UDP packet flow for key actions in the chat application, including the handshake mechanism (Character Count -> Confirm Count -> Ack) used for reliable delivery.

**Key:**

*   `(C)`: Client
*   `(S)`: Server
*   `[Fixed Key]`: Encrypted using the predefined fixed key.
*   `[Session Key]`: Encrypted using the user's unique session key established after login.
*   `C2S Flow`: Client-to-Server handshake initiated by the server sending `character_count`.
*   `S2C Flow`: Server-to-Client handshake initiated by the server sending the initial message (e.g., `receive_message`).

## 1. Login Flow (Client -> Server)

1.  **(C) -> (S): `login` Request** [Fixed Key]
    *   Contains `chatid` and `password`.
2.  **(S) -> (C): `character_count` Response** [Fixed Key] (Start C2S Flow)
    *   Server calculates character frequencies of the received `login` JSON.
    *   Contains `transaction_id`, `letter_frequencies`, `original_action` ("login").
3.  **(C) -> (S): `confirm_count` Request** [Fixed Key]
    *   Client calculates frequencies of the decrypted `login` JSON received implicitly (it doesn't receive the original JSON back, but calculates based on its sent data).
    *   Compares its frequencies with the server's.
    *   Contains `transaction_id`, `confirm` (true/false).
4.  **(S) -> (C): `ack` Response** [Fixed Key]
    *   Server verifies `confirm_count`. If true, it validates credentials.
    *   Contains `transaction_id`, `status` ("success" or "error"), `message`.
    *   **On Success:** Also contains `chatid` and the newly generated `session_key`.
    *   Client stores the `session_key` for future requests.

## 2. Send Message Flow (Client -> Server -> Other Clients)

1.  **(C) -> (S): `send_message` Request** [Session Key]
    *   Contains `chatid`, `room_id`, `content`.
2.  **(S) -> (C): `character_count` Response** [Session Key] (Start C2S Flow for sender)
    *   Server calculates frequencies of the received `send_message` JSON.
    *   Contains `transaction_id`, `letter_frequencies`, `original_action` ("send_message").
3.  **(C) -> (S): `confirm_count` Request** [Session Key]
    *   Client calculates frequencies and compares.
    *   Contains `transaction_id`, `confirm` (true/false).
4.  **(S) -> (C): `ack` Response** [Session Key] (To original sender)
    *   Server verifies `confirm_count`. If true, it saves the message to the database and identifies online recipients.
    *   Contains `transaction_id`, `status` ("success" or "error"), `message`.
5.  **(S) -> (Recipient C): `receive_message` Request** [Recipient's Session Key] (Start S2C Flow for each recipient)
    *   Server initiates a separate flow to forward the message to each online recipient *other than the sender*.
    *   See "Receive Message Flow" below.

## 3. Receive Message Flow (Server -> Client)

This flow is initiated by the server, typically when forwarding a message sent by another user (Step 5 in Send Message Flow) or sending results like `rooms_list` or `messages_list`.

1.  **(S) -> (C): Initial Action Request** (e.g., `receive_message`, `rooms_list`, `messages_list`) [Session Key] (Start S2C Flow)
    *   Contains the relevant data (e.g., sender, content, timestamp for `receive_message`).
    *   Crucially, the `data` payload includes a server-generated `transaction_id`.
2.  **(C) -> (S): `character_count` Response** [Session Key]
    *   Client receives the initial action, calculates character frequencies of the decrypted JSON.
    *   Contains `transaction_id` (from the received packet), `letter_frequencies`.
3.  **(S) -> (C): `confirm_count` Request** [Session Key]
    *   Server compares its calculated frequencies (from the JSON it sent) with the client's.
    *   Contains `transaction_id`, `confirm` (true/false).
4.  **(C) -> (S): `ack` Response** [Session Key]
    *   Client verifies `confirm_count`. If true, it processes the original action (e.g., displays the message).
    *   Contains `transaction_id`, `status` ("success" or "error"), `message`.
    *   Server receives the ACK, completing the transaction for this specific client.
