package com.familyhome.api.user.vo;

public record UserSessionApiVO(Long userId, Long householdId, String role, String client) {}
