SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_customer_im_chat` (
  `im_chat_id` bigint(20) NOT NULL COMMENT '长会话ID',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '所属用户ID',
  `title` varchar(64) NOT NULL DEFAULT '新客服会话' COMMENT '会话标题',
  `primary_intent` varchar(64) DEFAULT NULL COMMENT '主要意图',
  `summary` text COMMENT '长会话摘要',
  `handler_type` varchar(16) NOT NULL DEFAULT 'BOT' COMMENT '当前处理方：BOT/HUMAN',
  `status` varchar(24) NOT NULL DEFAULT 'BOT_ACTIVE' COMMENT '长会话状态',
  `last_message_time` datetime NOT NULL COMMENT '最后消息时间',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `close_time` datetime DEFAULT NULL COMMENT '关闭时间',
  PRIMARY KEY (`im_chat_id`) USING BTREE,
  KEY `idx_im_chat_user_last_time` (`user_id`, `last_message_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服长会话';

CREATE TABLE IF NOT EXISTS `tb_customer_chat` (
  `chat_id` bigint(20) NOT NULL COMMENT '短会话ID',
  `im_chat_id` bigint(20) NOT NULL COMMENT '所属长会话ID',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '所属用户ID',
  `intent` varchar(64) DEFAULT NULL COMMENT '本轮意图',
  `active_agent` varchar(32) DEFAULT NULL COMMENT '最近成功完成回复的Agent',
  `summary` text COMMENT '短会话摘要',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '短会话状态',
  `start_time` datetime NOT NULL COMMENT '开始时间',
  `last_active_time` datetime NOT NULL COMMENT '最后活跃时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  PRIMARY KEY (`chat_id`) USING BTREE,
  KEY `idx_chat_im_status` (`im_chat_id`, `status`) USING BTREE,
  KEY `idx_chat_user` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服短会话';

CREATE TABLE IF NOT EXISTS `tb_customer_chat_message` (
  `message_id` bigint(20) NOT NULL COMMENT '消息ID',
  `im_chat_id` bigint(20) NOT NULL COMMENT '长会话ID',
  `chat_id` bigint(20) NOT NULL COMMENT '短会话ID',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '所属用户ID',
  `sender_type` varchar(16) NOT NULL COMMENT '发送方：USER/ASSISTANT/HUMAN/SYSTEM',
  `message_type` varchar(16) NOT NULL DEFAULT 'TEXT' COMMENT '消息类型',
  `content` text COMMENT '消息内容',
  `structured_content` longtext COMMENT '结构化消息内容',
  `client_message_id` varchar(64) DEFAULT NULL COMMENT '客户端幂等消息ID',
  `reply_to_message_id` bigint(20) DEFAULT NULL COMMENT '回复的用户消息ID',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`message_id`) USING BTREE,
  UNIQUE KEY `uk_message_user_client` (`user_id`, `client_message_id`) USING BTREE,
  KEY `idx_message_im_id` (`im_chat_id`, `message_id`) USING BTREE,
  KEY `idx_message_chat_id` (`chat_id`, `message_id`) USING BTREE,
  KEY `idx_message_reply_to` (`reply_to_message_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服消息';

CREATE TABLE IF NOT EXISTS `tb_customer_chat_biz_ref` (
  `id` bigint(20) NOT NULL COMMENT '关联记录ID',
  `im_chat_id` bigint(20) NOT NULL COMMENT '长会话ID',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '所属用户ID',
  `biz_type` varchar(32) NOT NULL COMMENT '业务类型',
  `biz_id` bigint(20) NOT NULL COMMENT '业务对象ID',
  `source` varchar(32) NOT NULL COMMENT '关联来源',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_im_chat_biz` (`im_chat_id`, `biz_type`, `biz_id`) USING BTREE,
  KEY `idx_biz_ref_user` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服会话业务关联';
