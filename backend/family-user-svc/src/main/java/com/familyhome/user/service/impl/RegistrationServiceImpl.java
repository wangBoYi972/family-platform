package com.familyhome.user.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.user.config.AuthProperties;
import com.familyhome.user.dto.RegisterDTO;
import com.familyhome.user.mapper.AuthSessionMapper;
import com.familyhome.user.mapper.FamilyMemberMapper;
import com.familyhome.user.mapper.FamilyUserMapper;
import com.familyhome.user.mapper.HouseholdMapper;
import com.familyhome.user.mapper.InviteCodeMapper;
import com.familyhome.user.mapper.UserCredentialMapper;
import com.familyhome.user.mapper.WhitelistEntryMapper;
import com.familyhome.user.model.AuthSession;
import com.familyhome.user.model.FamilyMember;
import com.familyhome.user.model.FamilyUser;
import com.familyhome.user.model.InviteCode;
import com.familyhome.user.model.UserCredential;
import com.familyhome.user.model.WhitelistEntry;
import com.familyhome.user.security.InviteCodeGenerator;
import com.familyhome.user.security.LoginRateLimiter;
import com.familyhome.user.security.PasswordHasher;
import com.familyhome.user.security.TokenService;
import com.familyhome.user.service.RegistrationService;
import com.familyhome.user.vo.LoginVO;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 自助注册。
 *
 * <p>注册对所有人开放，但唯一的准入门槛是管理员发放的邀请码：邀请码决定了加入哪个家庭、
 * 获得什么角色，注册人无法自选。邀请码不存在、已作废、已过期或用尽时直接拒绝，
 * 且不会创建任何账号或凭据（整段逻辑在同一事务内）。
 */
@Service
public class RegistrationServiceImpl implements RegistrationService {
    private static final Set<String> CLIENTS = Set.of("admin", "family", "archive");
    private static final int HOUSEHOLD_MEMBER_LIMIT = 10;
    @Autowired private com.familyhome.user.config.DbClock clock;
    @org.springframework.beans.factory.annotation.Value("${family.auth.register-attempt-limit:5}")
    private int attemptLimit;

    @Autowired private AuthProperties properties;
    @Autowired private InviteCodeMapper inviteCodes;
    @Autowired private WhitelistEntryMapper whitelist;
    @Autowired private FamilyUserMapper users;
    @Autowired private FamilyMemberMapper members;
    @Autowired private HouseholdMapper households;
    @Autowired private UserCredentialMapper credentials;
    @Autowired private AuthSessionMapper sessions;
    @Autowired private TokenService tokens;
    @Autowired private PasswordHasher hasher;
    @Autowired private InviteCodeGenerator generator;
    @Autowired private LoginRateLimiter loginRateLimiter;

    /** 注册是匿名入口，按手机号限制尝试次数，避免邀请码被批量试错。 */
    private void limit(String phone) {
        loginRateLimiter.checkAndIncrement("register", phone, attemptLimit);
    }

    @Override
    @Transactional
    public LoginVO register(RegisterDTO request) {
        limit(request.phone());
        String client = request.client() == null || request.client().isBlank() ? "family" : request.client();
        if (!CLIENTS.contains(client))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的客户端类型");

        InviteCode code = inviteCodes.lockByHash(generator.hash(generator.normalize(request.inviteCode())));
        if (code == null || !Boolean.TRUE.equals(code.getEnabled()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "邀请码无效，请联系管理员获取");
        int maxUses = code.getMaxUses() == null ? 1 : code.getMaxUses();
        int usedCount = code.getUsedCount() == null ? 0 : code.getUsedCount();
        if (usedCount >= maxUses)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "邀请码已被使用，请联系管理员重新发放");
        if (code.getExpiresAt() != null && code.getExpiresAt().isBefore(clock.now()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "邀请码已过期，请联系管理员重新发放");
        // 管理端只接受管理员邀请码，防止拿成员邀请码注册出可进后台的账号。
        if (client.equals("admin") && !"ADMIN".equals(code.getRole()))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "该邀请码只能注册家庭成员账号，请在家庭小程序端使用");

        long householdId = code.getHouseholdId();
        households.lock(householdId);
        if (whitelist.selectCount(
                        new LambdaQueryWrapper<WhitelistEntry>()
                                .eq(WhitelistEntry::getHouseholdId, householdId)
                                .eq(WhitelistEntry::getEnabled, true))
                >= HOUSEHOLD_MEMBER_LIMIT)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该家庭成员数已达上限，无法继续注册");
        if (whitelist.selectCount(
                        new LambdaQueryWrapper<WhitelistEntry>().eq(WhitelistEntry::getPhone, request.phone()))
                > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "该手机号已注册，请直接登录");

        var entry = new WhitelistEntry();
        entry.setPhone(request.phone());
        entry.setHouseholdId(householdId);
        entry.setRole(code.getRole());
        entry.setEnabled(true);
        whitelist.insert(entry);

        var credential = new UserCredential();
        credential.setPhone(request.phone());
        credential.setPasswordHash(hasher.encode(request.password()));
        credential.setFailedAttempts(0);
        credential.setChangedAt(LocalDateTime.now());
        credentials.insert(credential);

        inviteCodes.update(
                null,
                new LambdaUpdateWrapper<InviteCode>()
                        .eq(InviteCode::getId, code.getId())
                        .setSql("used_count = used_count + 1")
                        .set(InviteCode::getUsedAt, LocalDateTime.now())
                        .set(InviteCode::getUsedPhone, request.phone())
                        .set(InviteCode::getEnabled, usedCount + 1 < maxUses));

        FamilyUser user =
                users.selectOne(
                        new LambdaQueryWrapper<FamilyUser>().eq(FamilyUser::getPhone, request.phone()));
        if (user == null) {
            user = new FamilyUser();
            user.setPhone(request.phone());
            user.setDisplayName(
                    request.displayName() == null || request.displayName().isBlank()
                            ? "家庭成员"
                            : request.displayName().trim());
            user.setCreatedAt(LocalDateTime.now());
            users.insert(user);
        }
        FamilyMember member =
                members.selectOne(
                        new LambdaQueryWrapper<FamilyMember>()
                                .eq(FamilyMember::getHouseholdId, householdId)
                                .eq(FamilyMember::getUserId, user.getId()));
        if (member == null) {
            member = new FamilyMember();
            member.setHouseholdId(householdId);
            member.setUserId(user.getId());
            member.setRole(code.getRole());
            members.insert(member);
        }

        AuthSession session = new AuthSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(user.getId());
        session.setHouseholdId(householdId);
        session.setRole(code.getRole());
        session.setClient(client);
        // 与 AuthServiceImpl 一致：JWT 用 Instant，数据库列用数据库时钟推算。
        Instant expiresAt = Instant.now().plusSeconds(properties.getTokenExpirationMinutes() * 60L);
        session.setExpiresAt(clock.now().plusMinutes(properties.getTokenExpirationMinutes()));
        session.setRevoked(false);
        sessions.insert(session);
        return new LoginVO(
                tokens.createToken(
                        user.getId(), householdId, code.getRole(), session.getId(), client, expiresAt),
                user.getId(),
                householdId,
                code.getRole());
    }
}
