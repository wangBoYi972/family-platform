package com.familyhome.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 端点的鉴权与未配置形态：未登录 401（SSE 都不开）、未配置 key 时 503，
 * 客户端按「能力未配置」静默提示——与微信订阅消息同一套待凭证约定。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-endpoint;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        // local profile 没有 family.clients 时 Feign 空 url 会让上下文起不来
        "family.clients.user-url=http://127.0.0.1:8081",
        "family.clients.file-url=http://127.0.0.1:8087",
        // ServiceSecurity 直接按环境变量名读密钥
        "family.auth.jwt-secret=test-jwt-secret-for-ai-svc-tests-32bytes"
})
@AutoConfigureMockMvc
class AiEndpointTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("未登录直接 401，不进入模型调用")
    void requiresLogin() throws Exception {
        mvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未登录读历史也是 401，不把别人的对话漏出去")
    void historyRequiresLogin() throws Exception {
        mvc.perform(get("/api/ai/history")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ai/history/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("鉴权先于参数校验：payload 为空时未登录仍是 401，不泄漏接口形态")
    void authComesBeforeValidation() throws Exception {
        mvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[]}"))
                .andExpect(status().isUnauthorized());
    }
}
