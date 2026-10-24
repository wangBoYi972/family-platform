package com.familyhome.user.vo;

/**
 * 邀请码视图。
 *
 * <p>{@code code} 仅在发放接口返回一次明文，列表查询恒为 null，避免已发放的邀请码被再次读取。
 */
public record InviteCodeVO(
        Long id,
        String code,
        String hint,
        String role,
        int maxUses,
        int usedCount,
        Boolean enabled,
        Boolean expired,
        String expiresAt,
        String createdAt,
        String usedPhone) {}
