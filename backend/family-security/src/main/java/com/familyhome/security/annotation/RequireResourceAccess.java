package com.familyhome.security.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 资源权限检查注解
 * 用于方法级别的权限控制
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireResourceAccess {

    /**
     * 资源类型
     */
    ResourceType resourceType();

    /**
     * 资源ID参数名(SpEL表达式)
     */
    String resourceIdParam() default "id";

    /**
     * 所需权限
     */
    AccessLevel accessLevel() default AccessLevel.READ;

    /**
     * 资源类型枚举
     */
    enum ResourceType {
        TASK,
        NOTICE,
        CALENDAR,
        DYNAMIC,
        ALBUM,
        FILE,
        ARCHIVE
    }

    /**
     * 访问级别
     */
    enum AccessLevel {
        READ,    // 查看权限
        WRITE,   // 编辑权限(创建者/负责人)
        DELETE,  // 删除权限(创建者或管理员)
        ADMIN    // 管理员权限
    }
}
