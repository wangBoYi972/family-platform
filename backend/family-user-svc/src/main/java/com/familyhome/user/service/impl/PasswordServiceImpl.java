package com.familyhome.user.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.security.SessionPrincipal;
import com.familyhome.user.dto.PasswordChangeDTO;
import com.familyhome.user.mapper.AuthSessionMapper;
import com.familyhome.user.mapper.FamilyUserMapper;
import com.familyhome.user.mapper.UserCredentialMapper;
import com.familyhome.user.mapper.WhitelistEntryMapper;
import com.familyhome.user.model.AuthSession;
import com.familyhome.user.model.FamilyUser;
import com.familyhome.user.model.UserCredential;
import com.familyhome.user.security.InvalidCredentialsException;
import com.familyhome.user.security.PasswordAttemptVerifier;
import com.familyhome.user.security.PasswordHasher;
import com.familyhome.user.service.PasswordService;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasswordServiceImpl implements PasswordService {
    @Autowired
    private WhitelistEntryMapper whitelist;
    @Autowired
    private UserCredentialMapper credentials;
    @Autowired
    private FamilyUserMapper users;
    @Autowired
    private AuthSessionMapper sessions;
    @Autowired
    private PasswordHasher hasher;
    @Autowired
    private com.familyhome.user.service.MemberService members;
    @Autowired
    private PasswordAttemptVerifier passwordVerifier;

    @Override
    @Transactional
    public void create(SessionPrincipal principal, com.familyhome.user.dto.AccountCreateDTO request) {
        // 白名单和密码原子创建；任一失败不会留下不能登录的半成品账号。
        members.add(principal, request.phone(), request.role());
        save(request.phone(), request.password());
    }

    @Override
    @Transactional
    public void set(SessionPrincipal principal, long whitelistId, String password) {
        principal.requireAdmin();
        var target = whitelist.selectById(whitelistId);
        if (target == null || target.getHouseholdId() != principal.householdId())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成员不存在");
        target = whitelist.lockByPhone(target.getPhone());
        if (!Boolean.TRUE.equals(target.getEnabled()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "停用成员不能设置密码");
        save(target.getPhone(), password);
    }

    @Override
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public void change(SessionPrincipal principal, PasswordChangeDTO request) {
        var user = users.selectById(principal.userId());
        whitelist.lockByPhone(user.getPhone());
        passwordVerifier.verify(user.getPhone(), request.currentPassword(), true);
        if (request.currentPassword().equals(request.newPassword()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码不能与当前密码相同");
        save(user.getPhone(), request.newPassword());
    }

    private void save(String phone, String password) {
        var existing = credentials.selectById(phone);
        var credential = new UserCredential();
        credential.setPhone(phone);
        credential.setPasswordHash(hasher.encode(password));
        credential.setChangedAt(LocalDateTime.now());
        if (existing == null) credentials.insert(credential);
        else {
            credentials.updateById(credential);
            credentials.recordAttempt(phone, 0, null);
        }
        var user =
                users.selectOne(new LambdaQueryWrapper<FamilyUser>().eq(FamilyUser::getPhone, phone));
        // 改密和撤销在同一事务提交，所有客户端的旧会话立即失效。
        if (user != null)
            sessions.update(
                    null,
                    new LambdaUpdateWrapper<AuthSession>()
                            .eq(AuthSession::getUserId, user.getId())
                            .set(AuthSession::getRevoked, true));
    }
}
