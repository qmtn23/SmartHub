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
@TableName("tb_customer_action_request")
public class CustomerActionRequest implements Serializable {
    @TableId(value = "action_request_id", type = IdType.INPUT)
    private String actionRequestId;
    private String originalRunId;
    private String agentExecutionId;
    private String requestId;
    private Long userMessageId;
    private Long userId;
    private Long imChatId;
    private Long chatId;
    private String actionType;
    private String targetBizType;
    private Long targetBizId;
    private String canonicalParameters;
    private String status;
    private Long activeImChatId;
    private String policyVersion;
    private LocalDateTime expiresTime;
    private LocalDateTime confirmedTime;
    private LocalDateTime executedTime;
    private String resultCode;
    private String resultPayload;
    private String errorCode;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
