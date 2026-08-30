SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_customer_agent_run` (
  `run_id` varchar(32) NOT NULL COMMENT 'Agent运行ID',
  `request_id` varchar(64) NOT NULL COMMENT '跨服务幂等请求ID',
  `user_message_id` bigint(20) NOT NULL COMMENT '触发运行的用户消息ID',
  `im_chat_id` bigint(20) NOT NULL COMMENT '长会话ID',
  `chat_id` bigint(20) NOT NULL COMMENT '短会话ID/LangGraph thread ID',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '所属用户ID',
  `status` varchar(24) NOT NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED_RETRYABLE/FAILED_FINAL',
  `retryable` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否允许安全重试',
  `attempt_count` int(11) NOT NULL DEFAULT 0 COMMENT '执行尝试次数',
  `trace_id` varchar(64) DEFAULT NULL COMMENT 'Agent链路追踪ID',
  `error_code` varchar(64) DEFAULT NULL COMMENT '最近错误码',
  `started_time` datetime DEFAULT NULL COMMENT '最近开始时间',
  `finished_time` datetime DEFAULT NULL COMMENT '完成时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`run_id`) USING BTREE,
  UNIQUE KEY `uk_agent_run_request` (`request_id`) USING BTREE,
  UNIQUE KEY `uk_agent_run_user_message` (`user_message_id`) USING BTREE,
  KEY `idx_agent_run_chat_status` (`chat_id`, `status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服Agent运行审计';
