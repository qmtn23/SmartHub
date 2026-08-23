package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 客服长会话。一个长会话可以包含多个短会话。
 */
@Data
@Accessors(chain = true)
@TableName("tb_customer_im_chat")
public class CustomerImChat implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "im_chat_id", type = IdType.INPUT)
    private Long imChatId;
    private Long userId;
    private String title;
    private String primaryIntent;
    private String summary;
    private String handlerType;
    private String status;
    private LocalDateTime lastMessageTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime closeTime;
}
