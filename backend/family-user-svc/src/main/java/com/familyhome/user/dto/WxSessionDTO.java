package com.familyhome.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** wx.login 拿到的临时 code，后端拿它换 openid（code5 分钟有效、一次性）。 */
public record WxSessionDTO(@NotBlank @Size(max = 128) String code) {}
