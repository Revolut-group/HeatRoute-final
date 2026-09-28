CREATE TABLE IF NOT EXISTS "jobs" (
 id VARCHAR(36) PRIMARY KEY,
 version BIGINT,
 state VARCHAR(20) NOT NULL,
 stage VARCHAR(20) NOT NULL,
 error_code VARCHAR(80),
 error_message VARCHAR(4000),
 received_bytes BIGINT NOT NULL,
 created_at VARCHAR(40) NOT NULL
);
