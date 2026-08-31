package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 客服短会话，承载一次连续沟通和模型短期上下文。
 */
@Data
@Accessors(chain = true)
@TableName("tb_customer_chat")
public class CustomerChat implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "chat_id", type = IdType.INPUT)
    private Long chatId;
    private Long imChatId;
    private Long userId;
    private String intent;
    private String activeAgent;
    private String summary;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime lastActiveTime;
    private LocalDateTime endTime;
}
