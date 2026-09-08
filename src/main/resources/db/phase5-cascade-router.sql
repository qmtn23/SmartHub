SET NAMES utf8mb4;

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

CALL smarthub_add_column_if_missing('tb_customer_chat', 'active_scene',
    'varchar(24) DEFAULT NULL COMMENT ''最近成功完成回复的一级场景'' AFTER `active_agent`');
CALL smarthub_add_column_if_missing('tb_customer_chat', 'active_master',
    'varchar(32) DEFAULT NULL COMMENT ''最近成功完成回复的Master Agent'' AFTER `active_scene`');

CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'route_source',
    'varchar(16) DEFAULT NULL COMMENT ''RULE或LLM'' AFTER `handoff_reason_code`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'router_rule_version',
    'varchar(32) DEFAULT NULL AFTER `route_source`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'route_confidence',
    'decimal(5,4) DEFAULT NULL AFTER `router_rule_version`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'scene_scores',
    'longtext COMMENT ''关键词场景得分JSON'' AFTER `route_confidence`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'matched_rule_ids',
    'longtext COMMENT ''命中的规则ID JSON'' AFTER `scene_scores`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'primary_scene',
    'varchar(24) DEFAULT NULL AFTER `matched_rule_ids`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'active_master',
    'varchar(32) DEFAULT NULL AFTER `primary_scene`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'scene_history',
    'longtext COMMENT ''场景执行历史JSON'' AFTER `active_master`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'specialist_history',
    'longtext COMMENT ''专业Agent调用审计JSON'' AFTER `scene_history`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'specialist_call_count',
    'int(11) NOT NULL DEFAULT 0 AFTER `specialist_history`');

DROP PROCEDURE IF EXISTS `smarthub_add_column_if_missing`;
