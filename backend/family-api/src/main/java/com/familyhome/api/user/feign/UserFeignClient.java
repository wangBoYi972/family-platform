package com.familyhome.api.user.feign;

import com.familyhome.api.user.vo.MemberApiVO;
import com.familyhome.api.user.vo.UserSessionApiVO;
import com.familyhome.common.api.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(
        name = "family-user-svc",
        contextId = "familyUserClient",
        url = "${family.clients.user-url:}",
        fallback = UserFeignClientFallback.class)
public interface UserFeignClient {
    @GetMapping("/api/auth/session")
    ApiResponse<UserSessionApiVO> session(@RequestHeader("Authorization") String bearer);

    @GetMapping("/api/users/members")
    ApiResponse<List<MemberApiVO>> members(@RequestHeader("Authorization") String bearer);

    /**
     * 批量查询用户信息
     * 避免 N+1 查询问题
     *
     * @param bearer 认证令牌
     * @param userIds 用户ID列表(逗号分隔)
     * @return 用户ID -> 用户信息的映射
     */
    @GetMapping("/api/users/batch")
    ApiResponse<Map<Long, MemberApiVO>> batchGetUsers(
            @RequestHeader("Authorization") String bearer,
            @RequestParam("ids") String userIds);
}
