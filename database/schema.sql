-- =============================================================================
-- CS6650 Assignment 3 – PostgreSQL 15 Schema
-- =============================================================================

-- Create the database (run as superuser before executing this file):
--   CREATE DATABASE chatflow;
--   CREATE USER chatuser WITH PASSWORD 'chatpassword';
--   GRANT ALL PRIVILEGES ON DATABASE chatflow TO chatuser;
--   \c chatflow
--   GRANT ALL ON SCHEMA public TO chatuser;

-- =============================================================================
-- Core table
-- =============================================================================

CREATE TABLE IF NOT EXISTS messages (
    message_id    VARCHAR(36)   NOT NULL,
    room_id       VARCHAR(50)   NOT NULL,
    user_id       VARCHAR(50)   NOT NULL,
    username      VARCHAR(100)  NOT NULL,
    message       TEXT          NOT NULL,
    message_type  VARCHAR(10)   NOT NULL,          -- TEXT | JOIN | LEAVE
    server_id     VARCHAR(50),
    client_ip     VARCHAR(50),
    created_at    TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_messages PRIMARY KEY (message_id)
);

-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------

-- Q1: Get messages for a room in time range
--     WHERE room_id = ? AND created_at BETWEEN ? AND ? ORDER BY created_at
CREATE INDEX IF NOT EXISTS idx_room_time
    ON messages (room_id, created_at DESC);

-- Q2 / Q3 / Q4: User-based queries
--     WHERE user_id = ? [AND created_at BETWEEN ? AND ?]
--     COUNT(DISTINCT user_id) WHERE created_at BETWEEN ? AND ?
--     GROUP BY room_id WHERE user_id = ?
CREATE INDEX IF NOT EXISTS idx_user_time
    ON messages (user_id, created_at DESC);

-- Q3 optimisation: pure time-window scans with no user filter
CREATE INDEX IF NOT EXISTS idx_time
    ON messages (created_at DESC);

-- =============================================================================
-- Materialized views for analytics (refreshed after each test run)
-- Each view carries a UNIQUE index so REFRESH CONCURRENTLY can be used,
-- which avoids locking reads during refresh.
-- =============================================================================

-- A1: Messages per minute
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_msg_per_minute AS
SELECT
    date_trunc('minute', created_at) AS bucket,
    COUNT(*)                          AS message_count
FROM messages
GROUP BY 1;

CREATE UNIQUE INDEX IF NOT EXISTS uidx_mv_msg_per_minute
    ON mv_msg_per_minute (bucket);

-- A1 (seconds granularity – for high-rate analysis)
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_msg_per_second AS
SELECT
    date_trunc('second', created_at) AS bucket,
    COUNT(*)                          AS message_count
FROM messages
GROUP BY 1;

CREATE UNIQUE INDEX IF NOT EXISTS uidx_mv_msg_per_second
    ON mv_msg_per_second (bucket);

-- A2: Top active users (all message types count toward activity)
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_top_users AS
SELECT
    user_id,
    MAX(username)    AS username,
    COUNT(*)         AS message_count,
    MAX(created_at)  AS last_active
FROM messages
GROUP BY user_id;

CREATE UNIQUE INDEX IF NOT EXISTS uidx_mv_top_users
    ON mv_top_users (user_id);

-- A3: Top active rooms
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_top_rooms AS
SELECT
    room_id,
    COUNT(*)        AS message_count,
    MAX(created_at) AS last_activity
FROM messages
GROUP BY room_id;

CREATE UNIQUE INDEX IF NOT EXISTS uidx_mv_top_rooms
    ON mv_top_rooms (room_id);

-- A4: User participation patterns
--     Counts how many distinct rooms each user posted in;
--     then aggregates to show the distribution (how many users visited N rooms).
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_user_participation AS
SELECT
    rooms_visited,
    COUNT(*) AS user_count
FROM (
    SELECT user_id, COUNT(DISTINCT room_id) AS rooms_visited
    FROM messages
    GROUP BY user_id
) sub
GROUP BY rooms_visited;

CREATE UNIQUE INDEX IF NOT EXISTS uidx_mv_user_participation
    ON mv_user_participation (rooms_visited);

-- =============================================================================
-- Part 2: Dead Letter Queue
-- Messages that fail all DatabaseWriter retry attempts are stored here with
-- the error reason for later inspection and manual replay.
-- =============================================================================

CREATE TABLE IF NOT EXISTS messages_dlq (
    id            BIGSERIAL     PRIMARY KEY,
    message_id    VARCHAR(36),
    room_id       VARCHAR(50),
    user_id       VARCHAR(50),
    username      VARCHAR(100),
    message       TEXT,
    message_type  VARCHAR(10),
    server_id     VARCHAR(50),
    client_ip     VARCHAR(50),
    created_at    TIMESTAMPTZ,
    error_reason  VARCHAR(500),
    failed_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dlq_failed_at ON messages_dlq (failed_at DESC);
