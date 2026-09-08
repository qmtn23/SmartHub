-- Additive migration; run once after phase5. No default merchant grants or secrets.
CREATE TABLE IF NOT EXISTS tb_shop_manager (
  shop_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
  PRIMARY KEY(shop_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_merchant_faq (
  faq_id VARCHAR(32) PRIMARY KEY, shop_id BIGINT NOT NULL,
  revision INT NOT NULL DEFAULT 1, active_revision INT NULL,
  pending_revision INT NULL, enabled TINYINT NOT NULL DEFAULT 0,
  requested_by BIGINT NULL, published_by BIGINT NULL, published_time DATETIME NULL,
  update_time DATETIME NOT NULL, INDEX idx_faq_shop(shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_merchant_faq_revision (
  faq_id VARCHAR(32) NOT NULL, revision INT NOT NULL,
  payload TEXT NOT NULL, checksum VARCHAR(64) NOT NULL,
  created_by BIGINT NOT NULL, create_time DATETIME NOT NULL,
  PRIMARY KEY(faq_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_faq_index_outbox (
  event_id VARCHAR(32) PRIMARY KEY, faq_id VARCHAR(32) NOT NULL,
  revision INT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  lease_id VARCHAR(32) NULL, lease_until DATETIME NULL,
  attempts INT NOT NULL DEFAULT 0, error_code VARCHAR(64) NULL,
  create_time DATETIME NOT NULL, update_time DATETIME NOT NULL,
  UNIQUE KEY uk_faq_index_revision(faq_id,revision),
  INDEX idx_index_work(status,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE tb_customer_chat_message ADD COLUMN consultation_context TEXT NULL;
