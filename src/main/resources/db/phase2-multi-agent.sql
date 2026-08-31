SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS `smarthub_add_column_if_missing`$$
CREATE PROCEDURE `smarthub_add_column_if_missing`(
    IN table_name_value VARCHAR(64),
    IN column_name_value VARCHAR(64),
    IN definition_value VARCHAR(512)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = table_name_value
          AND COLUMN_NAME = column_name_value
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', table_name_value, '` ADD COLUMN `',
                          column_name_value, '` ', definition_value);
        PREPARE statement FROM @ddl;
        EXECUTE statement;
        DEALLOCATE PREPARE statement;
    END IF;
END$$
DELIMITER ;

CALL smarthub_add_column_if_missing('tb_customer_chat', 'active_agent',
    'varchar(32) DEFAULT NULL COMMENT ''最近成功完成回复的Agent'' AFTER `intent`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'graph_version',
    'varchar(16) NOT NULL DEFAULT ''v2'' COMMENT ''LangGraph版本'' AFTER `error_code`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'entry_agent',
    'varchar(32) DEFAULT NULL COMMENT ''入口Agent'' AFTER `graph_version`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'final_agent',
    'varchar(32) DEFAULT NULL COMMENT ''最终回复Agent'' AFTER `entry_agent`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'route_history',
    'longtext COMMENT ''路由历史JSON'' AFTER `final_agent`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'handoff_count',
    'int(11) NOT NULL DEFAULT 0 COMMENT ''顺序Handoff次数'' AFTER `route_history`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'model_call_count',
    'int(11) NOT NULL DEFAULT 0 COMMENT ''模型调用次数'' AFTER `handoff_count`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'tool_call_count',
    'int(11) NOT NULL DEFAULT 0 COMMENT ''工具调用次数'' AFTER `model_call_count`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'prompt_tokens',
    'int(11) NOT NULL DEFAULT 0 COMMENT ''输入Token数'' AFTER `tool_call_count`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'completion_tokens',
    'int(11) NOT NULL DEFAULT 0 COMMENT ''输出Token数'' AFTER `prompt_tokens`');

DROP PROCEDURE IF EXISTS `smarthub_add_column_if_missing`;
