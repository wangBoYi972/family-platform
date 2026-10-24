package com.familyhome.api.user.feign;

import com.familyhome.api.user.vo.MemberApiVO;
import com.familyhome.api.user.vo.UserSessionApiVO;
import com.familyhome.common.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * UserFeignClient 降级处理
 * 当 user-svc 不可用时提供兜底响应
 */
@Slf4j
@Component
public class UserFeignClientFallback implements UserFeignClient {

    @Override
    public ApiResponse<UserSessionApiVO> session(String bearer) {
        log.error("UserFeignClient.session 调用失败,触发降级");
        return ApiResponse.failure(503, "用户服务暂时不可用");
    }

    @Override
    public ApiResponse<List<MemberApiVO>> members(String bearer) {
        log.error("UserFeignClient.members 调用失败,触发降级");
        return ApiResponse.failure(503, "用户服务暂时不可用");
    }

    @Override
    public ApiResponse<Map<Long, MemberApiVO>> batchGetUsers(String bearer, String userIds) {
        log.error("UserFeignClient.batchGetUsers 调用失败,触发降级,userIds: {}", userIds);
        return ApiResponse.failure(503, "用户服务暂时不可用");
    }
}
