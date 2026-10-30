package com.familyhome.security.config;

import com.familyhome.api.file.feign.FileFeignClient;
import com.familyhome.api.user.feign.UserFeignClient;
import com.familyhome.api.user.service.MemberCacheService;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 各业务服务共用的 Feign 客户端装配。
 *
 * <p>{@link MemberCacheService} 位于 {@code com.familyhome.api} 包，不在任何服务的组件扫描路径下，
 * 必须在此显式引入；否则依赖它的服务（如 content-svc）会在启动时报 "required a bean of type
 * MemberCacheService that could not be found"。
 */
@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = {UserFeignClient.class, FileFeignClient.class})
@Import(MemberCacheService.class)
public class ServiceClients {}
