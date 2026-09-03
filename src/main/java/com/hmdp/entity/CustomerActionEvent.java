package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("tb_customer_action_event")
public class CustomerActionEvent implements Serializable {
    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;
    private String actionRequestId;
    private String clientMessageId;
    private String eventType;
    private String fromStatus;
    private String toStatus;
    private String payload;
    private LocalDateTime createTime;
}
