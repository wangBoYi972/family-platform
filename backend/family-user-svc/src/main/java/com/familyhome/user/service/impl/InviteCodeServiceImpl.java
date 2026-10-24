package com.familyhome.user.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.security.SessionPrincipal;
import com.familyhome.user.converter.InviteCodeConverter;
import com.familyhome.user.dto.InviteCodeCreateDTO;
import com.familyhome.user.mapper.HouseholdMapper;
import com.familyhome.user.mapper.InviteCodeMapper;
import com.familyhome.user.model.InviteCode;
import com.familyhome.user.security.InviteCodeGenerator;
import com.familyhome.user.service.InviteCodeService;
import com.familyhome.user.vo.InviteCodeVO;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InviteCodeServiceImpl implements InviteCodeService {
    /** 同一家庭最多保留 50 枚邀请码，避免无节制发放堆积可复用凭据。 */
    private static final int HOUSEHOLD_LIMIT = 50;

    @Autowired private InviteCodeMapper inviteCodes;
    @Autowired private HouseholdMapper households;
    @Autowired private InviteCodeGenerator generator;

    @Override
    @Transactional
    public InviteCodeVO issue(SessionPrincipal principal, InviteCodeCreateDTO request) {
        principal.requireAdmin();
        households.lock(principal.householdId());
        String role = request.role() == null || request.role().isBlank() ? "MEMBER" : request.role();
        int maxUses = request.maxUses() == null ? 1 : request.maxUses();
        LocalDateTime expiresAt =
                request.expiresInHours() == null
                        ? null
                        : LocalDateTime.now().plusHours(request.expiresInHours());
        if (inviteCodes.selectCount(
                        new LambdaQueryWrapper<InviteCode>()
                                .eq(InviteCode::getHouseholdId, principal.householdId()))
                >= HOUSEHOLD_LIMIT)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "邀请码数量已达上限，请先作废旧邀请码");

        String plain = generator.generate();
        String hash = generator.hash(generator.normalize(plain));
        // 32 字符表下 8 位明文碰撞概率可忽略，仍以哈希唯一约束兜底重试。
        for (int i = 0; i < 5 && inviteCodes.selectCount(
                        new LambdaQueryWrapper<InviteCode>().eq(InviteCode::getCodeHash, hash))
                > 0; i++) {
            plain = generator.generate();
            hash = generator.hash(generator.normalize(plain));
        }
        var code = new InviteCode();
        code.setHouseholdId(principal.householdId());
        code.setCodeHash(hash);
        code.setCodeHint(generator.hint(generator.normalize(plain)));
        code.setRole(role);
        code.setMaxUses(maxUses);
        code.setUsedCount(0);
        code.setEnabled(true);
        code.setExpiresAt(expiresAt);
        code.setCreatedBy(principal.userId());
        code.setCreatedAt(LocalDateTime.now());
        inviteCodes.insert(code);
        return InviteCodeConverter.toVO(code, plain);
    }

    @Override
    public List<InviteCodeVO> list(SessionPrincipal principal) {
        principal.requireAdmin();
        return inviteCodes
                .selectList(
                        new LambdaQueryWrapper<InviteCode>()
                                .eq(InviteCode::getHouseholdId, principal.householdId())
                                .orderByDesc(InviteCode::getId))
                .stream()
                .map(InviteCodeConverter::toVO)
                .toList();
    }

    @Override
    @Transactional
    public void revoke(SessionPrincipal principal, long id) {
        principal.requireAdmin();
        households.lock(principal.householdId());
        var target =
                inviteCodes.selectOne(
                        new LambdaQueryWrapper<InviteCode>()
                                .eq(InviteCode::getId, id)
                                .eq(InviteCode::getHouseholdId, principal.householdId()));
        if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "邀请码不存在");
        inviteCodes.update(
                null,
                new LambdaUpdateWrapper<InviteCode>()
                        .eq(InviteCode::getId, id)
                        .eq(InviteCode::getHouseholdId, principal.householdId())
                        .set(InviteCode::getEnabled, false));
    }
}
