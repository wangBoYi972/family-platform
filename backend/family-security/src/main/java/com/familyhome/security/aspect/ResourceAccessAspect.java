package com.familyhome.security.aspect;

import com.familyhome.security.SessionPrincipal;
import com.familyhome.security.annotation.RequireResourceAccess;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 资源访问权限检查切面
 * 统一处理资源级别的权限控制
 */
@Slf4j
@Aspect
@Component
@Order(100)
public class ResourceAccessAspect {

    @Around("@annotation(com.familyhome.security.annotation.RequireResourceAccess)")
    public Object checkResourceAccess(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        RequireResourceAccess annotation = signature.getMethod()
                .getAnnotation(RequireResourceAccess.class);

        // 查找 SessionPrincipal 参数
        Object[] args = joinPoint.getArgs();
        SessionPrincipal principal = null;

        for (Object arg : args) {
            if (arg instanceof SessionPrincipal) {
                principal = (SessionPrincipal) arg;
                break;
            }
        }

        if (principal == null) {
            log.error("方法 {} 使用了 @RequireResourceAccess 但缺少 SessionPrincipal 参数",
                    signature.getMethod().getName());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "权限检查配置错误");
        }

        // 管理员拥有所有权限
        if (principal.admin()) {
            return joinPoint.proceed();
        }

        // 根据访问级别进行检查
        RequireResourceAccess.AccessLevel level = annotation.accessLevel();

        // READ 权限: 家庭成员都可以访问
        if (level == RequireResourceAccess.AccessLevel.READ) {
            return joinPoint.proceed();
        }

        // ADMIN 权限: 仅管理员
        if (level == RequireResourceAccess.AccessLevel.ADMIN) {
            log.warn("用户 {} 尝试访问需要管理员权限的资源: {}",
                    principal.userId(), annotation.resourceType());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }

        // WRITE/DELETE 权限: 需要在业务层进一步检查
        // 这里只做基础检查,具体的创建者/负责人判断在业务代码中
        log.debug("用户 {} 访问资源 {} (权限级别: {})",
                principal.userId(), annotation.resourceType(), level);

        return joinPoint.proceed();
    }
}
