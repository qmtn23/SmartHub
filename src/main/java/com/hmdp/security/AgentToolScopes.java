package com.hmdp.security;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class AgentToolScopes {
    public static final String SHOP_READ = "shop:read";
    public static final String VOUCHER_READ = "voucher:read";
    public static final String ORDER_SELF_READ = "order:self:read";
    public static final String BLOG_READ = "blog:read";

    private AgentToolScopes() {
    }

    public static Set<String> allReadScopes() {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                SHOP_READ, VOUCHER_READ, ORDER_SELF_READ, BLOG_READ)));
    }

    public static Set<String> transactionScopes() {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                SHOP_READ, VOUCHER_READ, ORDER_SELF_READ)));
    }

    public static Set<String> discoveryScopes() {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                SHOP_READ, BLOG_READ)));
    }
}
