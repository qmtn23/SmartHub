package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 客服消息持久化记录。
 */
@Data
@Accessors(chain = true)
@TableName("tb_customer_chat_message")
public class CustomerChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "message_id", type = IdType.INPUT)
    private Long messageId;
    private Long imChatId;
    private Long chatId;
    private Long userId;
    private String senderType;
    private String messageType;
    private String content;
    private String structuredContent;
    private String clientMessageId;
    private Long replyToMessageId;
    private LocalDateTime createTime;
}
