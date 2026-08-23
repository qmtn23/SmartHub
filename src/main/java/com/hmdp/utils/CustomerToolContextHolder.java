package com.hmdp.utils;

import com.hmdp.config.ChatBusinessException;
import org.springframework.stereotype.Component;

@Component
public class CustomerToolContextHolder {

    private final ThreadLocal<CustomerToolContext> holder = new ThreadLocal<>();

    public void set(CustomerToolContext context) {
        holder.set(context);
    }

    public CustomerToolContext requireContext() {
        CustomerToolContext context = holder.get();
        if (context == null) {
            throw new ChatBusinessException("工具调用缺少已认证的客服会话上下文");
        }
        return context;
    }

    public void clear() {
        holder.remove();
    }
}
