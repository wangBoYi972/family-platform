package com.familyhome.user.controller;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.security.SessionPrincipal;
import com.familyhome.user.dto.InviteCodeCreateDTO;
import com.familyhome.user.service.InviteCodeService;
import com.familyhome.user.vo.InviteCodeVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 邀请码管理：仅管理员可访问，用于向家人发放注册凭据。 */
@RestController
@RequestMapping("/api/users/invite-codes")
public class InviteCodeController {
    @Autowired
    private InviteCodeService service;

    @GetMapping
    public ApiResponse<List<InviteCodeVO>> list(HttpServletRequest request) {
        return ApiResponse.success(service.list(SessionPrincipal.from(request)));
    }

    /** 发放邀请码，响应体中的明文只出现这一次，请通过私密渠道转交。 */
    @PostMapping
    public ApiResponse<InviteCodeVO> issue(
            HttpServletRequest request, @Valid @RequestBody InviteCodeCreateDTO input) {
        return ApiResponse.success(service.issue(SessionPrincipal.from(request), input));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> revoke(HttpServletRequest request, @PathVariable long id) {
        service.revoke(SessionPrincipal.from(request), id);
        return ApiResponse.success(null);
    }
}
