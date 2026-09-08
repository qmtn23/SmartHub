-- Additive migration. Apply before enabling CUSTOMER_MEMORY_ENABLED.
CREATE TABLE IF NOT EXISTS tb_customer_task_memory (
  user_id BIGINT NOT NULL,
  scope_type VARCHAR(16) NOT NULL DEFAULT 'PLATFORM',
  scope_id BIGINT NOT NULL DEFAULT 0,
  memory_content LONGTEXT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  update_time DATETIME NOT NULL,
  PRIMARY KEY(user_id, scope_type, scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_customer_memory_job (
  chat_id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  im_chat_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  last_msg_id BIGINT NOT NULL DEFAULT 0,
  target_msg_id BIGINT NOT NULL DEFAULT 0,
  lease_id VARCHAR(32) NULL,
  lease_until DATETIME NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NOT NULL,
  error_code VARCHAR(64) NULL,
  update_time DATETIME NOT NULL,
  INDEX idx_memory_work(status, next_attempt_at, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Exact receipts supplement the high watermark: a lower-ID transaction may commit late.
-- Retain receipts while their source messages exist, including after task TTL expiry.
CREATE TABLE IF NOT EXISTS tb_customer_memory_receipt (
  message_id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  chat_id BIGINT NOT NULL,
  processed_at DATETIME NOT NULL,
  INDEX idx_memory_receipt_chat(user_id, chat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
