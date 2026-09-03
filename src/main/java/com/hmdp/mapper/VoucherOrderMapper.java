package com.hmdp.mapper;

import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    @Update("UPDATE tb_voucher_order SET status=4, update_time=NOW() " +
            "WHERE id=#{orderId} AND user_id=#{userId} AND status=1")
    int cancelUnpaidForCustomer(@Param("orderId") Long orderId, @Param("userId") Long userId);

    @Update("UPDATE tb_voucher_order SET status=5, update_time=NOW() " +
            "WHERE id=#{orderId} AND user_id=#{userId} AND status=2 AND use_time IS NULL")
    int requestRefundForCustomer(@Param("orderId") Long orderId, @Param("userId") Long userId);

}
