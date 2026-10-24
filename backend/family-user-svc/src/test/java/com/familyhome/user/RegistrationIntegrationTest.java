package com.familyhome.user;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.user.mapper.InviteCodeMapper;
import com.familyhome.user.mapper.UserCredentialMapper;
import com.familyhome.user.mapper.WhitelistEntryMapper;
import com.familyhome.user.model.InviteCode;
import com.familyhome.user.model.WhitelistEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 开放注册的边界：没有有效邀请码就不能创建任何账号，邀请码本身只能由管理员发放。 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:registration-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.cloud.nacos.discovery.enabled=false",
            "family.bootstrap.enabled=true",
            "family.bootstrap.household-name=邀请码注册验收家庭",
            "family.bootstrap.admin-phone=19900000888"
        })
@ActiveProfiles("prod")
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class RegistrationIntegrationTest {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String SECRET = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final AtomicInteger SEQUENCE = new AtomicInteger(300);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("FAMILY_JWT_SECRET", () -> SECRET);
        registry.add("family.bootstrap.admin-password", () -> PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired WhitelistEntryMapper whitelist;
    @Autowired UserCredentialMapper credentials;
    @Autowired InviteCodeMapper inviteCodes;

    private String phone() {
        return "1990000" + String.format("%04d", SEQUENCE.incrementAndGet());
    }

    private String adminToken() throws Exception {
        var response =
                mvc.perform(
                                post("/api/auth/password-login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "phone",
                                                                "19900000888",
                                                                "password",
                                                                PASSWORD,
                                                                "client",
                                                                "admin"))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        return json.readTree(response.getContentAsString()).path("data").path("token").asText();
    }

    private String issue(String token, String role, int maxUses, Integer hours) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("role", role);
        body.put("maxUses", maxUses);
        if (hours != null) body.put("expiresInHours", hours);
        var response =
                mvc.perform(
                                post("/api/users/invite-codes")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        return json.readTree(response.getContentAsString()).path("data").path("code").asText();
    }

    private String register(String phone, String code, String client) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("phone", phone);
        body.put("password", PASSWORD);
        body.put("inviteCode", code);
        body.put("client", client);
        var response =
                mvc.perform(
                                post("/api/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        return json.readTree(response.getContentAsString()).path("data").path("token").asText();
    }

    private void rejected(String phone, String code, String client) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("phone", phone);
        body.put("password", PASSWORD);
        body.put("inviteCode", code);
        body.put("client", client);
        mvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("邀请码无效时拒绝注册，且不留下任何账号或凭据")
    void invalidInviteCodeCreatesNothing() throws Exception {
        issue(adminToken(), "MEMBER", 1, 24);
        String phone = phone();
        rejected(phone, "FAM-0000-ZZZZ", "family");
        rejected(phone(), "FAM-9999-WWWW", "family");
        assertThat(
                        whitelist.selectCount(
                                new LambdaQueryWrapper<WhitelistEntry>().eq(WhitelistEntry::getPhone, phone)))
                .isZero();
        assertThat(credentials.selectById(phone)).isNull();
    }

    @Test
    @DisplayName("邀请码注册成功即可登录，且同一邀请码不能注册第二个人")
    void inviteCodeIsSingleUse() throws Exception {
        String code = issue(adminToken(), "MEMBER", 1, 24);
        String phone = phone();
        String memberToken = register(phone, code, "family");
        assertThat(memberToken).isNotBlank();
        var entry =
                whitelist.selectOne(
                        new LambdaQueryWrapper<WhitelistEntry>().eq(WhitelistEntry::getPhone, phone));
        assertThat(entry).isNotNull();
        assertThat(entry.getRole()).isEqualTo("MEMBER");
        mvc.perform(
                        post("/api/auth/password-login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("phone", phone, "password", PASSWORD, "client", "family"))))
                .andExpect(status().isOk());
        rejected(phone(), code, "family");
    }

    @Test
    @DisplayName("过期的邀请码无法注册")
    void expiredInviteCodeIsRejected() throws Exception {
        String code = issue(adminToken(), "MEMBER", 1, 24);
        var stored =
                inviteCodes.selectOne(
                        new LambdaQueryWrapper<InviteCode>()
                                .eq(InviteCode::getCodeHint, code.substring(code.length() - 4)));
        assertThat(stored).isNotNull();
        stored.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        inviteCodes.updateById(stored);
        rejected(phone(), code, "family");
    }

    @Test
    @DisplayName("成员邀请码不能用来注册管理端账号")
    void memberInviteCodeCannotCreateAdminSession() throws Exception {
        String code = issue(adminToken(), "MEMBER", 1, 24);
        rejected(phone(), code, "admin");
    }

    @Test
    @DisplayName("只有管理员能发放邀请码，列表不返回明文")
    void inviteCodeManagementIsAdminOnly() throws Exception {
        String admin = adminToken();
        String code = issue(admin, "MEMBER", 2, 24);
        String memberToken = register(phone(), code, "family");
        mvc.perform(
                        post("/api/users/invite-codes")
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("role", "MEMBER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users/invite-codes").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        var response =
                mvc.perform(get("/api/users/invite-codes").header("Authorization", "Bearer " + admin))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        var rows = json.readTree(response.getContentAsString()).path("data");
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.size()).isPositive();
        rows.forEach(row -> assertThat(row.path("code").isNull()).isTrue());
    }

    @Test
    @DisplayName("作废后的邀请码立即失效")
    void revokedInviteCodeIsRejected() throws Exception {
        String admin = adminToken();
        String code = issue(admin, "MEMBER", 1, 24);
        var stored =
                inviteCodes.selectOne(
                        new LambdaQueryWrapper<InviteCode>()
                                .eq(InviteCode::getCodeHint, code.substring(code.length() - 4)));
        mvc.perform(
                        delete("/api/users/invite-codes/" + stored.getId())
                                .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        rejected(phone(), code, "family");
    }
}
