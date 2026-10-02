package com.familyhome.api.user.feign;

import com.familyhome.api.user.vo.MemberApiVO;
import com.familyhome.api.user.vo.UserSessionApiVO;
import com.familyhome.common.api.ApiResponse;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(
        name = "family-user-svc",
        contextId = "familyUserClient",
        url = "${family.clients.user-url:}")
public interface UserFeignClient {
    @GetMapping("/api/auth/session")
    ApiResponse<UserSessionApiVO> session(@RequestHeader("Authorization") String bearer);

    @GetMapping("/api/users/members")
    ApiResponse<List<MemberApiVO>> members(@RequestHeader("Authorization") String bearer);
}
