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

CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'execution_mode',
    'varchar(16) NOT NULL DEFAULT ''SIMPLE'' COMMENT ''SIMPLE或COMPLEX'' AFTER `completion_tokens`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'plan_id',
    'varchar(64) DEFAULT NULL COMMENT ''Supervisor计划ID'' AFTER `execution_mode`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'supervisor_iterations',
    'int(11) NOT NULL DEFAULT 0 COMMENT ''Supervisor规划迭代次数'' AFTER `plan_id`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'parallel_task_count',
    'int(11) NOT NULL DEFAULT 0 COMMENT ''并行编排任务数'' AFTER `supervisor_iterations`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'task_outcomes',
    'longtext COMMENT ''领域任务执行结果JSON'' AFTER `parallel_task_count`');
CALL smarthub_add_column_if_missing('tb_customer_agent_run', 'orchestrator',
    'varchar(32) NOT NULL DEFAULT ''router'' COMMENT ''router或supervisor'' AFTER `task_outcomes`');

DROP PROCEDURE IF EXISTS `smarthub_add_column_if_missing`;
