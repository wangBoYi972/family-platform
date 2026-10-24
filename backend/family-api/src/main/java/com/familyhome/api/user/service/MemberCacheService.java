package com.familyhome.api.user.service;

import com.familyhome.api.user.feign.UserFeignClient;
import com.familyhome.api.user.vo.MemberApiVO;
import com.familyhome.common.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 成员信息缓存服务
 * 减少对 user-svc 的频繁调用
 */
@Slf4j
@Service
public class MemberCacheService {

    private static final long CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5);

    @Autowired
    private UserFeignClient userClient;

    // 缓存结构: householdId -> CacheEntry
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 获取家庭成员列表(带缓存)
     */
    public List<MemberApiVO> getMembers(String bearer, Long householdId) {
        CacheEntry entry = cache.get(householdId);
        long now = System.currentTimeMillis();

        // 缓存命中且未过期
        if (entry != null && (now - entry.timestamp) < CACHE_TTL_MS) {
            log.debug("成员缓存命中,家庭ID: {}", householdId);
            return entry.members;
        }

        // 缓存未命中或已过期,调用远程服务
        log.debug("成员缓存未命中,调用远程服务,家庭ID: {}", householdId);
        ApiResponse<List<MemberApiVO>> response = userClient.members(bearer);

        // 检查响应是否成功
        if (response.code() != 0 || response.data() == null) {
            // 如果远程调用失败,且有过期缓存,返回过期数据
            if (entry != null) {
                log.warn("远程调用失败,返回过期缓存,家庭ID: {}", householdId);
                return entry.members;
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "无法获取成员信息: " + response.msg());
        }

        // 更新缓存
        cache.put(householdId, new CacheEntry(now, response.data()));
        return response.data();
    }

    /**
     * 根据成员ID查找成员
     */
    public MemberApiVO getMemberById(String bearer, Long householdId, Long memberId) {
        List<MemberApiVO> members = getMembers(bearer, householdId);
        return members.stream()
                .filter(m -> m.id().equals(memberId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "负责人必须是本家庭启用成员"));
    }

    /**
     * 清除指定家庭的缓存
     */
    public void evict(Long householdId) {
        cache.remove(householdId);
        log.debug("清除成员缓存,家庭ID: {}", householdId);
    }

    /**
     * 清除所有缓存
     */
    public void evictAll() {
        cache.clear();
        log.info("清除所有成员缓存");
    }

    private static class CacheEntry {
        final long timestamp;
        final List<MemberApiVO> members;

        CacheEntry(long timestamp, List<MemberApiVO> members) {
            this.timestamp = timestamp;
            this.members = members;
        }
    }
}
