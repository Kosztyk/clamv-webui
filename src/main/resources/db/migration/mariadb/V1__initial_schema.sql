-- Phase 2 / V1: inventoried JPA persistent schema; new databases only.

-- Existing databases are NOT baselined automatically.

CREATE TABLE clamd_endpoints (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    platform VARCHAR(32) NOT NULL,
    enabled BIT NOT NULL,
    CONSTRAINT uk_clamd_endpoint_name UNIQUE (name)
);

CREATE TABLE app_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    enabled BIT NOT NULL,
    created_at DATETIME(6),
    last_login_at DATETIME(6),
    CONSTRAINT uk_app_users_username UNIQUE (username)
);

CREATE TABLE app_user_roles (
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    CONSTRAINT fk_app_user_roles_user FOREIGN KEY (user_id) REFERENCES app_users(id)
);

CREATE TABLE api_credentials (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    label VARCHAR(100) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    prefix VARCHAR(16) NOT NULL,
    scopes VARCHAR(512) NOT NULL,
    endpoint_ids VARCHAR(1024),
    path_roots VARCHAR(2048),
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6),
    revoked_at DATETIME(6),
    last_used_at DATETIME(6),
    CONSTRAINT uk_api_credentials_hash UNIQUE (token_hash)
);

CREATE TABLE audit_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    at DATETIME(6),
    username VARCHAR(64),
    action VARCHAR(64) NOT NULL,
    details VARCHAR(1024),
    outcome VARCHAR(64),
    job_id VARCHAR(64),
    transport VARCHAR(32),
    endpoint_id BIGINT,
    ip VARCHAR(64),
    user_agent VARCHAR(255)
);

CREATE TABLE scan_jobs (
    id VARCHAR(64) PRIMARY KEY,
    type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    verdict VARCHAR(16),
    target VARCHAR(2048) NOT NULL,
    stored_path VARCHAR(1024),
    sha256 VARCHAR(128),
    size_bytes BIGINT,
    endpoint_id BIGINT,
    submitted_by VARCHAR(64),
    submitted_transport VARCHAR(32),
    submitted_at DATETIME(6),
    started_at DATETIME(6),
    finished_at DATETIME(6),
    found_viruses_json TEXT,
    error_message TEXT,
    quarantine_path VARCHAR(2048),
    CONSTRAINT fk_scan_jobs_endpoint FOREIGN KEY (endpoint_id) REFERENCES clamd_endpoints(id)
);

CREATE TABLE watched_directories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    path VARCHAR(2048) NOT NULL,
    endpoint_id BIGINT,
    enabled BIT NOT NULL,
    CONSTRAINT fk_watch_endpoint FOREIGN KEY (endpoint_id) REFERENCES clamd_endpoints(id)
);

CREATE TABLE processed_files (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    path VARCHAR(4096) NOT NULL,
    last_modified BIGINT NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(128),
    processed_at DATETIME(6)
);

ALTER TABLE processed_files ADD COLUMN path_digest BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(path, 256))) STORED;

CREATE UNIQUE INDEX idx_processed_path_digest ON processed_files (path_digest);

CREATE INDEX idx_scan_jobs_submitted_at ON scan_jobs(submitted_at);

CREATE INDEX idx_scan_jobs_status ON scan_jobs(status);

CREATE INDEX idx_audit_at ON audit_events(at);

CREATE INDEX idx_audit_job_id ON audit_events(job_id);

CREATE INDEX idx_watch_endpoint ON watched_directories(endpoint_id);
