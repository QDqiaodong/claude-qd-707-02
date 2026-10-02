package com.marina.berth.security;

import java.io.IOException;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 把请求头里的身份解析进 {@link ActorContext}，请求结束清理 ThreadLocal。
 * 角色缺失或非法时按 STAFF 处理；OWNER 身份必须带 owner id，否则忽略该头（仍按 STAFF）。
 */
@Component
@Order(1)
public class ActorFilter implements Filter {

    @Override
    public void doFilter(jakarta.servlet.ServletRequest request,
                         jakarta.servlet.ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        try {
            String role = req.getHeader(ActorContext.HEADER_ROLE);
            Long ownerId = parseId(req.getHeader(ActorContext.HEADER_OWNER_ID));
            if (ActorContext.OWNER.equalsIgnoreCase(role) && ownerId != null) {
                ActorContext.set(new ActorContext.Actor(ActorContext.OWNER, ownerId));
            } else {
                ActorContext.set(new ActorContext.Actor(ActorContext.STAFF, null));
            }
            chain.doFilter(req, (HttpServletResponse) response);
        } finally {
            ActorContext.clear();
        }
    }

    private Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
