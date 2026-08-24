SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_customer_handoff` (
  `handoff_id` bigint(20) NOT NULL COMMENT '转人工记录ID',
  `im_chat_id` bigint(20) NOT NULL COMMENT '长会话ID',
  `from_chat_id` bigint(20) NOT NULL COMMENT '转接前机器人短会话ID',
  `human_chat_id` bigint(20) NOT NULL COMMENT '人工接待短会话ID',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户ID',
  `reason` varchar(255) DEFAULT NULL COMMENT '转人工原因',
  `summary` text COMMENT '转接摘要',
  `business_refs` text COMMENT '关联业务对象快照',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/COMPLETED',
  `operator_id` varchar(64) DEFAULT NULL COMMENT '人工客服标识',
  `requested_time` datetime NOT NULL COMMENT '申请时间',
  `accepted_time` datetime DEFAULT NULL COMMENT '接入时间',
  `completed_time` datetime DEFAULT NULL COMMENT '结束时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`handoff_id`) USING BTREE,
  KEY `idx_handoff_status_time` (`status`, `requested_time`) USING BTREE,
  KEY `idx_handoff_im_chat` (`im_chat_id`, `handoff_id`) USING BTREE,
  KEY `idx_handoff_user` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服转人工记录';
