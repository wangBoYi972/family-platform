package com.familyhome.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 网关上下文装载验证：本地 profile 下无需 Nacos/Redis，限流默认关闭时也能正常启动。 */
@SpringBootTest(
        properties = {
            "spring.cloud.nacos.discovery.enabled=false",
            "family.gateway.rate-limit.enabled=false"
        })
@ActiveProfiles("local")
class GatewayContextTest {
    @Test
    @DisplayName("网关应用上下文可启动（限流关闭、无外部依赖）")
    void contextLoads() {}
}
