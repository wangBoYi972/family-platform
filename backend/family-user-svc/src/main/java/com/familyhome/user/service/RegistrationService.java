package com.familyhome.user.service;

import com.familyhome.user.dto.RegisterDTO;
import com.familyhome.user.vo.LoginVO;

/** 开放注册：必须携带管理员发放的有效邀请码，否则一律拒绝。 */
public interface RegistrationService {
    /** 校验邀请码并创建账号，成功后直接返回可登录的会话令牌。 */
    LoginVO register(RegisterDTO request);
}
