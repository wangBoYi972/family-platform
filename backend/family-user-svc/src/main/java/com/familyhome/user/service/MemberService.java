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

    /**
     * 管理端的成员账号列表。
     *
     * <p>keyword 在 SQL 里模糊匹配手机号或角色——管理端只拿到过滤后的这一页，
     * 本地过滤会漏掉还没加载的账号。
     *
     * @param keyword 手机号或角色模糊匹配，空值表示不筛
     */
    List<WhitelistVO> whitelist(SessionPrincipal p, String keyword);

    void add(SessionPrincipal p, String phone, String role);

    void disable(SessionPrincipal p, long id);
}
