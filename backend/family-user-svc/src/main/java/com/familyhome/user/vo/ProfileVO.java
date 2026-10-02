package com.familyhome.user.vo;

public record ProfileVO(
        Long id, String displayName, String householdName, String role, Long householdId) {}
