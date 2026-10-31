package com.familyhome.user;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyhome.user.config.ProductionBootstrap;
import com.familyhome.user.mapper.*;
import com.familyhome.user.model.*;
import com.familyhome.user.security.PasswordHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            // LOCK_TIMEOUT：并发用例里 8 个线程靠 family_white_list 行锁串行化，
            // H2 默认锁等待只有 1s，机器负载高时会超时，而 H2 在锁超时后会话即不可用，
            // 表现成 Spring 的 "JDBC rollback failed: Connection is closed"（偶发、非业务缺陷）。
            // 生产走 MySQL，innodb_lock_wait_timeout 默认 50s，不会出现这种秒级超时。
            "spring.datasource.url=jdbc:h2:mem:password-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.cloud.nacos.discovery.enabled=false",
            "family.bootstrap.enabled=true",
            "family.bootstrap.household-name=正式认证验收家庭",
            "family.bootstrap.admin-phone=19900000999",
            "family.auth.token-expiration-minutes=7"
        })
@ActiveProfiles("prod")
@AutoConfigureMockMvc(
        print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class PasswordAuthenticationIntegrationTest {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String SECRET = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final AtomicInteger SEQUENCE = new AtomicInteger(100);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("FAMILY_JWT_SECRET", () -> SECRET);
        registry.add("family.bootstrap.admin-password", () -> PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired WhitelistEntryMapper whitelist;
    @Autowired HouseholdMapper households;
    @Autowired UserCredentialMapper credentials;
    @Autowired AuthSessionMapper sessions;
    @Autowired PasswordHasher hasher;
    @Autowired ProductionBootstrap bootstrap;

    private WhitelistEntry account(String role) {
        var household = new Household();
        household.setName("认证边界验收");
        households.insert(household);
        return account(role, household.getId());
    }

    private WhitelistEntry account(String role, long householdId) {
        var entry = new WhitelistEntry();
        entry.setPhone("1990000" + String.format("%04d", SEQUENCE.incrementAndGet()));
        entry.setHouseholdId(householdId);
        entry.setRole(role);
        entry.setEnabled(true);
        whitelist.insert(entry);
        var credential = new UserCredential();
        credential.setPhone(entry.getPhone());
        credential.setPasswordHash(hasher.encode(PASSWORD));
        credential.setChangedAt(LocalDateTime.now());
        credentials.insert(credential);
        return entry;
    }

    private String body(String phone, String password, String client) throws Exception {
        return json.writeValueAsString(Map.of("phone", phone, "password", password, "client", client));
    }

    private String login(String phone, String password, String client) throws Exception {
        var response =
                mvc.perform(
                                post("/api/auth/password-login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body(phone, password, client)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        return json.readTree(response.getContentAsString()).path("data").path("token").asText();
    }

    @Test
    @DisplayName("正式环境首次初始化能够登录且会话与令牌采用配置期限")
    void productionLoginAndLifetime() throws Exception {
        String token = login("19900000999", PASSWORD, "admin");
        var claims =
                Jwts.parser()
                        .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(7 * 60_000);
        assertThat(
                        Math.abs(
                                sessions
                                                .selectById(claims.getId())
                                                .getExpiresAt()
                                                .atZone(ZoneId.systemDefault())
                                                .toInstant()
                                                .toEpochMilli()
                                        - claims.getExpiration().getTime()))
                .isLessThan(1000);
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.householdName").value("正式认证验收家庭"));
        assertThat(credentials.selectById("19900000999").getPasswordHash())
                .startsWith("$2a$12$")
                .doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("正式环境拒绝固定验证码入口")
    void mockLoginRemainsDisabled() throws Exception {
        mvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"phone\":\"19900000999\",\"code\":\"123456\",\"client\":\"admin\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("错误密码计数不会随拒绝响应回滚，锁定到期后允许恢复")
    void persistedLockoutAndRecovery() throws Exception {
        var entry = account("MEMBER");
        for (int i = 0; i < 5; i++)
            mvc.perform(
                            post("/api/auth/password-login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body(entry.getPhone(), "incorrect-password", "family")))
                    .andExpect(status().isUnauthorized());
        assertThat(credentials.selectById(entry.getPhone()).getFailedAttempts()).isEqualTo(5);
        mvc.perform(
                        post("/api/auth/password-login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(entry.getPhone(), PASSWORD, "family")))
                .andExpect(status().isTooManyRequests());
        credentials.recordAttempt(
                entry.getPhone(), 5, LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        login(entry.getPhone(), PASSWORD, "family");
        assertThat(credentials.selectById(entry.getPhone()).getFailedAttempts()).isZero();
        assertThat(credentials.selectById(entry.getPhone()).getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("并发失败请求共享数据库锁定计数")
    void concurrentAttemptsCannotBypassLockout() throws Exception {
        var entry = account("MEMBER");
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 8; i++)
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return mvc.perform(
                                                    post("/api/auth/password-login")
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content(body(entry.getPhone(), "incorrect-password", "family")))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var future : futures) statuses.add(future.get(30, TimeUnit.SECONDS));
            assertThat(statuses.stream().filter(s -> s == 401).count()).isEqualTo(5);
            assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(3);
            assertThat(credentials.selectById(entry.getPhone()).getFailedAttempts()).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("普通成员不能进入管理端或重置其他账号密码")
    void memberCannotResetPassword() throws Exception {
        var entry = account("MEMBER");
        mvc.perform(
                        post("/api/auth/password-login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(entry.getPhone(), PASSWORD, "admin")))
                .andExpect(status().isForbidden());
        var token = login(entry.getPhone(), PASSWORD, "family");
        mvc.perform(
                        put("/api/users/whitelist/" + entry.getId() + "/password")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("password", UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("管理员重置密码时校验家庭归属并撤销所有旧客户端会话")
    void resetScopesAndRevokesSessions() throws Exception {
        var admin = account("ADMIN");
        var member = account("MEMBER", admin.getHouseholdId());
        var outsider = account("MEMBER");
        String adminToken = login(admin.getPhone(), PASSWORD, "admin");
        String familyToken = login(member.getPhone(), PASSWORD, "family");
        String archiveToken = login(member.getPhone(), PASSWORD, "archive");
        String newPassword = UUID.randomUUID().toString();
        String input = json.writeValueAsString(Map.of("password", newPassword));
        mvc.perform(
                        put("/api/users/whitelist/" + outsider.getId() + "/password")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(input))
                .andExpect(status().isNotFound());
        mvc.perform(
                        put("/api/users/whitelist/" + member.getId() + "/password")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(input))
                .andExpect(status().isOk());
        for (String oldToken : List.of(familyToken, archiveToken))
            mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + oldToken))
                    .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/auth/password-login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(member.getPhone(), PASSWORD, "family")))
                .andExpect(status().isUnauthorized());
        login(member.getPhone(), newPassword, "family");
    }

    @Test
    @DisplayName("本人改密必须验证原密码并使当前会话失效")
    void changePasswordRequiresCurrentSecret() throws Exception {
        var member = account("MEMBER");
        String token = login(member.getPhone(), PASSWORD, "archive");
        String newPassword = UUID.randomUUID().toString();
        mvc.perform(
                        put("/api/auth/password")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "currentPassword", "incorrect-password", "newPassword", newPassword))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put("/api/auth/password")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("currentPassword", PASSWORD, "newPassword", newPassword))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        login(member.getPhone(), newPassword, "archive");
    }

    @Test
    @DisplayName("重复初始化不会覆盖已有密码或创建重复家庭")
    void accountCreationIsAtomic() throws Exception {
        var admin = account("ADMIN");
        String token = login(admin.getPhone(), PASSWORD, "admin");
        String phone = "19900000771";
        mvc.perform(
                        post("/api/users/accounts")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("phone", phone, "role", "MEMBER", "password", "密".repeat(30)))))
                .andExpect(status().isBadRequest());
        assertThat(credentials.selectById(phone)).isNull();
        assertThat(
                        whitelist.selectCount(
                                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                                                WhitelistEntry>()
                                        .eq(WhitelistEntry::getPhone, phone)))
                .isZero();
        mvc.perform(
                        post("/api/users/accounts")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("phone", phone, "role", "MEMBER", "password", PASSWORD))))
                .andExpect(status().isOk());
        login(phone, PASSWORD, "family");
    }

    @Test
    @DisplayName("修改密码入口同样持久限制原密码猜测次数")
    void passwordChangeCannotBypassLockout() throws Exception {
        var entry = account("MEMBER");
        String token = login(entry.getPhone(), PASSWORD, "family");
        for (int i = 0; i < 5; i++)
            mvc.perform(
                            put("/api/auth/password")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            json.writeValueAsString(
                                                    Map.of(
                                                            "currentPassword",
                                                            "incorrect-password",
                                                            "newPassword",
                                                            UUID.randomUUID().toString()))))
                    .andExpect(status().isForbidden());
        assertThat(credentials.selectById(entry.getPhone()).getFailedAttempts()).isEqualTo(5);
        mvc.perform(
                        post("/api/auth/password-login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(entry.getPhone(), PASSWORD, "family")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("重复初始化不会覆盖已有密码或创建重复家庭")
    void bootstrapIsIdempotent() {
        String before = credentials.selectById("19900000999").getPasswordHash();
        long count = households.selectCount(null);
        bootstrap.run(new DefaultApplicationArguments());
        assertThat(credentials.selectById("19900000999").getPasswordHash()).isEqualTo(before);
        assertThat(households.selectCount(null)).isEqualTo(count);
    }

    @Test
    @DisplayName("停用账号即使密码正确也不能登录，未知账号返回统一错误")
    void disabledAndUnknownAccountsAreRejected() throws Exception {
        var entry = account("MEMBER");
        entry.setEnabled(false);
        whitelist.updateById(entry);
        for (String phone : List.of(entry.getPhone(), "18800000000"))
            mvc.perform(
                            post("/api/auth/password-login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body(phone, PASSWORD, "family")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.msg").value("手机号或密码无效"));
    }
}
