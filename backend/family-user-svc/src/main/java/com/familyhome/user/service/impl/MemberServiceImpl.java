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

    /** 单次批量查询允许的最大 ID 数，防止枚举拖库。 */
    private static final int BATCH_USER_ID_LIMIT = 200;

    @Override
    public Map<Long, MemberVO> batchGetUsers(SessionPrincipal p, String userIds) {
        if (userIds == null || userIds.isBlank()) {
            return Map.of();
        }

        // 解析用户ID列表：容忍非法片段（非数字直接跳过），避免坏参数导致 500
        List<Long> ids;
        try {
            ids = Arrays.stream(userIds.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> {
                        try {
                            return Long.parseLong(s);
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .distinct()
                    .limit(BATCH_USER_ID_LIMIT + 1)   // 多取一个用于超限判断
                    .toList();
        } catch (Exception e) {
            return Map.of();
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        if (ids.size() > BATCH_USER_ID_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次最多查询 " + BATCH_USER_ID_LIMIT + " 个用户");
        }

        // 安全边界：先取本家庭启用中的白名单电话与角色，查询只允许落在本家庭成员范围内。
        // FamilyUser 是全局表且无 householdId，不能只按 id 查——否则任何成员都能枚举全库用户信息。
        List<WhitelistEntry> entries = whitelist.selectList(
                new LambdaQueryWrapper<WhitelistEntry>()
                        .eq(WhitelistEntry::getHouseholdId, p.householdId())
                        .eq(WhitelistEntry::getEnabled, true)
        );
        if (entries.isEmpty()) {
            return Map.of();
        }
        Map<String, String> phoneToRole = new HashMap<>();
        entries.forEach(e -> phoneToRole.put(e.getPhone(), e.getRole()));

        // 按 id + 本家庭电话双重过滤；非本家庭的 id 不会出现在结果里
        List<FamilyUser> userList = users.selectList(
                new LambdaQueryWrapper<FamilyUser>()
                        .in(FamilyUser::getId, ids)
                        .in(FamilyUser::getPhone, phoneToRole.keySet())
        );

        Map<Long, MemberVO> result = new HashMap<>();
        for (FamilyUser user : userList) {
            String role = phoneToRole.get(user.getPhone());
            if (role == null) {   // 双重保险：即便 SQL 条件失效也不泄露非本家庭成员
                continue;
            }
            result.put(user.getId(), new MemberVO(user.getId(), user.getDisplayName(), role));
        }
        return result;
    }

    public List<WhitelistVO> whitelist(SessionPrincipal p, String keyword) {
        p.requireAdmin();
        String text = keyword == null ? "" : keyword.trim();
        var query = new LambdaQueryWrapper<WhitelistEntry>()
                .eq(WhitelistEntry::getHouseholdId, p.householdId());
        // 手机号是主检索字段；角色（ADMIN/MEMBER）允许按关键字定位
        if (!text.isEmpty())
            query.and(w -> w.like(WhitelistEntry::getPhone, text).or().like(WhitelistEntry::getRole, text));
        return whitelist
                .selectList(query.orderByAsc(WhitelistEntry::getId))
                .stream()
                .map(MemberConverter::toVO)
                .toList();
    }

    @Transactional
    public WhitelistVO add(SessionPrincipal p, String phone, String role) {
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
        return MemberConverter.toVO(e);
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
