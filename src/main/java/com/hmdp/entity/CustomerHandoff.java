package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 机器人客服向人工客服转接的轻量记录，不代表业务工单。
 */
@Data
@Accessors(chain = true)
@TableName("tb_customer_handoff")
public class CustomerHandoff implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "handoff_id", type = IdType.INPUT)
    private Long handoffId;
    private Long imChatId;
    private Long fromChatId;
    private Long humanChatId;
    private Long userId;
    private String reason;
    private String summary;
    private String businessRefs;
    private String status;
    private String operatorId;
    private LocalDateTime requestedTime;
    private LocalDateTime acceptedTime;
    private LocalDateTime completedTime;
    private LocalDateTime updateTime;
}
