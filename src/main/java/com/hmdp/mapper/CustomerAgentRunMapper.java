package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.CustomerAgentRun;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface CustomerAgentRunMapper extends BaseMapper<CustomerAgentRun> {

    @Update("UPDATE tb_customer_agent_run " +
            "SET status = 'RUNNING', retryable = 0, attempt_count = attempt_count + 1, " +
            "started_time = NOW(), finished_time = NULL, error_code = NULL, update_time = NOW() " +
            "WHERE run_id = #{runId} AND (status IN ('PENDING', 'FAILED_RETRYABLE') " +
            "OR (status = 'RUNNING' AND update_time < #{staleBefore}))")
    int claimForExecution(@Param("runId") String runId,
                          @Param("staleBefore") LocalDateTime staleBefore);
}
