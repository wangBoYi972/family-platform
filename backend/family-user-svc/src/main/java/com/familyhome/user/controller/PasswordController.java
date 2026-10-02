package com.familyhome.user.controller;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.security.SessionPrincipal;
import com.familyhome.user.dto.PasswordChangeDTO;
import com.familyhome.user.dto.PasswordSetDTO;
import com.familyhome.user.service.PasswordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PasswordController {
    @Autowired
    private PasswordService passwords;

    @PostMapping("/users/accounts")
    public ApiResponse<Void> create(
            HttpServletRequest request,
            @Valid @RequestBody com.familyhome.user.dto.AccountCreateDTO input) {
        passwords.create(SessionPrincipal.from(request), input);
        return ApiResponse.success(null);
    }

    @PutMapping("/users/whitelist/{id}/password")
    public ApiResponse<Void> set(
            HttpServletRequest request, @PathVariable long id, @Valid @RequestBody PasswordSetDTO input) {
        passwords.set(SessionPrincipal.from(request), id, input.password());
        return ApiResponse.success(null);
    }

    @PutMapping("/auth/password")
    public ApiResponse<Void> change(
            HttpServletRequest request, @Valid @RequestBody PasswordChangeDTO input) {
        passwords.change(SessionPrincipal.from(request), input);
        return ApiResponse.success(null);
    }
}
