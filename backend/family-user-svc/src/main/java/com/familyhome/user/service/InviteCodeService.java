package com.familyhome.user.service;

import com.familyhome.security.SessionPrincipal;
import com.familyhome.user.dto.InviteCodeCreateDTO;
import com.familyhome.user.vo.InviteCodeVO;
import java.util.List;

/** 邀请码发放：只有管理员可以生成、查看和作废本家庭的邀请码。 */
public interface InviteCodeService {
    /** 发放一枚邀请码，返回体中带一次性明文。 */
    InviteCodeVO issue(SessionPrincipal principal, InviteCodeCreateDTO request);

    List<InviteCodeVO> list(SessionPrincipal principal);

    void revoke(SessionPrincipal principal, long id);
}
