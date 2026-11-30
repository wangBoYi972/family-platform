package com.familyhome.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.user.dto.LoginDTO;
import com.familyhome.user.mapper.FamilyUserMapper;
import com.familyhome.user.mapper.WxSubscribeGrantMapper;
import com.familyhome.user.model.FamilyUser;
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
 * 订阅消息链路（已配置形态）：配额被正确消耗、openid 缺失时跳过、
 * 微信接口不可达时降级为 failed 而不是抛错——推送失败绝不能把调用方拖出异常。
 * api-base 指向不会监听的本地端口，验证的就是「发不出去也安全」。
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:wx-enabled-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.cloud.nacos.discovery.enabled=false",
            "family.wx.enabled=true",
            "family.wx.appid=wx-test-appid",
            "family.wx.secret=test-secret",
            "family.wx.api-base=http://127.0.0.1:1",
            "family.wx.templates.im-message.template-id=TPL-TEST",
            "family.wx.templates.im-message.fields.sender=thing1",
            "family.wx.templates.im-message.fields.preview=thing2"
        })
@ActiveProfiles("local")
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class WxNotifyFlowTest {
    private static final String CODE =
            String.format("%06d", new java.security.SecureRandom().nextInt(1000000));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        String secret = UUID.randomUUID() + "" + UUID.randomUUID();
        r.add("FAMILY_JWT_SECRET", () -> secret);
        r.add("family.auth.mock-code", () -> CODE);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired WxSubscribeGrantMapper grants;
    @Autowired FamilyUserMapper users;

    private String token;
    private long userId;

    @BeforeEach
    void login() throws Exception {
        // 配额台账在方法之间共享：先清空，配额状态只由当前用例自己构造
        grants.delete(new LambdaQueryWrapper<WxSubscribeGrant>());
        var result =
                mvc.perform(
                                post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(new LoginDTO("19900000001", CODE, "family"))))
                        .andExpect(status().isOk())
                        .andReturn();
        token = json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
        userId = users.selectOne(
                        new LambdaQueryWrapper<FamilyUser>().eq(FamilyUser::getPhone, "19900000001"))
                .getId();
    }

    private void grant() throws Exception {
        mvc.perform(post("/api/wx/subscriptions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("grants", List.of(
                                Map.of("templateKey", "im-message", "accepted", true))))))
                .andExpect(status().isOk());
    }

    private String sendOne() throws Exception {
        var result =
                mvc.perform(
                                post("/api/wx/notify")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(Map.of(
                                                "userId", userId,
                                                "templateKey", "im-message",
                                                "data", Map.of("sender", userId, "preview", "到家了")))))
                        .andExpect(status().isOk())
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("result")
                .asText();
    }

    @Test
    @DisplayName("已配置但没绑 openid：跳过且不消耗配额")
    void skipsWithoutOpenid() throws Exception {
        // 方法之间共享同一个用户行：先把 openid 清掉，别依赖执行顺序
        FamilyUser user = users.selectById(userId);
        user.setWxOpenid(null);
        users.updateById(user);
        grant();
        assertThat(sendOne()).isEqualTo("skipped");
        assertThat(grants.selectCount(new LambdaQueryWrapper<WxSubscribeGrant>()
                .isNull(WxSubscribeGrant::getConsumedAt))).isEqualTo(1);
    }

    @Test
    @DisplayName("绑了 openid 没配额：no-quota，不调微信接口")
    void noQuotaNoSend() throws Exception {
        FamilyUser user = users.selectById(userId);
        user.setWxOpenid("oX-abc123");
        users.updateById(user);
        assertThat(sendOne()).isEqualTo("no-quota");
    }

    @Test
    @DisplayName("openid 与配额都齐：消耗配额后调微信接口；接口不可达降级为 failed")
    void consumesQuotaThenFailsGracefully() throws Exception {
        FamilyUser user = users.selectById(userId);
        user.setWxOpenid("oX-abc123");
        users.updateById(user);
        grant();
        assertThat(sendOne()).isEqualTo("failed");
        assertThat(grants.selectCount(new LambdaQueryWrapper<WxSubscribeGrant>()
                .isNull(WxSubscribeGrant::getConsumedAt))).isZero();
        // 配额被消耗掉了：这次发送确实发生了，只是微信侧没成功
        assertThat(grants.selectCount(new LambdaQueryWrapper<WxSubscribeGrant>()
                .isNotNull(WxSubscribeGrant::getConsumedAt))).isEqualTo(1);
    }
}
