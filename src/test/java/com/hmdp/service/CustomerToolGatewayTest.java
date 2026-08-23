package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.config.ChatBusinessException;
import com.hmdp.dto.tool.OrderToolDTO;
import com.hmdp.dto.tool.ToolResult;
import com.hmdp.dto.tool.ToolResultCodes;
import com.hmdp.entity.CustomerChatBizRef;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.CustomerChatBizRefMapper;
import com.hmdp.utils.CustomerToolContext;
import com.hmdp.utils.CustomerToolContextHolder;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerToolGatewayTest {

    @Mock
    private IShopService shopService;
    @Mock
    private IVoucherService voucherService;
    @Mock
    private IVoucherOrderService voucherOrderService;
    @Mock
    private IBlogService blogService;
    @Mock
    private CustomerChatBizRefMapper bizRefMapper;
    @Mock
    private CustomerToolContextHolder contextHolder;
    @Mock
    private RedisIdWorker redisIdWorker;

    private CustomerToolGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new CustomerToolGateway(
                shopService, voucherService, voucherOrderService, blogService,
                bizRefMapper, contextHolder, redisIdWorker);
    }

    @Test
    void shouldRejectToolCallWithoutAuthenticatedChatContext() {
        when(contextHolder.requireContext())
                .thenThrow(new ChatBusinessException("缺少上下文"));

        ToolResult<List<OrderToolDTO>> result = gateway.queryCurrentUserOrders();

        assertFalse(result.isSuccess());
        assertEquals(ToolResultCodes.FORBIDDEN, result.getCode());
    }

    @Test
    void shouldQueryOrdersWithServerSideUserAndRecordBusinessReference() {
        CustomerToolContext context = new CustomerToolContext(7L, 1001L, 2001L, 3001L);
        VoucherOrder order = new VoucherOrder()
                .setId(9001L)
                .setUserId(7L)
                .setVoucherId(501L)
                .setStatus(2)
                .setCreateTime(LocalDateTime.now());

        when(contextHolder.requireContext()).thenReturn(context);
        when(voucherOrderService.list(any())).thenReturn(Collections.singletonList(order));
        when(bizRefMapper.selectCount(any())).thenReturn(0);
        when(redisIdWorker.nextId("chat_biz_ref")).thenReturn(8001L);

        ToolResult<List<OrderToolDTO>> result = gateway.queryCurrentUserOrders();

        assertTrue(result.isSuccess());
        assertEquals(ToolResultCodes.SUCCESS, result.getCode());
        assertEquals(9001L, result.getData().get(0).getOrderId());
        assertEquals("已支付", result.getData().get(0).getStatusText());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<VoucherOrder>> queryCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(voucherOrderService).list(queryCaptor.capture());
        QueryWrapper<VoucherOrder> orderQuery = queryCaptor.getValue();
        assertTrue(orderQuery.getSqlSegment().contains("user_id"));
        assertTrue(orderQuery.getParamNameValuePairs().values().stream()
                .anyMatch(value -> "7".equals(String.valueOf(value))));

        ArgumentCaptor<CustomerChatBizRef> refCaptor = ArgumentCaptor.forClass(CustomerChatBizRef.class);
        verify(bizRefMapper).insert(refCaptor.capture());
        assertEquals(1001L, refCaptor.getValue().getImChatId());
        assertEquals(7L, refCaptor.getValue().getUserId());
        assertEquals("VOUCHER_ORDER", refCaptor.getValue().getBizType());
        assertEquals(9001L, refCaptor.getValue().getBizId());
    }
}
