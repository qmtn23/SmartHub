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
@TableName("tb_customer_agent_run")
public class CustomerAgentRun implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "run_id", type = IdType.INPUT)
    private String runId;
    private String requestId;
    private Long userMessageId;
    private Long imChatId;
    private Long chatId;
    private Long userId;
    private String status;
    private Boolean retryable;
    private Integer attemptCount;
    private String traceId;
    private String errorCode;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private LocalDateTime updateTime;
}
