package com.familyhome.user.service;

import com.familyhome.security.SessionPrincipal;
import com.familyhome.user.vo.MemberVO;
import com.familyhome.user.vo.ProfileVO;
import com.familyhome.user.vo.WhitelistVO;
import java.util.List;

public interface MemberService {
    void logout(SessionPrincipal p);

    ProfileVO me(SessionPrincipal p);

    void profile(SessionPrincipal p, String name);

    List<MemberVO> members(SessionPrincipal p);

    List<WhitelistVO> whitelist(SessionPrincipal p);

    void add(SessionPrincipal p, String phone, String role);

    void disable(SessionPrincipal p, long id);
}
