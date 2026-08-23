package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 长会话与订单、店铺、优惠券等业务对象的关联。
 */
@Data
@Accessors(chain = true)
@TableName("tb_customer_chat_biz_ref")
public class CustomerChatBizRef implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private Long imChatId;
    private Long userId;
    private String bizType;
    private Long bizId;
    private String source;
    private LocalDateTime createTime;
}
