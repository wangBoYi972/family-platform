package com.familyhome.user.converter;

import com.familyhome.user.model.InviteCode;
import com.familyhome.user.vo.InviteCodeVO;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

public final class InviteCodeConverter {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private InviteCodeConverter() {}

    /** 列表视图：只带回显尾号，明文不返回。 */
    public static InviteCodeVO toVO(InviteCode e) {
        return toVO(e, null);
    }

    public static InviteCodeVO toVO(InviteCode e, String plainCode) {
        return new InviteCodeVO(
                e.getId(),
                plainCode,
                e.getCodeHint(),
                e.getRole(),
                e.getMaxUses() == null ? 1 : e.getMaxUses(),
                e.getUsedCount() == null ? 0 : e.getUsedCount(),
                Boolean.TRUE.equals(e.getEnabled()),
                expired(e.getExpiresAt()),
                e.getExpiresAt() == null ? null : FORMAT.format(e.getExpiresAt()),
                e.getCreatedAt() == null ? null : FORMAT.format(e.getCreatedAt()),
                e.getUsedPhone());
    }

    public static boolean expired(LocalDateTime expiresAt) {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }
}
