package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.config.ChatBusinessException;
import com.hmdp.dto.Result;
import com.hmdp.dto.tool.BlogToolDTO;
import com.hmdp.dto.tool.BusinessReferenceDTO;
import com.hmdp.dto.tool.OrderToolDTO;
import com.hmdp.dto.tool.ShopToolDTO;
import com.hmdp.dto.tool.ToolResult;
import com.hmdp.dto.tool.ToolResultCodes;
import com.hmdp.dto.tool.VoucherToolDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.CustomerChatBizRef;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.CustomerChatBizRefMapper;
import com.hmdp.utils.CustomerToolContext;
import com.hmdp.utils.CustomerToolContextHolder;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 客服Agent访问业务能力的统一入口。
 * 负责注入登录用户上下文、规范化结果并记录长会话业务关联。
 */
@Slf4j
@Service
public class CustomerToolGateway {

    private static final String BIZ_TYPE_SHOP = "SHOP";
    private static final String BIZ_TYPE_VOUCHER = "VOUCHER";
    private static final String BIZ_TYPE_VOUCHER_ORDER = "VOUCHER_ORDER";
    private static final String BIZ_TYPE_BLOG = "BLOG";

    private final IShopService shopService;
    private final IVoucherService voucherService;
    private final IVoucherOrderService voucherOrderService;
    private final IBlogService blogService;
    private final CustomerChatBizRefMapper bizRefMapper;
    private final CustomerToolContextHolder contextHolder;
    private final RedisIdWorker redisIdWorker;

    public CustomerToolGateway(IShopService shopService,
                               IVoucherService voucherService,
                               IVoucherOrderService voucherOrderService,
                               IBlogService blogService,
                               CustomerChatBizRefMapper bizRefMapper,
                               CustomerToolContextHolder contextHolder,
                               RedisIdWorker redisIdWorker) {
        this.shopService = shopService;
        this.voucherService = voucherService;
        this.voucherOrderService = voucherOrderService;
        this.blogService = blogService;
        this.bizRefMapper = bizRefMapper;
        this.contextHolder = contextHolder;
        this.redisIdWorker = redisIdWorker;
    }

    public ToolResult<ShopToolDTO> queryShopById(Long shopId) {
        return execute("queryShopById", context -> {
            requirePositiveId(shopId, "店铺ID");
            Shop shop = shopService.getById(shopId);
            if (shop == null) {
                return ToolResult.failure(ToolResultCodes.NOT_FOUND,
                        "未找到指定店铺", false);
            }
            List<BusinessReferenceDTO> refs = Collections.singletonList(ref(BIZ_TYPE_SHOP, shop.getId()));
            return success(context, "queryShopById", toShopDTO(shop), "店铺查询成功", refs);
        });
    }

    public ToolResult<List<ShopToolDTO>> searchShopsByName(String keyword) {
        return execute("searchShopsByName", context -> {
            String normalizedKeyword = requireText(keyword, "搜索关键词", 50);
            List<Shop> shops = shopService.list(new QueryWrapper<Shop>()
                    .like("name", normalizedKeyword)
                    .last("LIMIT 5"));
            if (shops == null || shops.isEmpty()) {
                return ToolResult.failure(ToolResultCodes.NOT_FOUND,
                        "未找到名称匹配的店铺", false);
            }
            List<BusinessReferenceDTO> refs = shops.stream()
                    .map(shop -> ref(BIZ_TYPE_SHOP, shop.getId()))
                    .collect(Collectors.toList());
            List<ShopToolDTO> data = shops.stream().map(this::toShopDTO).collect(Collectors.toList());
            return success(context, "searchShopsByName", data, "店铺搜索成功", refs);
        });
    }

    @SuppressWarnings("unchecked")
    public ToolResult<List<VoucherToolDTO>> queryVouchersByShopId(Long shopId) {
        return execute("queryVouchersByShopId", context -> {
            requirePositiveId(shopId, "店铺ID");
            Result result = voucherService.queryVoucherOfShop(shopId);
            if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
                return ToolResult.failure(ToolResultCodes.BUSINESS_REJECTED,
                        result == null ? "优惠券查询失败" : result.getErrorMsg(), false);
            }
            List<Voucher> vouchers = result.getData() instanceof List
                    ? (List<Voucher>) result.getData() : Collections.emptyList();
            if (vouchers.isEmpty()) {
                return ToolResult.failure(ToolResultCodes.NOT_FOUND,
                        "该店铺暂无可用优惠券", false);
            }
            List<BusinessReferenceDTO> refs = new ArrayList<>();
            refs.add(ref(BIZ_TYPE_SHOP, shopId));
            vouchers.forEach(voucher -> refs.add(ref(BIZ_TYPE_VOUCHER, voucher.getId())));
            List<VoucherToolDTO> data = vouchers.stream().map(this::toVoucherDTO).collect(Collectors.toList());
            return success(context, "queryVouchersByShopId", data, "优惠券查询成功", refs);
        });
    }

    /**
     * 用户身份只能从服务端工具上下文取得，模型不能传入userId。
     */
    public ToolResult<List<OrderToolDTO>> queryCurrentUserOrders() {
        return execute("queryCurrentUserOrders", context -> {
            List<VoucherOrder> orders = voucherOrderService.list(new QueryWrapper<VoucherOrder>()
                    .eq("user_id", context.getUserId())
                    .orderByDesc("create_time")
                    .last("LIMIT 10"));
            if (orders == null || orders.isEmpty()) {
                return ToolResult.failure(ToolResultCodes.NOT_FOUND,
                        "当前用户暂无订单记录", false);
            }
            List<BusinessReferenceDTO> refs = orders.stream()
                    .map(order -> ref(BIZ_TYPE_VOUCHER_ORDER, order.getId()))
                    .collect(Collectors.toList());
            List<OrderToolDTO> data = orders.stream().map(this::toOrderDTO).collect(Collectors.toList());
            return success(context, "queryCurrentUserOrders", data, "订单查询成功", refs);
        });
    }

    public ToolResult<List<ShopToolDTO>> recommendShopsByType(Integer typeId) {
        return execute("recommendShopsByType", context -> {
            if (typeId == null || typeId < 1 || typeId > 10) {
                throw new IllegalArgumentException("店铺类型ID必须是1到10之间的整数");
            }
            List<Shop> shops = shopService.list(new QueryWrapper<Shop>()
                    .eq("type_id", typeId)
                    .orderByDesc("score")
                    .last("LIMIT 5"));
            if (shops == null || shops.isEmpty()) {
                return ToolResult.failure(ToolResultCodes.NOT_FOUND,
                        "该类型下暂无店铺", false);
            }
            List<BusinessReferenceDTO> refs = shops.stream()
                    .map(shop -> ref(BIZ_TYPE_SHOP, shop.getId()))
                    .collect(Collectors.toList());
            List<ShopToolDTO> data = shops.stream().map(this::toShopDTO).collect(Collectors.toList());
            return success(context, "recommendShopsByType", data, "店铺推荐查询成功", refs);
        });
    }

    public ToolResult<List<BlogToolDTO>> queryHotBlogs() {
        return execute("queryHotBlogs", context -> {
            List<Blog> blogs = blogService.list(new QueryWrapper<Blog>()
                    .orderByDesc("liked")
                    .last("LIMIT 5"));
            if (blogs == null || blogs.isEmpty()) {
                return ToolResult.failure(ToolResultCodes.NOT_FOUND,
                        "暂无热门笔记", false);
            }
            List<BusinessReferenceDTO> refs = blogs.stream()
                    .map(blog -> ref(BIZ_TYPE_BLOG, blog.getId()))
                    .collect(Collectors.toList());
            List<BlogToolDTO> data = blogs.stream().map(this::toBlogDTO).collect(Collectors.toList());
            return success(context, "queryHotBlogs", data, "热门笔记查询成功", refs);
        });
    }

    private <T> ToolResult<T> execute(String toolName,
                                      Function<CustomerToolContext, ToolResult<T>> action) {
        try {
            CustomerToolContext context = contextHolder.requireContext();
            return action.apply(context);
        } catch (ChatBusinessException e) {
            return ToolResult.failure(ToolResultCodes.FORBIDDEN, e.getMessage(), false);
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(ToolResultCodes.INVALID_ARGUMENT, e.getMessage(), false);
        } catch (RuntimeException e) {
            log.error("客服工具调用失败: {}", toolName, e);
            return ToolResult.failure(ToolResultCodes.TEMPORARY_ERROR,
                    "业务服务暂时不可用，请稍后重试", true);
        }
    }

    private <T> ToolResult<T> success(CustomerToolContext context,
                                      String source,
                                      T data,
                                      String message,
                                      List<BusinessReferenceDTO> refs) {
        recordBusinessReferences(context, source, refs);
        return ToolResult.success(data, message, refs);
    }

    private void recordBusinessReferences(CustomerToolContext context,
                                          String source,
                                          List<BusinessReferenceDTO> refs) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        for (BusinessReferenceDTO ref : refs) {
            if (ref.getBizId() == null) {
                continue;
            }
            Integer count = bizRefMapper.selectCount(new QueryWrapper<CustomerChatBizRef>()
                    .eq("im_chat_id", context.getImChatId())
                    .eq("biz_type", ref.getBizType())
                    .eq("biz_id", ref.getBizId()));
            if (count != null && count > 0) {
                continue;
            }
            CustomerChatBizRef entity = new CustomerChatBizRef()
                    .setId(redisIdWorker.nextId("chat_biz_ref"))
                    .setImChatId(context.getImChatId())
                    .setUserId(context.getUserId())
                    .setBizType(ref.getBizType())
                    .setBizId(ref.getBizId())
                    .setSource(source)
                    .setCreateTime(LocalDateTime.now());
            try {
                bizRefMapper.insert(entity);
            } catch (DuplicateKeyException e) {
                log.debug("会话业务关联已存在: imChatId={}, bizType={}, bizId={}",
                        context.getImChatId(), ref.getBizType(), ref.getBizId());
            } catch (RuntimeException e) {
                // 关联记录失败不能影响只读业务查询结果。
                log.warn("记录会话业务关联失败: imChatId={}, bizType={}, bizId={}",
                        context.getImChatId(), ref.getBizType(), ref.getBizId(), e);
            }
        }
    }

    private ShopToolDTO toShopDTO(Shop shop) {
        ShopToolDTO dto = new ShopToolDTO();
        dto.setShopId(shop.getId());
        dto.setName(shop.getName());
        dto.setTypeId(shop.getTypeId());
        dto.setAddress(shop.getAddress());
        dto.setArea(shop.getArea());
        dto.setAvgPrice(shop.getAvgPrice());
        dto.setScore(shop.getScore() == null ? null : shop.getScore() / 10.0);
        dto.setSold(shop.getSold());
        dto.setComments(shop.getComments());
        dto.setOpenHours(shop.getOpenHours());
        return dto;
    }

    private VoucherToolDTO toVoucherDTO(Voucher voucher) {
        VoucherToolDTO dto = new VoucherToolDTO();
        dto.setVoucherId(voucher.getId());
        dto.setShopId(voucher.getShopId());
        dto.setTitle(voucher.getTitle());
        dto.setSubTitle(voucher.getSubTitle());
        dto.setRules(voucher.getRules());
        dto.setPayValueCent(voucher.getPayValue());
        dto.setActualValueCent(voucher.getActualValue());
        dto.setType(voucher.getType());
        dto.setStock(voucher.getStock());
        dto.setBeginTime(voucher.getBeginTime());
        dto.setEndTime(voucher.getEndTime());
        return dto;
    }

    private OrderToolDTO toOrderDTO(VoucherOrder order) {
        OrderToolDTO dto = new OrderToolDTO();
        dto.setOrderId(order.getId());
        dto.setVoucherId(order.getVoucherId());
        dto.setPayType(order.getPayType());
        dto.setStatus(order.getStatus());
        dto.setStatusText(orderStatusText(order.getStatus()));
        dto.setCreateTime(order.getCreateTime());
        dto.setPayTime(order.getPayTime());
        dto.setUseTime(order.getUseTime());
        dto.setRefundTime(order.getRefundTime());
        return dto;
    }

    private BlogToolDTO toBlogDTO(Blog blog) {
        BlogToolDTO dto = new BlogToolDTO();
        dto.setBlogId(blog.getId());
        dto.setTitle(blog.getTitle());
        dto.setLiked(blog.getLiked());
        dto.setComments(blog.getComments());
        String content = blog.getContent();
        dto.setContentSummary(content == null ? null
                : content.length() <= 100 ? content : content.substring(0, 100) + "...");
        return dto;
    }

    private String orderStatusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case 1: return "未支付";
            case 2: return "已支付";
            case 3: return "已核销";
            case 4: return "已取消";
            case 5: return "退款中";
            case 6: return "已退款";
            default: return "未知";
        }
    }

    private void requirePositiveId(Long id, String fieldName) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException(fieldName + "必须是正整数");
        }
    }

    private String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "长度不能超过" + maxLength + "个字符");
        }
        return normalized;
    }

    private BusinessReferenceDTO ref(String bizType, Long bizId) {
        return new BusinessReferenceDTO(bizType, bizId);
    }
}
