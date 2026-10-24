package com.familyhome.user.controller;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.security.SessionPrincipal;
import com.familyhome.user.dto.MemberCreateDTO;
import com.familyhome.user.dto.ProfileUpdateDTO;
import com.familyhome.user.service.MemberService;
import com.familyhome.user.vo.MemberVO;
import com.familyhome.user.vo.ProfileVO;
import com.familyhome.user.vo.SessionVO;
import com.familyhome.user.vo.WhitelistVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MemberController {
    @Autowired
    private MemberService service;

    @GetMapping("/auth/session")
    public ApiResponse<SessionVO> session(HttpServletRequest r) {
        var p = SessionPrincipal.from(r);
        return ApiResponse.success(new SessionVO(p.userId(), p.householdId(), p.role(), p.client()));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(HttpServletRequest r) {
        service.logout(SessionPrincipal.from(r));
        return ApiResponse.success(null);
    }

    @GetMapping("/users/me")
    public ApiResponse<ProfileVO> me(HttpServletRequest r) {
        return ApiResponse.success(service.me(SessionPrincipal.from(r)));
    }

    @PatchMapping("/users/me")
    public ApiResponse<Void> profile(HttpServletRequest r, @Valid @RequestBody ProfileUpdateDTO i) {
        service.profile(SessionPrincipal.from(r), i.displayName());
        return ApiResponse.success(null);
    }

    @GetMapping("/users/members")
    public ApiResponse<List<MemberVO>> members(HttpServletRequest r) {
        return ApiResponse.success(service.members(SessionPrincipal.from(r)));
    }

    /**
     * 批量查询用户信息
     * 用于避免 N+1 查询问题
     *
     * @param r HTTP 请求
     * @param ids 用户ID列表(逗号分隔,如 "1,2,3")
     * @return 用户ID -> 用户信息的映射
     */
    @GetMapping("/users/batch")
    public ApiResponse<java.util.Map<Long, MemberVO>> batchGetUsers(
            HttpServletRequest r,
            @RequestParam("ids") @NotBlank String ids) {
        SessionPrincipal p = SessionPrincipal.from(r);
        return ApiResponse.success(service.batchGetUsers(p, ids));
    }

    @GetMapping("/users/whitelist")
    public ApiResponse<List<WhitelistVO>> whitelist(HttpServletRequest r) {
        return ApiResponse.success(service.whitelist(SessionPrincipal.from(r)));
    }

    @PostMapping("/users/whitelist")
    public ApiResponse<Void> add(HttpServletRequest r, @Valid @RequestBody MemberCreateDTO i) {
        service.add(SessionPrincipal.from(r), i.phone(), i.role());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/users/whitelist/{id}")
    public ApiResponse<Void> disable(HttpServletRequest r, @PathVariable long id) {
        service.disable(SessionPrincipal.from(r), id);
        return ApiResponse.success(null);
    }
}
