CREATE TABLE im_conversation (
 id VARCHAR(80) PRIMARY KEY,household_id BIGINT NOT NULL,kind VARCHAR(16) NOT NULL,
 user_a BIGINT,user_b BIGINT,title VARCHAR(120) NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE im_message (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, conversation_id VARCHAR(80) NOT NULL,household_id BIGINT NOT NULL,
 sender_id BIGINT NOT NULL,client_id VARCHAR(36) NOT NULL,kind VARCHAR(16) NOT NULL,body VARCHAR(4000) NOT NULL,
 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uk_message_client (sender_id,client_id),
 FOREIGN KEY(conversation_id) REFERENCES im_conversation(id), INDEX idx_message_conversation(conversation_id,id)
);
CREATE TABLE im_message_read (
 conversation_id VARCHAR(80) NOT NULL,user_id BIGINT NOT NULL,last_message_id BIGINT NOT NULL,
 PRIMARY KEY(conversation_id,user_id)
);
