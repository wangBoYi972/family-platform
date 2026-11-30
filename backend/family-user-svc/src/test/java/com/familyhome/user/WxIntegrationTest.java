package com.familyhome.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.user.dto.LoginDTO;
import com.familyhome.user.mapper.WxSubscribeGrantMapper;
import com.familyhome.user.model.WxSubscribeGrant;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 订阅消息链路（未配置形态）：bind 要明确拒绝、授权台账照常工作、notify 静默跳过。
 * 未配置不是错误——微信凭证是上线后才填的东西，链路必须在缺省时安静待命。
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:wx-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.cloud.nacos.discovery.enabled=false"
        })
@ActiveProfiles("local")
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class WxIntegrationTest {
    private static final String CODE =
            String.format("%06d", new java.security.SecureRandom().nextInt(1000000));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        // 密钥必须固定成局部变量：supplier 会被多次取值，每次随机的话签发与校验的密钥就对不上了
        String secret = UUID.randomUUID() + "" + UUID.randomUUID();
        r.add("FAMILY_JWT_SECRET", () -> secret);
        r.add("family.auth.mock-code", () -> CODE);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired WxSubscribeGrantMapper grants;

    private String token() throws Exception {
        var result =
                mvc.perform(
                                post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(new LoginDTO("19900000001", CODE, "family"))))
                        .andExpect(status().isOk())
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    @Test
    @DisplayName("未配置微信时绑定 openid 返回 503，客户端按「能力不可用」静默处理")
    void bindFailsWithoutConfig() throws Exception {
        mvc.perform(post("/api/wx/session")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"abc\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("授权上报：accept 落一条配额，reject 不落；重复授权累计多行")
    void grantsLedger() throws Exception {
        String bearer = "Bearer " + token();
        mvc.perform(post("/api/wx/subscriptions")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("grants", List.of(
                                Map.of("templateKey", "im-message", "accepted", true),
                                Map.of("templateKey", "task-reminder", "accepted", false))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(1));
        // 再授权一次同一个模板：一次性订阅允许累加配额
        mvc.perform(post("/api/wx/subscriptions")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("grants", List.of(
                                Map.of("templateKey", "im-message", "accepted", true))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(1));
        assertThat(grants.selectCount(new LambdaQueryWrapper<WxSubscribeGrant>()
                .eq(WxSubscribeGrant::getTemplateKey, "im-message")
                .isNull(WxSubscribeGrant::getConsumedAt))).isEqualTo(2);
        assertThat(grants.selectCount(new LambdaQueryWrapper<WxSubscribeGrant>()
                .eq(WxSubscribeGrant::getTemplateKey, "task-reminder"))).isZero();
    }

    @Test
    @DisplayName("未配置时 notify 静默返回 skipped，且不消耗任何配额")
    void notifySkippedWhenDisabled() throws Exception {
        String bearer = "Bearer " + token();
        mvc.perform(post("/api/wx/notify")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"templateKey\":\"im-message\",\"data\":{\"sender\":1,\"preview\":\"到家了\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("skipped"));
        // skipped 不能顺手消耗配额：配额是用户授权的，白扣等于偷
        assertThat(grants.selectCount(new LambdaQueryWrapper<WxSubscribeGrant>()
                .isNotNull(WxSubscribeGrant::getConsumedAt))).isZero();
    }
}
