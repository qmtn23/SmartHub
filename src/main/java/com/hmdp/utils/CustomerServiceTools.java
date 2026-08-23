package com.hmdp.utils;

import com.hmdp.dto.tool.BlogToolDTO;
import com.hmdp.dto.tool.OrderToolDTO;
import com.hmdp.dto.tool.ShopToolDTO;
import com.hmdp.dto.tool.ToolResult;
import com.hmdp.dto.tool.VoucherToolDTO;
import com.hmdp.service.CustomerToolGateway;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LangChain4j工具适配层。所有业务调用统一委托给CustomerToolGateway。
 */
@Component
public class CustomerServiceTools {

    private final CustomerToolGateway toolGateway;

    public CustomerServiceTools(CustomerToolGateway toolGateway) {
        this.toolGateway = toolGateway;
    }

    @Tool("根据店铺ID查询店铺详细信息，返回结构化店铺数据")
    public ToolResult<ShopToolDTO> queryShopById(@P("店铺ID") Long shopId) {
        return toolGateway.queryShopById(shopId);
    }

    @Tool("根据名称关键词搜索店铺，最多返回5条结构化店铺数据")
    public ToolResult<List<ShopToolDTO>> searchShopsByName(
            @P("店铺名称搜索关键词") String keyword) {
        return toolGateway.searchShopsByName(keyword);
    }

    @Tool("查询指定店铺当前可查询到的优惠券，返回金额、库存和有效期等结构化数据")
    public ToolResult<List<VoucherToolDTO>> queryVouchersByShopId(
            @P("店铺ID") Long shopId) {
        return toolGateway.queryVouchersByShopId(shopId);
    }

    @Tool("查询当前已登录用户的最近10条优惠券订单。用户身份由系统注入，不要询问或传入用户ID")
    public ToolResult<List<OrderToolDTO>> queryCurrentUserOrders() {
        return toolGateway.queryCurrentUserOrders();
    }

    @Tool("按店铺类型推荐评分最高的店铺。类型ID：1美食、2KTV、3酒店、4文化、5健身、6旅游、7宠物、8美容、9休闲、10亲子")
    public ToolResult<List<ShopToolDTO>> recommendShopsByType(
            @P("1到10之间的店铺类型ID") Integer typeId) {
        return toolGateway.recommendShopsByType(typeId);
    }

    @Tool("查询当前最热门的探店笔记，返回最多5条结构化笔记摘要")
    public ToolResult<List<BlogToolDTO>> queryHotBlogs() {
        return toolGateway.queryHotBlogs();
    }
}
