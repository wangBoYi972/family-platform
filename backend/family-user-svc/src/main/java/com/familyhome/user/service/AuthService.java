package com.familyhome.user.service;

import com.familyhome.user.dto.LoginDTO;
import com.familyhome.user.vo.LoginVO;

public interface AuthService {
    LoginVO login(LoginDTO request);

    LoginVO passwordLogin(com.familyhome.user.dto.PasswordLoginDTO request);
}
