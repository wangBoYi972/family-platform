package com.familyhome.user.vo;

public record LoginVO(String token, Long userId, Long householdId, String role) {}
