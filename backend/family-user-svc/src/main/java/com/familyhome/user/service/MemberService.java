package com.familyhome.user.service;

import com.familyhome.security.SessionPrincipal;
import com.familyhome.user.vo.MemberVO;
import com.familyhome.user.vo.ProfileVO;
import com.familyhome.user.vo.WhitelistVO;
import java.util.List;
import java.util.Map;

public interface MemberService {
    void logout(SessionPrincipal p);

    ProfileVO me(SessionPrincipal p);

    void profile(SessionPrincipal p, String name);

    List<MemberVO> members(SessionPrincipal p);

    /**
     * 批量查询用户信息
     *
     * @param p 会话信息
     * @param userIds 用户ID列表(逗号分隔)
     * @return 用户ID -> 用户信息的映射
     */
    Map<Long, MemberVO> batchGetUsers(SessionPrincipal p, String userIds);

    List<WhitelistVO> whitelist(SessionPrincipal p);

    void add(SessionPrincipal p, String phone, String role);

    void disable(SessionPrincipal p, long id);
}
