package com.familyhome.security;

import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import com.familyhome.api.user.feign.UserFeignClient;
import com.familyhome.security.config.ServiceClients;
import feign.FeignException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Import({ApiErrors.class, ServiceClients.class})
public class ServiceSecurity implements WebMvcConfigurer {
    @Value("${family.auth.jwt-secret:}") private String secret;
    @Autowired private ObjectProvider<UserFeignClient> client;
    @Autowired private ObjectProvider<IdentityVerifier> verifier;
    private byte[] key;
    private IdentityVerifier localVerifier;

    @PostConstruct
    void initialize() {
        key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) throw new IllegalStateException("FAMILY_JWT_SECRET 需要至少 32 字节");
        localVerifier = verifier.getIfAvailable();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry
                .addInterceptor(
                        new HandlerInterceptor() {
                            @Override
                            public boolean preHandle(
                                    HttpServletRequest request, HttpServletResponse response, Object handler) {
                                String path = request.getRequestURI();
                                // 登录与开放注册是匿名入口；注册的安全边界由邀请码保证。
                                if ("POST".equals(request.getMethod())
                                        && Set.of("/api/auth/login", "/api/auth/password-login", "/api/auth/register")
                                                .contains(path)) return true;
                                // 家侧网关匿名入口（仅这三条）；status 等仍需登录。
                                // 注册靠配对码；devices/state 在控制器内校验 X-Gateway-Token。
                                if (("POST".equals(request.getMethod())
                                                && Set.of(
                                                                "/api/iot/gateway/register",
                                                                "/api/iot/gateway/devices",
                                                                "/api/iot/gateway/state")
                                                        .contains(path))) return true;
                                String bearer = request.getHeader("Authorization");
                                SessionPrincipal principal;
                                try {
                                    if (bearer == null || !bearer.startsWith("Bearer "))
                                        throw new IllegalArgumentException("缺少或无效的 Authorization header");
                                    Claims claims =
                                            Jwts.parser()
                                                    .verifyWith(Keys.hmacShaKeyFor(key))
                                                    .requireIssuer("family-home")
                                                    .build()
                                                    .parseSignedClaims(bearer.substring(7))
                                                    .getPayload();
                                    if (claims.getExpiration() == null || claims.getId() == null)
                                        throw new IllegalArgumentException("JWT 缺少必需字段");
                                    principal =
                                            new SessionPrincipal(
                                                    Long.parseLong(claims.getSubject()),
                                                    ((Number) claims.get("householdId")).longValue(),
                                                    claims.get("role", String.class),
                                                    claims.getId(),
                                                    claims.get("client", String.class));
                                    if (principal.userId() < 1
                                            || principal.householdId() < 1
                                            || !Set.of("ADMIN", "MEMBER").contains(principal.role())
                                            || !Set.of("admin", "family", "archive").contains(principal.client()))
                                        throw new IllegalArgumentException("JWT 包含无效的 principal 数据");
                                } catch (RuntimeException ex) {
                                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
                                }
                                if (localVerifier != null) localVerifier.verify(principal);
                                else {
                                    try {
                                        if (client.getObject().session(bearer).code() != 0)
                                            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
                                    } catch (FeignException ex) {
                                        throw new ResponseStatusException(
                                                ex.status() == 401 || ex.status() == 403
                                                        ? HttpStatus.UNAUTHORIZED
                                                        : HttpStatus.SERVICE_UNAVAILABLE,
                                                "身份服务验证失败");
                                    } catch (RuntimeException ex) {
                                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "身份服务暂时不可用");
                                    }
                                }
                                if ("archive".equals(principal.client())
                                        && !path.startsWith("/api/auth/")
                                        && !("GET".equals(request.getMethod())
                                                && (path.startsWith("/api/archives")
                                                        || path.startsWith("/api/files")
                                                        || path.startsWith("/api/notices")
                                                        || path.equals("/api/users/me")))) {
                                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "档案端仅提供档案、文件和公告查询");
                                }
                                request.setAttribute(SessionPrincipal.class.getName(), principal);
                                return true;
                            }
                        })
                .addPathPatterns("/api/**");
    }
}
