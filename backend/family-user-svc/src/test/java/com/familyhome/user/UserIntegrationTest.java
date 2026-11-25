package com.familyhome.user;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyhome.user.dto.LoginDTO;
import com.familyhome.user.mapper.*;
import com.familyhome.user.model.*;
import com.familyhome.user.service.*;
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

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:user-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.cloud.nacos.discovery.enabled=false"
        })
@ActiveProfiles("local")
@AutoConfigureMockMvc(
        print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class UserIntegrationTest {
    private static final String CODE =
            String.format("%06d", new java.security.SecureRandom().nextInt(1000000));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        String secret = UUID.randomUUID().toString() + UUID.randomUUID();
        r.add("FAMILY_JWT_SECRET", () -> secret);
        r.add("family.auth.mock-code", () -> CODE);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired MemberService members;
    @Autowired WhitelistEntryMapper whitelist;
    @Autowired HouseholdMapper households;
    @Autowired FamilyUserMapper users;

    private String token(String phone) throws Exception {
        var result =
                mvc.perform(
                                post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(new LoginDTO(phone, CODE, "family"))))
                        .andExpect(status().isOk())
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("token")
                .asText();
    }

    @Test
    @DisplayName("登录、真实会话查询、注销后立即拒绝访问")
    void loginAndRevoke() throws Exception {
        String token = token("19900000001");
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.householdName").value("本地测试家庭"));
        mvc.perform(get("/api/auth/session").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").doesNotExist());
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("客户端伪造家庭请求头不能切换家庭，缺少身份不能访问")
    void rejectsUntrustedTenant() throws Exception {
        String token = token("19900000001");
        mvc.perform(
                        get("/api/users/me")
                                .header("Authorization", "Bearer " + token)
                                .header("X-Household-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.householdName").value("本地测试家庭"));
        mvc.perform(get("/api/users/me").header("X-Household-Id", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("白名单列表支持 keyword 在 SQL 里过滤手机号与角色")
    void whitelistKeywordFilter() throws Exception {
        String token = token("19900000001");
        Long household = households.selectList(null).stream()
                .filter(h -> "本地测试家庭".equals(h.getName()))
                .findFirst()
                .orElseThrow()
                .getId();
        for (String phone : new String[] {"18800000055", "18800000066", "13900000055"}) {
            var w = new WhitelistEntry();
            w.setPhone(phone);
            w.setHouseholdId(household);
            w.setRole("MEMBER");
            w.setEnabled(true);
            whitelist.insert(w);
        }
        // 不带 keyword：全量返回
        mvc.perform(get("/api/users/whitelist").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // 关键字命中两个手机号
        mvc.perform(get("/api/users/whitelist?keyword=18800000055")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].phone").value("18800000055"));
        // 关键字只匹配片段，而不是要求全等
        mvc.perform(get("/api/users/whitelist?keyword=139000000")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].phone").value("13900000055"));
        // 完全不匹配时返回空列表，而不是报错
        mvc.perform(get("/api/users/whitelist?keyword=没有这个号")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("批量查用户不能跨家庭泄露，非法ID容错，超限拒绝")
    void batchGetUsersIsHouseholdScoped() throws Exception {
        // 家庭 A：登录用户 + 一个本机成员
        var homeA = new Household();
        homeA.setName("批量查询家庭A");
        households.insert(homeA);
        for (String phone : new String[] {"19900001001", "19900001002"}) {
            var w = new WhitelistEntry();
            w.setPhone(phone);
            w.setHouseholdId(homeA.getId());
            w.setRole(phone.endsWith("001") ? "ADMIN" : "MEMBER");
            w.setEnabled(true);
            whitelist.insert(w);
        }
        var ua = new FamilyUser();
        ua.setPhone("19900001001");
        ua.setDisplayName("A家庭成员");
        ua.setCreatedAt(java.time.LocalDateTime.now());
        users.insert(ua);
        var ua2 = new FamilyUser();
        ua2.setPhone("19900001002");
        ua2.setDisplayName("A家庭另一成员");
        ua2.setCreatedAt(java.time.LocalDateTime.now());
        users.insert(ua2);

        // 家庭 B：外部用户（不应被 A 查到）
        var homeB = new Household();
        homeB.setName("批量查询家庭B");
        households.insert(homeB);
        var wb = new WhitelistEntry();
        wb.setPhone("19900002001");
        wb.setHouseholdId(homeB.getId());
        wb.setRole("ADMIN");
        wb.setEnabled(true);
        whitelist.insert(wb);
        var ub = new FamilyUser();
        ub.setPhone("19900002001");
        ub.setDisplayName("B家庭外部用户");
        ub.setCreatedAt(java.time.LocalDateTime.now());
        users.insert(ub);

        String tokenA = token("19900001001");

        // 本家庭成员能查到
        mvc.perform(get("/api/users/batch")
                        .param("ids", String.valueOf(ua2.getId()))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['" + ua2.getId() + "'].displayName").value("A家庭另一成员"));

        // 跨家庭用户查不到（关键：漏洞回归）
        mvc.perform(get("/api/users/batch")
                        .param("ids", String.valueOf(ub.getId()))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['" + ub.getId() + "']").doesNotExist());

        // 混合查询：只返回本家庭的，外部用户被过滤
        mvc.perform(get("/api/users/batch")
                        .param("ids", ua2.getId() + "," + ub.getId())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data['" + ub.getId() + "']").doesNotExist());

        // 非法 ID 片段不导致 500
        mvc.perform(get("/api/users/batch")
                        .param("ids", "abc,not-a-number," + ua2.getId())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        // 超过 200 个不同的 ID 直接 400（去重后仍需超限，避免 distinct 绕过）
        String many = java.util.stream.IntStream.rangeClosed(1, 201)
                .mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        mvc.perform(get("/api/users/batch")
                        .param("ids", many)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("普通成员不能登录管理端或登记白名单")
    void memberCannotAdminister() throws Exception {
        var h = new Household();
        h.setName("成员权限测试家庭");
        households.insert(h);
        var w = new WhitelistEntry();
        w.setPhone("19900000022");
        w.setHouseholdId(h.getId());
        w.setRole("MEMBER");
        w.setEnabled(true);
        whitelist.insert(w);
        mvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(new LoginDTO(w.getPhone(), CODE, "admin"))))
                .andExpect(status().isForbidden());
        String token = token(w.getPhone());
        mvc.perform(
                        post("/api/users/whitelist")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"phone\":\"19900000023\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
        w.setEnabled(false);
        whitelist.updateById(w);
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
