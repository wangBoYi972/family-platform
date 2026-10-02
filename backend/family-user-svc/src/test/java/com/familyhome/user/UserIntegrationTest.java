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
