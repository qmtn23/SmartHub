package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.CustomerActionRequest;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface CustomerActionRequestMapper extends BaseMapper<CustomerActionRequest> {
    @Update("UPDATE tb_customer_action_request SET status='EXECUTING', confirmed_time=NOW(), " +
            "update_time=NOW() WHERE action_request_id=#{id} AND user_id=#{userId} " +
            "AND status='AWAITING_CONFIRMATION' AND expires_time > NOW()")
    int claimForExecution(@Param("id") String id, @Param("userId") Long userId);

    @Update("UPDATE tb_customer_action_request SET status='DECLINED', active_im_chat_id=NULL, " +
            "result_code='USER_DECLINED', result_payload='已放弃本次操作。', " +
            "executed_time=NOW(), update_time=NOW() " +
            "WHERE action_request_id=#{id} AND user_id=#{userId} AND status='AWAITING_CONFIRMATION'")
    int decline(@Param("id") String id, @Param("userId") Long userId);

    @Update("UPDATE tb_customer_action_request SET status='EXPIRED', active_im_chat_id=NULL, " +
            "result_code='CONFIRMATION_EXPIRED', result_payload='本次操作确认已过期，请重新发起。', " +
            "executed_time=NOW(), update_time=NOW() " +
            "WHERE action_request_id=#{id} AND status='AWAITING_CONFIRMATION' AND expires_time <= NOW()")
    int expire(@Param("id") String id);

    @Update("UPDATE tb_customer_action_request SET status=#{status}, active_im_chat_id=NULL, " +
            "result_code=#{resultCode}, result_payload=#{resultPayload}, error_code=#{errorCode}, " +
            "executed_time=NOW(), update_time=NOW() WHERE action_request_id=#{id}")
    int finish(@Param("id") String id, @Param("status") String status,
               @Param("resultCode") String resultCode, @Param("resultPayload") String resultPayload,
               @Param("errorCode") String errorCode);
}
