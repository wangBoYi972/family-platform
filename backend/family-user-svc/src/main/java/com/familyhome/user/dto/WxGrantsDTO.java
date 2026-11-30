package com.familyhome.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 客户端上报的订阅授权结果。requestSubscribeMessage 一次可以带多个模板，
 * 每个模板要么 accept（落一行配额）要么 reject（忽略，不落行也不报错）。
 */
public record WxGrantsDTO(@NotEmpty @Size(max = 8) List<Grant> grants) {
    public record Grant(@NotBlank String templateKey, boolean accepted) {}
}
