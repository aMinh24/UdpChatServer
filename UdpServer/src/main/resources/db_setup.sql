CREATE DATABASE IF NOT EXISTS udp_chat_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE udp_chat_db;

CREATE TABLE IF NOT EXISTS users (
    chatid VARCHAR(50) PRIMARY KEY,
    password VARCHAR(255) NOT NULL, -- Store hashed passwords in a real application
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rooms (
    room_id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,  -- Add a name field for the room
    owner VARCHAR(50) NOT NULL,  -- Add owner field to track room creator
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (owner) REFERENCES users(chatid) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS room_participants (
    room_id VARCHAR(100),
    chatid VARCHAR(50),
    PRIMARY KEY (room_id, chatid),
    FOREIGN KEY (room_id) REFERENCES rooms(room_id) ON DELETE CASCADE,
    FOREIGN KEY (chatid) REFERENCES users(chatid) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS messages (
    message_id INT AUTO_INCREMENT PRIMARY KEY,
    room_id VARCHAR(100) NOT NULL,
    sender_chatid VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (room_id) REFERENCES rooms(room_id) ON DELETE CASCADE,
    FOREIGN KEY (sender_chatid) REFERENCES users(chatid) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS files (
    file_id INT AUTO_INCREMENT PRIMARY KEY,
    room_id VARCHAR(100) NOT NULL,
    sender_chatid VARCHAR(50) NOT NULL,
    file_path VARCHAR(255) NOT NULL,
    file_type VARCHAR(255) NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO users (chatid, password) VALUES ('gemini_bot', 'password_ai');
