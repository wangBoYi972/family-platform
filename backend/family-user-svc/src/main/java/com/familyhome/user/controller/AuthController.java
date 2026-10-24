package com.familyhome.user.controller;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.user.dto.LoginDTO;
import com.familyhome.user.service.AuthService;
import com.familyhome.user.vo.LoginVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @Autowired
    private AuthService authService;
    @Autowired
    private com.familyhome.user.service.RegistrationService registration;

    @PostMapping("/login")
    public ApiResponse<LoginVO> login(@Valid @RequestBody LoginDTO request) {
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/password-login")
    public ApiResponse<LoginVO> passwordLogin(
            @Valid @RequestBody com.familyhome.user.dto.PasswordLoginDTO request) {
        return ApiResponse.success(authService.passwordLogin(request));
    }

    /** 开放注册：不要求登录态，但必须携带管理员发放的有效邀请码。 */
    @PostMapping("/register")
    public ApiResponse<LoginVO> register(
            @Valid @RequestBody com.familyhome.user.dto.RegisterDTO request) {
        return ApiResponse.success(registration.register(request));
    }
}
