package com.familyhome.media.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 发起通话。
 *
 * <p>一对一：{@code mode=DIRECT} + {@code calleeId}；多人：{@code mode=GROUP} + {@code participantIds}。
 * 目标必须是同一家庭的成员，服务端会二次校验，不信任客户端声明。
 */
public record CallStartDTO(
        @Pattern(regexp = "DIRECT|GROUP") String mode,
        @NotBlank @Pattern(regexp = "AUDIO|VIDEO") String kind,
        Long calleeId,
        @Size(max = 8) List<Long> participantIds) {}
