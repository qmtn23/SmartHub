-- Apply after task-memory.sql and before enabling CUSTOMER_PROFILE_ENABLED.
CREATE TABLE IF NOT EXISTS tb_customer_user_profile (
  user_id BIGINT NOT NULL,
  scope_type VARCHAR(16) NOT NULL DEFAULT 'PLATFORM',
  scope_id BIGINT NOT NULL DEFAULT 0,
  profile_content LONGTEXT NOT NULL,
  schema_version INT NOT NULL DEFAULT 1,
  version BIGINT NOT NULL DEFAULT 0,
  update_time DATETIME NOT NULL,
  PRIMARY KEY(user_id, scope_type, scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Source IDs come from the committed task-memory batch, never from a model response.
CREATE TABLE IF NOT EXISTS tb_customer_profile_job (
  user_id BIGINT NOT NULL,
  memory_version BIGINT NOT NULL,
  source_message_ids TEXT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NOT NULL,
  lease_id VARCHAR(32) NULL,
  lease_until DATETIME NULL,
  error_code VARCHAR(64) NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY(user_id, memory_version),
  INDEX idx_profile_work(status, next_attempt_at, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
