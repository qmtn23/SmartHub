SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_customer_action_request` (
  `action_request_id` varchar(32) NOT NULL,
  `original_run_id` varchar(32) NOT NULL COMMENT 'Java AgentRun ID',
  `agent_execution_id` varchar(32) NOT NULL COMMENT 'Agent Service执行ID',
  `request_id` varchar(64) NOT NULL,
  `user_message_id` bigint(20) NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `im_chat_id` bigint(20) NOT NULL,
  `chat_id` bigint(20) NOT NULL,
  `action_type` varchar(32) NOT NULL,
  `target_biz_type` varchar(32) NOT NULL,
  `target_biz_id` bigint(20) NOT NULL,
  `canonical_parameters` text,
  `status` varchar(32) NOT NULL,
  `active_im_chat_id` bigint(20) DEFAULT NULL COMMENT '活跃时等于im_chat_id，终态置空',
  `policy_version` varchar(32) NOT NULL,
  `expires_time` datetime NOT NULL,
  `confirmed_time` datetime DEFAULT NULL,
  `executed_time` datetime DEFAULT NULL,
  `result_code` varchar(64) DEFAULT NULL,
  `result_payload` text,
  `error_code` varchar(64) DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`action_request_id`),
  UNIQUE KEY `uk_action_request_origin` (`request_id`, `action_type`, `target_biz_type`, `target_biz_id`),
  UNIQUE KEY `uk_action_active_im_chat` (`active_im_chat_id`),
  KEY `idx_action_user_status` (`user_id`, `status`),
  KEY `idx_action_expiry` (`status`, `expires_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服受控业务动作';

CREATE TABLE IF NOT EXISTS `tb_customer_action_event` (
  `event_id` varchar(32) NOT NULL,
  `action_request_id` varchar(32) NOT NULL,
  `client_message_id` varchar(64) DEFAULT NULL,
  `event_type` varchar(32) NOT NULL,
  `from_status` varchar(32) DEFAULT NULL,
  `to_status` varchar(32) NOT NULL,
  `payload` text,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`event_id`),
  UNIQUE KEY `uk_action_event_client_message` (`client_message_id`),
  KEY `idx_action_event_request_time` (`action_request_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服业务动作审计事件';

DELIMITER $$
DROP PROCEDURE IF EXISTS `smarthub_add_column_if_missing`$$
CREATE PROCEDURE `smarthub_add_column_if_missing`(
    IN table_name_value VARCHAR(64), IN column_name_value VARCHAR(64), IN definition_value VARCHAR(512)
)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_name_value AND COLUMN_NAME=column_name_value) THEN
        SET @ddl = CONCAT('ALTER TABLE `', table_name_value, '` ADD COLUMN `',
                          column_name_value, '` ', definition_value);
        PREPARE statement FROM @ddl; EXECUTE statement; DEALLOCATE PREPARE statement;
    END IF;
END$$
DELIMITER ;

CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'resolution_type',
    'varchar(32) DEFAULT NULL COMMENT ''RESPONSE_ONLY/ACTION_PROPOSAL/HANDOFF_PROPOSAL'' AFTER `orchestrator`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'action_request_id',
    'varchar(32) DEFAULT NULL AFTER `resolution_type`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'action_type',
    'varchar(32) DEFAULT NULL AFTER `action_request_id`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'action_status',
    'varchar(32) DEFAULT NULL AFTER `action_type`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'interrupt_reason',
    'varchar(64) DEFAULT NULL AFTER `action_status`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'resume_count',
    'int(11) NOT NULL DEFAULT 0 AFTER `interrupt_reason`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'handoff_reason_code',
    'varchar(64) DEFAULT NULL AFTER `resume_count`');
CALL smarthub_add_column_if_missing('tb_customer_handoff', 'reason_code',
    'varchar(64) DEFAULT NULL AFTER `reason`');
CALL smarthub_add_column_if_missing('tb_customer_handoff', 'source',
    'varchar(24) NOT NULL DEFAULT ''USER'' AFTER `reason_code`');
CALL smarthub_add_column_if_missing('tb_customer_handoff', 'agent_run_id',
    'varchar(32) DEFAULT NULL AFTER `source`');
CALL smarthub_add_column_if_missing('tb_customer_handoff', 'action_request_id',
    'varchar(32) DEFAULT NULL AFTER `agent_run_id`');

DROP PROCEDURE IF EXISTS `smarthub_add_column_if_missing`;
