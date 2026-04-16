package com.hmdp.utils;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CustomerServiceTools {

    @Resource
    private IShopService shopService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private IBlogService blogService;

    @Tool("根据店铺ID查询店铺详细信息，包括名称、地址、评分、人均价格、营业时间等")
    public String queryShopById(@P("店铺ID") Long shopId) {
        log.debug("Tool调用: queryShopById({})", shopId);
        Shop shop = shopService.getById(shopId);
        if (shop == null) {
            return "未找到ID为" + shopId + "的店铺";
        }
        return String.format("店铺ID：%d，名称：%s，地址：%s，商圈：%s，人均：%d元，评分：%.1f，销量：%d，评论数：%d，营业时间：%s",
                shop.getId(), shop.getName(), shop.getAddress(), shop.getArea(),
                shop.getAvgPrice(), shop.getScore() / 10.0,
                shop.getSold(), shop.getComments(), shop.getOpenHours());
    }

    @Tool("根据关键词搜索店铺列表，返回匹配的店铺名称、地址和评分")
    public String searchShopsByName(@P("搜索关键词，例如店铺名称") String keyword) {
        log.debug("Tool调用: searchShopsByName({})", keyword);
        List<Shop> shops = shopService.query()
                .like("name", keyword)
                .last("LIMIT 5")
                .list();
        if (shops.isEmpty()) {
            return "未找到包含\"" + keyword + "\"的店铺";
        }
        return shops.stream()
                .map(s -> String.format("ID:%d %s（%s，人均%d元，评分%.1f）",
                        s.getId(), s.getName(), s.getAddress(),
                        s.getAvgPrice(), s.getScore() / 10.0))
                .collect(Collectors.joining("\n"));
    }

    @Tool("查询指定店铺的可用优惠券列表，包括普通券和秒杀券")
    public String queryVouchersByShopId(@P("店铺ID") Long shopId) {
        log.debug("Tool调用: queryVouchersByShopId({})", shopId);
        Result result = voucherService.queryVoucherOfShop(shopId);
        if (result.getData() == null) {
            return "该店铺暂无可用优惠券";
        }
        List<Voucher> vouchers = (List<Voucher>) result.getData();
        if (vouchers.isEmpty()) {
            return "该店铺暂无可用优惠券";
        }
        return vouchers.stream()
                .map(v -> String.format("优惠券：%s（%s），支付%d元抵扣%d元，类型：%s",
                        v.getTitle(), v.getSubTitle(),
                        v.getPayValue() / 100, v.getActualValue() / 100,
                        v.getType() == 0 ? "普通券" : "秒杀券"))
                .collect(Collectors.joining("\n"));
    }

    @Tool("查询指定用户的优惠券订单记录")
    public String queryUserOrders(@P("用户ID") Long userId) {
        log.debug("Tool调用: queryUserOrders({})", userId);
        List<VoucherOrder> orders = voucherOrderService.query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .last("LIMIT 10")
                .list();
        if (orders.isEmpty()) {
            return "该用户暂无订单记录";
        }
        return orders.stream()
                .map(o -> {
                    String statusText;
                    switch (o.getStatus()) {
                        case 1: statusText = "未支付"; break;
                        case 2: statusText = "已支付"; break;
                        case 3: statusText = "已核销"; break;
                        case 4: statusText = "已取消"; break;
                        case 5: statusText = "退款中"; break;
                        case 6: statusText = "已退款"; break;
                        default: statusText = "未知";
                    }
                    return String.format("订单号：%d，优惠券ID：%d，状态：%s，下单时间：%s",
                            o.getId(), o.getVoucherId(), statusText, o.getCreateTime());
                })
                .collect(Collectors.joining("\n"));
    }

    @Tool("按店铺类型推荐评分最高的店铺，类型ID对应：1美食 2KTV 3酒店 4文化 5健身 6旅游 7宠物 8美容 9休闲 10亲子")
    public String recommendShopsByType(@P("店铺类型ID，1-10的整数") Integer typeId) {
        log.debug("Tool调用: recommendShopsByType({})", typeId);
        List<Shop> shops = shopService.query()
                .eq("type_id", typeId)
                .orderByDesc("score")
                .last("LIMIT 5")
                .list();
        if (shops.isEmpty()) {
            return "该类型下暂无店铺";
        }
        return shops.stream()
                .map(s -> String.format("【%.1f分】%s - %s，人均%d元",
                        s.getScore() / 10.0, s.getName(), s.getAddress(), s.getAvgPrice()))
                .collect(Collectors.joining("\n"));
    }

    @Tool("查询当前最热门的探店笔记，按点赞数排序")
    public String queryHotBlogs() {
        log.debug("Tool调用: queryHotBlogs()");
        List<Blog> blogs = blogService.query()
                .orderByDesc("liked")
                .last("LIMIT 5")
                .list();
        if (blogs.isEmpty()) {
            return "暂无热门笔记";
        }
        return blogs.stream()
                .map(b -> String.format("《%s》- %d赞，%d评论，内容摘要：%s",
                        b.getTitle(), b.getLiked(), b.getComments(),
                        b.getContent() != null && b.getContent().length() > 50
                                ? b.getContent().substring(0, 50) + "..."
                                : b.getContent()))
                .collect(Collectors.joining("\n"));
    }
}
