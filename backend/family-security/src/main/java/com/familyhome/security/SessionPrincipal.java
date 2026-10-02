package com.familyhome.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record SessionPrincipal(
        long userId, long householdId, String role, String sessionId, String client) {
    public static SessionPrincipal from(HttpServletRequest request) {
        Object value = request.getAttribute(SessionPrincipal.class.getName());
        if (!(value instanceof SessionPrincipal principal))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return principal;
    }

    public boolean admin() {
        return "ADMIN".equals(role);
    }

    public void requireAdmin() {
        if (!admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可以操作");
    }
}
