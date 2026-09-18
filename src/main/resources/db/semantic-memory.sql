-- Apply after task-memory.sql and user-profile.sql, before enabling semantic memory.
CREATE TABLE IF NOT EXISTS tb_customer_memory_item (
  memory_id VARCHAR(64) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  scope_type VARCHAR(16) NOT NULL DEFAULT 'PLATFORM',
  scope_id BIGINT NOT NULL DEFAULT 0,
  task_id VARCHAR(64) NOT NULL,
  memory_type VARCHAR(16) NOT NULL,
  content LONGTEXT NOT NULL,
  source_refs LONGTEXT NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  version BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  observed_at DATETIME NOT NULL,
  valid_until DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  INDEX idx_memory_owner(user_id,scope_type,scope_id,status,valid_until),
  INDEX idx_memory_expiry(status,valid_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Immutable versioned jobs: delayed workers cannot overwrite a newer vector.
CREATE TABLE IF NOT EXISTS tb_customer_memory_backfill (
  user_id BIGINT NOT NULL PRIMARY KEY,
  memory_version BIGINT NOT NULL,
  processed_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_customer_memory_index_job (
  memory_id VARCHAR(64) NOT NULL,
  memory_version BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  lease_id VARCHAR(32) NULL,
  lease_until DATETIME NULL,
  next_attempt_at DATETIME NOT NULL,
  error_code VARCHAR(64) NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY(memory_id,memory_version),
  INDEX idx_memory_index_work(status,next_attempt_at,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Model/tool events are audit data, never accepted as confirmed user preferences.
CREATE TABLE IF NOT EXISTS tb_customer_profile_receipt (
  message_id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  processed_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_customer_memory_event (
  event_id VARCHAR(64) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  chat_id BIGINT NOT NULL,
  user_message_id BIGINT NOT NULL,
  run_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  payload LONGTEXT NOT NULL,
  create_time DATETIME NOT NULL,
  INDEX idx_memory_event_run(user_id,run_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
