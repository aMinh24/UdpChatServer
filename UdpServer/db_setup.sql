-- Create the database if it doesn't exist
CREATE DATABASE IF NOT EXISTS udp_chat_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Use the created database
USE udp_chat_db;

-- Create the users table
CREATE TABLE IF NOT EXISTS users (
    chatid VARCHAR(50) PRIMARY KEY,
    password VARCHAR(255) NOT NULL, -- Store hashed passwords in a real application
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create the rooms table
CREATE TABLE IF NOT EXISTS rooms (
    room_id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,  -- Add a name field for the room
    owner VARCHAR(50) NOT NULL,  -- Add owner field to track room creator
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (owner) REFERENCES users(chatid) ON DELETE CASCADE
);

-- Create the room_participants table
CREATE TABLE IF NOT EXISTS room_participants (
    room_id VARCHAR(100),
    chatid VARCHAR(50),
    PRIMARY KEY (room_id, chatid),
    FOREIGN KEY (room_id) REFERENCES rooms(room_id) ON DELETE CASCADE,
    FOREIGN KEY (chatid) REFERENCES users(chatid) ON DELETE CASCADE
);

-- Create the messages table
CREATE TABLE IF NOT EXISTS messages (
    message_id INT AUTO_INCREMENT PRIMARY KEY,
    room_id VARCHAR(100) NOT NULL,
    sender_chatid VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (room_id) REFERENCES rooms(room_id) ON DELETE CASCADE,
    FOREIGN KEY (sender_chatid) REFERENCES users(chatid) ON DELETE CASCADE
);

-- Create the files table
CREATE TABLE IF NOT EXISTS files (
    file_id INT AUTO_INCREMENT PRIMARY KEY,
    room_id VARCHAR(100) NOT NULL,
    sender_chatid VARCHAR(50) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(255) NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Optional: Add some initial data for testing (example)
INSERT INTO users (chatid, password) VALUES ('user1', 'pass1');
INSERT INTO users (chatid, password) VALUES ('user2', 'pass2');
