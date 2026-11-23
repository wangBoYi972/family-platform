package com.familyhome.user.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.user.config.AuthProperties;
import com.familyhome.user.dto.LoginDTO;
import com.familyhome.user.dto.PasswordLoginDTO;
import com.familyhome.user.mapper.AuthSessionMapper;
import com.familyhome.user.mapper.FamilyMemberMapper;
import com.familyhome.user.mapper.FamilyUserMapper;
import com.familyhome.user.mapper.WhitelistEntryMapper;
import com.familyhome.user.model.AuthSession;
import com.familyhome.user.model.FamilyMember;
import com.familyhome.user.model.FamilyUser;
import com.familyhome.user.model.WhitelistEntry;
import com.familyhome.user.security.InvalidCredentialsException;
import com.familyhome.user.security.PasswordAttemptVerifier;
import com.familyhome.user.security.TokenService;
import com.familyhome.user.service.AuthService;
import com.familyhome.user.vo.LoginVO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import com.familyhome.user.security.LoginRateLimiter;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthServiceImpl implements AuthService {
    @Autowired
    private AuthProperties properties;
    @Autowired
    private WhitelistEntryMapper whitelist;
    @Autowired
    private FamilyUserMapper users;
    @Autowired
    private FamilyMemberMapper members;
    @Autowired
    private AuthSessionMapper sessions;
    @Autowired
    private TokenService tokens;
    @Autowired
    private Environment environment;
    @Autowired
    private PasswordAttemptVerifier passwordVerifier;
    @Autowired
    private LoginRateLimiter loginRateLimiter;

    /** 同一手机号在限流窗口内的登录尝试上限，集成测试可调高以免 429 干扰断言。 */
    @Value("${family.auth.login-attempt-limit:10}")
    private int loginAttemptLimit;

    @Override
    @Transactional
    public LoginVO login(LoginDTO request) {
        loginRateLimiter.checkAndIncrement("login", request.phone(), loginAttemptLimit);
        String client = request.client() == null ? "family" : request.client();
        if (!Set.of("admin", "family", "archive").contains(client))
            throw new IllegalArgumentException("无效的客户端类型");
        boolean local = Arrays.asList(environment.getActiveProfiles()).contains("local");
        if (!local || !properties.isMockLoginEnabled() || properties.getMockCode().isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "验证码登录暂未开放，请使用密码登录");
        if (!MessageDigest.isEqual(
                properties.getMockCode().getBytes(StandardCharsets.UTF_8),
                request.code().getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "手机号或验证码无效");
        WhitelistEntry entry = whitelist.lockByPhone(request.phone());
        if (entry == null || !Boolean.TRUE.equals(entry.getEnabled()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "手机号或验证码无效");
        if (client.equals("admin") && !entry.getRole().equals("ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "管理端仅限管理员登录");
        return issueSession(entry, client);
    }

    @Override
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public LoginVO passwordLogin(PasswordLoginDTO request) {
        if (!Set.of("admin", "family", "archive").contains(request.client()))
            throw new IllegalArgumentException("无效的客户端类型");
        // 以数据库行锁串行化同一账号的认证和改密，防止多实例并发绕过计数。
        var entry = whitelist.lockByPhone(request.phone());
        passwordVerifier.verify(request.phone(), request.password(), false);
        if (entry == null || !Boolean.TRUE.equals(entry.getEnabled()))
            throw new InvalidCredentialsException();
        if (request.client().equals("admin") && !entry.getRole().equals("ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "管理端仅限管理员登录");
        return issueSession(entry, request.client());
    }

    private LoginVO issueSession(WhitelistEntry entry, String client) {
        FamilyUser user =
                users.selectOne(
                        new LambdaQueryWrapper<FamilyUser>().eq(FamilyUser::getPhone, entry.getPhone()));
        if (user == null) {
            user = new FamilyUser();
            user.setPhone(entry.getPhone());
            user.setDisplayName("家庭成员");
            user.setCreatedAt(LocalDateTime.now());
            users.insert(user);
        }
        FamilyMember member =
                members.selectOne(
                        new LambdaQueryWrapper<FamilyMember>()
                                .eq(FamilyMember::getHouseholdId, entry.getHouseholdId())
                                .eq(FamilyMember::getUserId, user.getId()));
        if (member == null) {
            member = new FamilyMember();
            member.setHouseholdId(entry.getHouseholdId());
            member.setUserId(user.getId());
            member.setRole(entry.getRole());
            members.insert(member);
        } else if (!Objects.equals(member.getRole(), entry.getRole())) {
            member.setRole(entry.getRole());
            members.updateById(member);
        }
        AuthSession session = new AuthSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(user.getId());
        session.setHouseholdId(entry.getHouseholdId());
        session.setRole(entry.getRole());
        session.setClient(client);
        Instant expiresAt = Instant.now().plusSeconds(properties.getTokenExpirationMinutes() * 60);
        session.setExpiresAt(LocalDateTime.ofInstant(expiresAt, java.time.ZoneId.systemDefault()));
        session.setRevoked(false);
        sessions.insert(session);
        return new LoginVO(
                tokens.createToken(
                        user.getId(),
                        entry.getHouseholdId(),
                        entry.getRole(),
                        session.getId(),
                        client,
                        expiresAt),
                user.getId(),
                entry.getHouseholdId(),
                entry.getRole());
    }
}
