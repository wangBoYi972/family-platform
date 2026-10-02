package com.familyhome.user.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.security.SessionPrincipal;
import com.familyhome.user.converter.MemberConverter;
import com.familyhome.user.mapper.AuthSessionMapper;
import com.familyhome.user.mapper.FamilyUserMapper;
import com.familyhome.user.mapper.HouseholdMapper;
import com.familyhome.user.mapper.WhitelistEntryMapper;
import com.familyhome.user.model.AuthSession;
import com.familyhome.user.model.FamilyUser;
import com.familyhome.user.model.WhitelistEntry;
import com.familyhome.user.service.MemberService;
import com.familyhome.user.vo.MemberVO;
import com.familyhome.user.vo.ProfileVO;
import com.familyhome.user.vo.WhitelistVO;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberServiceImpl implements MemberService {
    @Autowired
    private FamilyUserMapper users;
    @Autowired
    private WhitelistEntryMapper whitelist;
    @Autowired
    private HouseholdMapper households;
    @Autowired
    private AuthSessionMapper sessions;

    public void logout(SessionPrincipal p) {
        sessions.update(
                null,
                new LambdaUpdateWrapper<AuthSession>()
                        .eq(AuthSession::getId, p.sessionId())
                        .set(AuthSession::getRevoked, true));
    }

    public ProfileVO me(SessionPrincipal p) {
        var u = users.selectById(p.userId());
        var h = households.selectById(p.householdId());
        return new ProfileVO(u.getId(), u.getDisplayName(), h.getName(), p.role(), p.householdId());
    }

    public void profile(SessionPrincipal p, String name) {
        users.update(
                null,
                new LambdaUpdateWrapper<FamilyUser>()
                        .eq(FamilyUser::getId, p.userId())
                        .set(FamilyUser::getDisplayName, name.trim()));
    }

    public List<MemberVO> members(SessionPrincipal p) {
        var entries =
                whitelist.selectList(
                        new LambdaQueryWrapper<WhitelistEntry>()
                                .eq(WhitelistEntry::getHouseholdId, p.householdId())
                                .eq(WhitelistEntry::getEnabled, true));
        if (entries.isEmpty()) return List.of();
        var roles = new HashMap<String, String>();
        entries.forEach(e -> roles.put(e.getPhone(), e.getRole()));
        return users
                .selectList(
                        new LambdaQueryWrapper<FamilyUser>()
                                .in(FamilyUser::getPhone, roles.keySet())
                                .orderByAsc(FamilyUser::getId))
                .stream()
                .map(u -> new MemberVO(u.getId(), u.getDisplayName(), roles.get(u.getPhone())))
                .toList();
    }

    public List<WhitelistVO> whitelist(SessionPrincipal p) {
        p.requireAdmin();
        return whitelist
                .selectList(
                        new LambdaQueryWrapper<WhitelistEntry>()
                                .eq(WhitelistEntry::getHouseholdId, p.householdId())
                                .orderByAsc(WhitelistEntry::getId))
                .stream()
                .map(MemberConverter::toVO)
                .toList();
    }

    @Transactional
    public void add(SessionPrincipal p, String phone, String role) {
        p.requireAdmin();
        households.lock(p.householdId());
        if (whitelist.selectCount(
                        new LambdaQueryWrapper<WhitelistEntry>()
                                .eq(WhitelistEntry::getHouseholdId, p.householdId())
                                .eq(WhitelistEntry::getEnabled, true))
                >= 10) throw new ResponseStatusException(HttpStatus.CONFLICT, "每个家庭最多十位启用成员");
        if (whitelist.selectCount(
                        new LambdaQueryWrapper<WhitelistEntry>().eq(WhitelistEntry::getPhone, phone))
                > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "该手机号已登记");
        var e = new WhitelistEntry();
        e.setPhone(phone);
        e.setHouseholdId(p.householdId());
        e.setRole(role);
        e.setEnabled(true);
        whitelist.insert(e);
    }

    @Transactional
    public void disable(SessionPrincipal p, long id) {
        p.requireAdmin();
        households.lock(p.householdId());
        var entry =
                whitelist.selectOne(
                        new LambdaQueryWrapper<WhitelistEntry>()
                                .eq(WhitelistEntry::getId, id)
                                .eq(WhitelistEntry::getHouseholdId, p.householdId()));
        if (entry == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成员不存在");
        if (Objects.equals(entry.getPhone(), users.selectById(p.userId()).getPhone()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能停用自己的管理员账号");
        whitelist.update(
                null,
                new LambdaUpdateWrapper<WhitelistEntry>()
                        .eq(WhitelistEntry::getId, id)
                        .eq(WhitelistEntry::getHouseholdId, p.householdId())
                        .set(WhitelistEntry::getEnabled, false));
    }
}
